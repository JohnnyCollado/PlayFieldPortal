package com.playfieldportal.core.ui.gesture

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.Modifier

/**
 * Drags on this element scroll [state] — the scroll container it *frames* rather than contains.
 *
 * Settings screens and the Setup Wizard lay their header, divider and footer out as **siblings** of
 * the scrolling body (`SettingsScaffold`), so a drag that starts on chrome has nothing under it to
 * scroll and simply dies. This modifier hands those dead regions the body's own [ScrollableState],
 * which the scaffold already holds for keep-in-view (`LocalSettingsScrollStateRegistrar`).
 *
 * **Why not `nestedScroll`.** Nested scroll connects an *ancestor* to a scrolling *descendant*. The
 * chrome here is neither: it is a sibling of the scroll container, one level up in a `Column`. There
 * is no nested-scroll relationship to intercept, so `scrollable` — a plain second gesture owner
 * pointed at the same state — is the smaller and more honest tool.
 *
 * **Attach it to SIBLINGS of the scroll container, never to an ancestor of it.** `scrollable` takes
 * part in nested scrolling, so an ancestor sharing the container's own [ScrollableState] becomes a
 * second owner of that state: the two then contend for its `MutatorMutex`, and a touch landing
 * during a fling is swallowed instead of catching it. This was observed — a drag right after a
 * scroll "stopped and consumed" — when the modifier was attached to the content Box wrapping a
 * `verticalScroll` body. Chrome laid out beside the container (a header or footer in the same
 * Column) has no nested-scroll relationship with it and is safe.
 *
 * **The [ScrollableState] type, not `ScrollState`,** so one implementation serves `ScrollState`,
 * `LazyListState` and `LazyGridState` — the drawer and picker grids share this layout shape and are
 * a later wiring job, not a second modifier.
 *
 * [state] is nullable and a null state is a no-op: a screen that never registers its scroll owner
 * (several `LibraryManagerScreen` / `CategoryManagerScreen` sub-screens) must degrade to today's
 * behaviour rather than crash.
 *
 * Vertical only, and [reverseDirection] is `true` to match `Modifier.verticalScroll`: for a vertical
 * orientation `ScrollableDefaults.reverseDirection(…, reverseScrolling = false)` returns `true`, so
 * this is the same sign the bodies already scroll with — finger up moves content up. Pinned by
 * `DragToScrollTest`, because an inverted header would be worse than a dead one.
 */
fun Modifier.dragToScroll(state: ScrollableState?): Modifier =
    if (state == null) this
    else scrollable(state = state, orientation = Orientation.Vertical, reverseDirection = true)
