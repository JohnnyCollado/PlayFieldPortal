package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction

/**
 * Everything [ArtworkStudioContent] can ask of the Studio. [ArtworkStudioViewModel] implements it,
 * which is what lets the content be previewed with sample state and a no-op implementation
 * instead of a Hilt graph. Loading, closing and the local file picker stay in
 * [ArtworkStudioScreen], so they are not here.
 */
interface ArtworkStudioActions {
    fun handleGamepadAction(action: GamepadAction)

    // Tabs and sources
    fun selectTab(index: Int)
    fun sourcesForTab(): List<StudioSource>
    fun sourceBadge(source: StudioSource): String?
    fun selectSource(index: Int)
    fun requestLocalPick()
    fun toggleNsfw()

    // Search
    fun openSearch()
    fun onQueryDraftChanged(text: String)
    fun submitSearch()
    fun cancelSearch()
    fun resetSearchToTitle()

    // Match
    fun onChangeMatchPressed()
    fun onChangeMatchDraftChanged(text: String)
    fun startChangeMatchEdit()
    fun stopChangeMatchEdit()
    fun submitChangeMatch()
    fun confirmMatch(index: Int)
    fun cancelChangeMatch()
    fun forgetMatch()

    // Grid and paging
    fun onGridMeasured(widthDp: Float, heightDp: Float)
    fun openCandidate(index: Int)
    fun toggleSelection(index: Int)
    fun previousPage()
    fun nextPage()

    // Apply and the download queue
    fun applyChanges()
    fun resolveApplyConfirm(choice: StudioApplyChoice)
    fun retryFailed()
    fun removeFailed()
    fun resolveLeavePrompt(choice: StudioLeaveChoice)

    // Candidate preview
    fun applyCandidate()
    fun dismissCandidate()
    fun onManualPageCount(count: Int)
    fun manualPreviousPage()
    fun manualNextPage()

    // Actions menu and crop
    fun openActions()
    fun closeActions()
    fun runAction(action: StudioAction)
    fun panCrop(dx: Float, dy: Float)
    fun zoomCrop(factor: Float)
    fun applyCrop()
    fun cancelCrop()

    fun dismissMessage()
}
