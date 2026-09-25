package com.playfieldportal.feature.settings.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.database.entity.ArtworkOrphanFileEntity
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.match.OrphanTitleRanker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** One artwork file the last relink could not place. */
data class OrphanRowUi(
    val platformId: String,
    val artworkType: String,
    val fileName: String,
    val stem: String,
)

/** A game the user may assign the selected file to. */
data class OrphanCandidateUi(val gameId: Long, val title: String)

data class ArtworkOrphanUiState(
    val loading: Boolean = true,
    val orphans: List<OrphanRowUi> = emptyList(),
    /** The file being resolved, or null when the list is showing. */
    val selected: OrphanRowUi? = null,
    /** Exactly what the user typed. Never used to trigger anything on its own. */
    val query: String = "",
    /**
     * Null until a search has been executed for the current selection. Null renders "type a title
     * and press Search"; an empty list renders "no matches". The distinction is the whole reason
     * this is nullable rather than an empty list.
     */
    val results: List<OrphanCandidateUi>? = null,
    val searching: Boolean = false,
    val notice: String? = null,
)

/**
 * The orphan picker (C22 task T4).
 *
 * **Results appear only when the user executes a search.** There is exactly one entry point that
 * produces results — [search] — and it is called from nothing but an explicit press. There is no
 * debounce, no `LaunchedEffect(query)` and no flow derived from [ArtworkOrphanUiState.query]:
 * [onQueryChange] writes the text and nothing else. That is a product requirement, and it is meant
 * to stay greppable.
 *
 * Nothing here touches a provider. Matching a stray file to a game the user already owns is a
 * question about the database.
 */
@HiltViewModel
class ArtworkOrphanViewModel @Inject constructor(
    private val importManager: ArtworkImportManager,
    private val gameRepository: GameRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtworkOrphanUiState())
    val uiState: StateFlow<ArtworkOrphanUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            importManager.orphanFiles.collect { rows ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    orphans = rows.map { it.toUi() },
                )
            }
        }
    }

    /** Opens [row] for resolution, with the field pre-filled from the file's own name. */
    fun select(row: OrphanRowUi) {
        _uiState.value = _uiState.value.copy(
            selected = row,
            query = row.stem,
            results = null,      // a new selection has not been searched for yet
            notice = null,
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selected = null, query = "", results = null)
    }

    /** Records what the user typed. Deliberately does not search. */
    fun onQueryChange(text: String) {
        _uiState.value = _uiState.value.copy(query = text)
    }

    /** The only thing that produces results. Called from an explicit press, and nowhere else. */
    fun search() {
        val state = _uiState.value
        val selected = state.selected ?: return
        if (state.searching) return
        _uiState.value = state.copy(searching = true)
        viewModelScope.launch {
            // Only this file's own platform: a cover in ps2/covers/ belongs to a PS2 game, and
            // offering the whole library would make the list useless and the mistake easy.
            val candidates = gameRepository.getByPlatform(selected.platformId)
                .map { OrphanTitleRanker.Candidate(it.id, it.title, it.romPath?.romStem()) }
            val ranked = OrphanTitleRanker.rank(state.query, candidates)
            _uiState.value = _uiState.value.copy(
                searching = false,
                results = ranked.map { OrphanCandidateUi(it.candidate.gameId, it.candidate.title) },
            )
        }
    }

    /** Ties the selected file to [gameId]; the row then disappears from the list. */
    fun assign(gameId: Long) {
        val selected = _uiState.value.selected ?: return
        viewModelScope.launch {
            val ok = runCatching {
                importManager.assignOrphan(
                    platformId = selected.platformId,
                    artworkType = selected.artworkType,
                    fileName = selected.fileName,
                    gameId = gameId,
                )
            }.onFailure { Timber.e(it, "Assigning orphan artwork failed") }.getOrDefault(false)
            _uiState.value = _uiState.value.copy(
                selected = null,
                query = "",
                results = null,
                notice = if (ok) "Artwork linked — it will stay linked through future scans."
                else "Couldn't link that file — run Scan & Relink and try again.",
            )
        }
    }

    fun dismissNotice() {
        _uiState.value = _uiState.value.copy(notice = null)
    }

    private fun ArtworkOrphanFileEntity.toUi() = OrphanRowUi(
        platformId = platformId,
        artworkType = artworkType,
        fileName = fileName,
        stem = stem,
    )

    private fun String.romStem(): String? = replace('\\', '/')
        .substringAfterLast('/')
        .substringBeforeLast('.')
        .takeIf { it.isNotBlank() }
}
