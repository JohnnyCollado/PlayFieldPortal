package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.domain.model.GamepadAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the "convert detected games?" multi-select picker shared by every entry point (the XMB
 * Windows card, the Library Manager, and the batch matcher's convertible pile). It is handed the
 * folders that carry an app id but no `achievements.json` yet; the panel shows them all at once with
 * a checkbox each, and on confirm the selected games are converted through
 * [LocalSteamSchemaGenerator] in one batch.
 *
 * Framework-agnostic: it exposes a [picker] StateFlow the screens render a panel from, so the same
 * controller and panel serve every surface. Owned per ViewModel (constructed with the ViewModel's
 * scope), never a singleton — which is why the FOCUS index lives here too. It is the hosting
 * ViewModel's state by construction, and duplicating it into each host would be two chances for the
 * XMB and the settings screen to behave differently on a controller.
 *
 * Rows are probed before the panel opens, so a game Steam keeps no achievement list for is shown as
 * unselectable up front rather than checked, converted, and then failed at write time.
 */
class LocalSteamConvertPickerController(
    private val generator: LocalSteamSchemaGenerator,
    private val scope: CoroutineScope,
) {
    /** One selectable row; position in the list is the toggle key. */
    data class Row(
        val folderName: String,
        val appId: String,
        val selected: Boolean,
        /** How many coins this game has on Steam, or null while unknown / unavailable. */
        val achievementCount: Int? = null,
        /** True when the row cannot be converted at all, with [note] saying why. */
        val unselectable: Boolean = false,
        /** The quiet second line: the coin count, or the reason this row is greyed out. */
        val note: String,
    )

    /** The open picker. Null when idle. */
    data class Picker(
        val rows: List<Row>,
        /** `0..rows.lastIndex`; the action row is reached with Start / a tap, not with focus. */
        val focus: Int = 0,
        /** True while the rows' achievement counts are still being fetched. */
        val loading: Boolean = false,
        /** True once conversion is running, so a second confirm cannot double-write. */
        val converting: Boolean = false,
    ) {
        val selectedCount: Int get() = rows.count { it.selected && !it.unselectable }

        val focusedRow: Row? get() = rows.getOrNull(focus)

        /** Whether anything can be converted — the state the confirm action is enabled on. */
        val canConfirm: Boolean get() = !loading && !converting && selectedCount > 0
    }

    /** Tally of a completed run, for the caller's summary message. */
    data class Outcome(
        val converted: Int,
        val noAchievements: Int,
        val noKey: Int,
        val failed: Int,
        val skipped: Int,
        /** The folders a kit was actually written into, so the caller can link and sync them. */
        val convertedFolders: List<LocalSteamGame> = emptyList(),
    )

    private val _picker = MutableStateFlow<Picker?>(null)
    val picker: StateFlow<Picker?> = _picker.asStateFlow()

    // Parallel to _picker.rows by index, so a toggle never depends on appId uniqueness (the same
    // game can be installed under two folders).
    private var games: List<LocalSteamGame> = emptyList()
    private var onComplete: ((Outcome) -> Unit)? = null
    private var running = false

    /**
     * Opens the picker for [convertible] (every convertible row pre-checked). [onComplete] fires once
     * the run finishes — immediately with a zero outcome when [convertible] is empty. Ignored if a
     * run is already in progress.
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
        // Rows first with no counts, so the panel appears immediately; the probe then fills them in.
        _picker.value = Picker(
            rows = convertible.map { Row(it.folderName, it.appId, selected = true, note = "Checking Steam…") },
            loading = true,
        )
        scope.launch { probeRows(convertible) }
    }

    /** Fills each row's coin count, and marks the ones Steam keeps no list for as unselectable. */
    private suspend fun probeRows(convertible: List<LocalSteamGame>) {
        for ((index, game) in convertible.withIndex()) {
            val probe = generator.probe(game.appId)
            val current = _picker.value ?: return
            val row = current.rows.getOrNull(index) ?: continue
            _picker.value = current.copy(
                rows = current.rows.toMutableList().also { rows ->
                    rows[index] = when (probe) {
                        is LocalSteamSchemaGenerator.Probe.Count -> row.copy(
                            achievementCount = probe.count,
                            note = "${probe.count} coin${if (probe.count == 1) "" else "s"}  ·  appid ${game.appId}",
                        )
                        // Not a failure, and not the user's to fix: Steam simply has no list.
                        LocalSteamSchemaGenerator.Probe.NoAchievements -> row.copy(
                            selected = false,
                            unselectable = true,
                            note = "No list on Steam  ·  appid ${game.appId}",
                        )
                        LocalSteamSchemaGenerator.Probe.NoKey -> row.copy(
                            selected = false,
                            unselectable = true,
                            note = "Needs your Steam Web API key  ·  appid ${game.appId}",
                        )
                        // Temporary, so the row stays selectable — trying again is reasonable.
                        LocalSteamSchemaGenerator.Probe.Unavailable ->
                            row.copy(note = "Steam didn't answer  ·  appid ${game.appId}")
                    }
                },
            )
        }
        _picker.value = _picker.value?.copy(loading = false)
    }

    /** Controller input while the panel is open. True when the action was the panel's to take. */
    fun onGamepadAction(action: GamepadAction): Boolean {
        val current = _picker.value ?: return false
        when (action) {
            GamepadAction.NAVIGATE_UP -> moveFocus(-1)
            GamepadAction.NAVIGATE_DOWN -> moveFocus(1)
            GamepadAction.SELECT -> toggle(current.focus)
            // Triangle is all-or-none, matching the panel's own prompt line.
            GamepadAction.OPEN_CONTEXT_MENU -> setAll(current.rows.any { !it.selected && !it.unselectable })
            GamepadAction.HOME -> confirm()
            GamepadAction.BACK -> cancel()
            else -> return false
        }
        return true
    }

    /** Move the cursor, clamped — the list does not wrap, so held input settles at an end. */
    fun moveFocus(delta: Int) {
        val current = _picker.value ?: return
        if (current.rows.isEmpty()) return
        _picker.value = current.copy(focus = (current.focus + delta).coerceIn(0, current.rows.lastIndex))
    }

    /** Flip the checkbox for the row at [index]. An unselectable row cannot be checked. */
    fun toggle(index: Int) {
        val cur = _picker.value ?: return
        if (cur.converting) return
        val row = cur.rows.getOrNull(index) ?: return
        if (row.unselectable) return
        _picker.value = cur.copy(
            focus = index,
            rows = cur.rows.mapIndexed { i, r -> if (i == index) r.copy(selected = !r.selected) else r },
        )
    }

    /** Check or uncheck every convertible row at once. */
    fun setAll(selected: Boolean) {
        val cur = _picker.value ?: return
        if (cur.converting) return
        _picker.value = cur.copy(
            rows = cur.rows.map { if (it.unselectable) it else it.copy(selected = selected) },
        )
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

    /**
     * Closes the panel and reports nothing to convert — the "Skip & Sync" exit.
     *
     * Distinct from [cancel] only in intent, and deliberately identical in effect: neither writes
     * anything into a game folder. The caller links the folders that were already ready either way,
     * which is what makes skipping a real option rather than a dead end.
     */
    fun skip() = cancel()

    /** Convert every checked game, then report the tally. */
    fun confirm() {
        val cur = _picker.value ?: return
        if (!cur.canConfirm) return
        val toConvert = games.filterIndexed { i, _ ->
            cur.rows.getOrNull(i)?.let { it.selected && !it.unselectable } == true
        }
        val skipped = games.size - toConvert.size
        _picker.value = null // hide the panel while the network writes run
        scope.launch {
            var converted = 0
            var noAchievements = 0
            var noKey = 0
            var failed = 0
            val convertedFolders = mutableListOf<LocalSteamGame>()
            for (game in toConvert) {
                when (generator.generate(game)) {
                    is LocalSteamSchemaGenerator.Result.Written -> {
                        converted++
                        convertedFolders += game
                    }
                    is LocalSteamSchemaGenerator.Result.NoAchievements -> noAchievements++
                    is LocalSteamSchemaGenerator.Result.NoKey -> noKey++
                    is LocalSteamSchemaGenerator.Result.Failed -> failed++
                }
            }
            val done = onComplete
            val outcome = Outcome(converted, noAchievements, noKey, failed, skipped, convertedFolders)
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
