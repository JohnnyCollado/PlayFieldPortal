package com.playfieldportal.feature.achievements.provider.localsteam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "convert detected games?" multi-select picker shared by every scan surface (the XMB
 * Windows card and the Library Manager). A scan hands it the folders that carry a
 * `steam_settings/steam_appid.txt` but no `achievements.json` yet; the UI shows them all at once
 * with a checkbox each (every row pre-checked), and on confirm the selected games are converted
 * through [LocalSteamSchemaGenerator] in one batch.
 *
 * Framework-agnostic: it exposes a [picker] StateFlow the screens render a dialog from, so the same
 * controller and dialog serve both surfaces. Owned per ViewModel (constructed with the ViewModel's
 * scope), never a singleton. Replaces the retired per-game LocalSteamSchemaPromptController.
 */
class LocalSteamConvertPickerController(
    private val generator: LocalSteamSchemaGenerator,
    private val scope: CoroutineScope,
) {
    /** One selectable row; [selected] is user-toggled, position-keyed to the backing game list. */
    data class Row(val folderName: String, val appId: String, val selected: Boolean)

    /** The open picker. Null when idle. */
    data class Picker(val rows: List<Row>) {
        val selectedCount: Int get() = rows.count { it.selected }
    }

    /** Tally of a completed run, for the caller's summary message. */
    data class Outcome(
        val converted: Int,
        val noAchievements: Int,
        val noKey: Int,
        val failed: Int,
        val skipped: Int,
    )

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    // Parallel to _picker.rows by index, so a toggle never depends on appId uniqueness (the same
    // game can be installed under two folders).
    private var games: List<LocalSteamGame> = emptyList()
    private var onComplete: ((Outcome) -> Unit)? = null
    private var running = false

    /**
     * Opens the picker for [convertible] (all rows pre-checked). [onComplete] fires once the run
     * finishes — immediately with a zero outcome when [convertible] is empty. Ignored if a run is
     * already in progress.
     */
    fun start(convertible: List<LocalSteamGame>, onComplete: (Outcome) -> Unit) {
        if (running) return
        if (convertible.isEmpty()) {
            onComplete(Outcome(0, 0, 0, 0, 0))
            return
        }
        games = convertible
        this.onComplete = onComplete
        running = true
        _picker.value = Picker(convertible.map { Row(it.folderName, it.appId, selected = true) })
    }

    /** Flip the checkbox for the row at [index]. */
    fun toggle(index: Int) {
        val cur = _picker.value ?: return
        if (index !in cur.rows.indices) return
        _picker.value = cur.copy(
            rows = cur.rows.mapIndexed { i, row -> if (i == index) row.copy(selected = !row.selected) else row },
        )
    }

    /** Check or uncheck every row at once. */
    fun setAll(selected: Boolean) {
        val cur = _picker.value ?: return
        _picker.value = cur.copy(rows = cur.rows.map { it.copy(selected = selected) })
    }

    /** Dismiss without converting anything. */
    fun cancel() {
        if (!running) return
        val skipped = games.size
        _picker.value = null
        val done = onComplete
        reset()
        done?.invoke(Outcome(0, 0, 0, 0, skipped))
    }

    /** Convert every checked game, then report the tally. */
    fun confirm() {
        val cur = _picker.value ?: return
        val toConvert = games.filterIndexed { i, _ -> cur.rows.getOrNull(i)?.selected == true }
        val skipped = games.size - toConvert.size
        _picker.value = null // hide the dialog while the network writes run
        scope.launch {
            var converted = 0
            var noAchievements = 0
            var noKey = 0
            var failed = 0
            for (game in toConvert) {
                when (generator.generate(game)) {
                    is LocalSteamSchemaGenerator.Result.Written -> converted++
                    is LocalSteamSchemaGenerator.Result.NoAchievements -> noAchievements++
                    is LocalSteamSchemaGenerator.Result.NoKey -> noKey++
                    is LocalSteamSchemaGenerator.Result.Failed -> failed++
                }
            }
            val done = onComplete
            val outcome = Outcome(converted, noAchievements, noKey, failed, skipped)
            reset()
            done?.invoke(outcome)
        }
    }

    private fun reset() {
        games = emptyList()
        onComplete = null
        running = false
    }
}
