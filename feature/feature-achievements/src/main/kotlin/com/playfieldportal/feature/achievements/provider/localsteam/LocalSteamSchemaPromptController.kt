package com.playfieldportal.feature.achievements.provider.localsteam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the per-game "generate the missing achievement schema?" prompt shared by every scan
 * surface (the XMB Windows card and the Library Manager). A scan hands it the folders that lack a
 * `steam_settings/achievements.json`; it walks them one at a time, and for each the UI answers
 * [no], [yes], or [yesToAll] (approve the rest of this scan). Approved games are written through
 * [LocalSteamSchemaGenerator]. Yes-to-All is scoped to the current scan only — a fresh scan starts
 * from no standing permission.
 *
 * Framework-agnostic: it exposes a [prompt] StateFlow the screens render a dialog from, so the same
 * controller and the same dialog serve both surfaces. Owned per ViewModel (constructed with the
 * ViewModel's scope), never a singleton.
 */
class LocalSteamSchemaPromptController(
    private val generator: LocalSteamSchemaGenerator,
    private val scope: CoroutineScope,
) {
    /** The game currently awaiting a decision, with its place in the run. Null when idle. */
    data class Prompt(
        val folderName: String,
        val appId: String,
        val index: Int,
        val total: Int,
    )

    /** Tally of a completed run, for the caller's summary message. */
    data class Outcome(val generated: Int, val failed: Int, val skipped: Int)

    private val _prompt = MutableStateFlow<Prompt?>(null)
    val prompt: StateFlow<Prompt?> = _prompt.asStateFlow()

    private var queue: List<LocalSteamGame> = emptyList()
    private var cursor = 0
    private var generated = 0
    private var failed = 0
    private var skipped = 0
    private var onComplete: ((Outcome) -> Unit)? = null
    private var running = false

    /**
     * Begins prompting for [missing]. [onComplete] fires once every game has an answer (immediately
     * with a zero outcome when [missing] is empty). Ignored if a run is already in progress.
     */
    fun start(missing: List<LocalSteamGame>, onComplete: (Outcome) -> Unit) {
        if (running) return
        if (missing.isEmpty()) {
            onComplete(Outcome(0, 0, 0))
            return
        }
        queue = missing
        cursor = 0
        generated = 0
        failed = 0
        skipped = 0
        this.onComplete = onComplete
        running = true
        showCurrent()
    }

    /** Skip this game and move on. */
    fun no() {
        if (!running || _prompt.value == null) return
        skipped++
        advance()
    }

    /** Generate this game's schema, then move on. */
    fun yes() {
        if (!running || _prompt.value == null) return
        generate(remaining = false)
    }

    /** Generate this game and every remaining game in this run without further prompts. */
    fun yesToAll() {
        if (!running || _prompt.value == null) return
        generate(remaining = true)
    }

    private fun showCurrent() {
        val game = queue[cursor]
        _prompt.value = Prompt(game.folderName, game.appId, cursor + 1, queue.size)
    }

    private fun generate(remaining: Boolean) {
        _prompt.value = null // hide the dialog while the network write runs
        scope.launch {
            if (remaining) {
                for (i in cursor until queue.size) record(runOne(queue[i]))
                finish()
            } else {
                record(runOne(queue[cursor]))
                advance()
            }
        }
    }

    private suspend fun runOne(game: LocalSteamGame): Boolean =
        generator.generate(game) is LocalSteamSchemaGenerator.Result.Written

    private fun record(written: Boolean) {
        if (written) generated++ else failed++
    }

    private fun advance() {
        cursor++
        if (cursor >= queue.size) finish() else showCurrent()
    }

    private fun finish() {
        _prompt.value = null
        val done = onComplete
        val outcome = Outcome(generated, failed, skipped)
        reset()
        done?.invoke(outcome)
    }

    private fun reset() {
        queue = emptyList()
        cursor = 0
        onComplete = null
        running = false
    }
}
