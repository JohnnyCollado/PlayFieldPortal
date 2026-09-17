# Code review — Game Detail screen redesign

**Status:** Review record, findings open
**Date:** 2026-09-16
**Branch:** `game-detail-screen-rework` (uncommitted work on top of `5071026`)
**Reviewed change set:** `git diff HEAD` — 1 295 insertions / 799 deletions across 5 tracked files, plus 11 untracked files
**Spec:** [`docs/plans/PFP_Game_Details_Redesign_Design.md`](../plans/PFP_Game_Details_Redesign_Design.md)
**Method:** two-axis review (Standards, Spec), `code-review` skill

---

## 1. Scope

Reviewed files:

| File | State |
| --- | --- |
| `feature/feature-xmb/.../ui/detail/GameDetailScreen.kt` | modified (922 lines changed) |
| `feature/feature-xmb/.../ui/detail/GameDetailViewModel.kt` | modified (679 lines changed) |
| `feature/feature-xmb/.../ui/detail/GameDetailNav.kt` | new |
| `feature/feature-xmb/.../ui/detail/DetailComponents.kt` | modified (148 lines removed) |
| `feature/feature-xmb/.../ui/app/AppDetailScreen.kt` | modified (186 lines changed) |
| `core/core-ui/.../core/ui/detail/*.kt` | new — `DetailScaffold`, `DetailHeroBanner`, `DetailActions`, `DetailRows`, `DetailMediaStrip`, `DetailGlyphs` |
| `feature/feature-xmb/src/test/.../GameDetailViewModelTest.kt` | modified |
| `feature/feature-xmb/src/test/.../GameDetailNavTest.kt` | new (22 tests) |
| `feature/feature-xmb/src/test/.../GameDetailHelperFooterTest.kt` | new (6 tests) |
| `feature/feature-xmb/src/test/.../GameDetailScreenContentTest.kt` | new (3 Robolectric tests) |
| `core/core-ui/src/test/.../DetailScaffoldLayoutTest.kt` | new (2 Robolectric tests) |

Standards sources used:

- `.agents/skills/android-kotlin-compose/SKILL.md` (active skill; composer contract, recomposition rules, banlist table)
- `.agents/skills/android-kotlin-architecture/SKILL.md` (MVI contract, container/content composables)
- `.agents/skills/android-kotlin-testing/SKILL.md` (fakes over mocks, Compose test semantics)
- In-repo documented conventions: `core/core-ui/src/main/kotlin/com/playfieldportal/core/ui/preview/PfpPreviewWrapper.kt` KDoc, `feature/feature-settings/.../SettingsScaffold.kt`

Baseline smells (Fowler, ch.3) were applied as judgement calls only, and any documented repo standard overrides them.

### Method caveats

- The reviewing agent had no sub-agent tool available in this environment, so the two axes were run sequentially rather than as two parallel sub-agents. The axes were still kept separate and neither was reranked against the other.
- The change set and this review were produced by the same agent. This is a self-review: the Standards findings are more reliable than the Spec findings, because the author is biased toward the design decisions being reviewed (the core-ui extraction, the `GameDetailNav` adapter). An independent pass on the Spec axis is worth doing before merge.
- There is no `docs/agents/issue-tracker.md` in this repo, so spec discovery went straight to the design doc the request named. Run `/setup-matt-pocock-skills` if issue-tracker-backed spec discovery is wanted.

---

## 2. Standards

### 2.1 Screen is not split into container/content *(documented-standard breach, inherited from the pre-redesign file)*

`PfpPreviewWrapper.kt` documents the required shape in the repo's own words:

> A composable that calls `hiltViewModel()` cannot be previewed directly — there is no Hilt graph in the preview/inspection environment, so it throws. The fix is **state hoisting**: split the screen into a thin stateful entry point that owns the ViewModel, and a stateless content composable that takes the `UiState` plus callback lambdas.

The architecture skill names the same split ("container/content composables"). `GameDetailScreen.kt` instead threads the concrete ViewModel through the whole tree:

```kotlin
GameDetailContent(state = state, game = game, …, viewModel = viewModel)
GameInformationBand(game, state, focusedKey, requesterFor, nodeY, viewModel)
```

Consequence in this very change set: the only way to render a page state in a test is to mock a concrete `@HiltViewModel`, which is what the new `GameDetailScreenContentTest` has to do (`mockk<GameDetailViewModel>(relaxed = true)`). `AppPickerScreen` — used by the existing `AppPickerThreeRowsTest` — is the in-repo counter-example: stateless, state plus callbacks, trivially renderable.

The pre-redesign `GameDetailScreen.kt` had the same shape, so this is inherited rather than introduced, but the file was rewritten wholesale and the split was available.

### 2.2 `collectAsState()` on Android *(banlist row 4 / checklist item 4 — pre-existing)*

```kotlin
// GameDetailScreen.kt:149
val state by viewModel.uiState.collectAsState()
```

Identical at `HEAD:131`, so not introduced here. Listed because the file was rewritten and the checklist is explicit (`collectAsStateWithLifecycle()` on Android).

### 2.3 Two sources of truth for "no Shiba Coins row on Android" *(judgement call)*

```kotlin
// GameDetailScreen.kt:467
if (game.platformId != "android") {
// GameDetailViewModel.kt:362
showCoins = loaded && game?.platformId != ANDROID_PLATFORM_ID,
```

The navigation graph's rule and the page's rule are separate literals that happen to agree. If one moves, the engine can offer the cursor a node the page never draws — the failure mode the file's own comments spend paragraphs warning about. One constant should serve both.

### 2.4 Key prefix re-typed instead of reused *(judgement call)*

```kotlin
// GameDetailScreen.kt:867
focus.startsWith("game-detail:disc:") -> "Choose disc"
```

The prefix is produced by `GameDetailKeys.disc()` (`GameDetailNav.kt:51`), and `GameDetailViewModel.kt:255` already keeps a private `DISC_KEY_PREFIX`. A `GameDetailKeys.isDisc(key)` predicate would remove the third copy.

### 2.5 Duplicated colour constants *(judgement call)*

```kotlin
// GameDetailScreen.kt:121
private val PlayGreen = Color(0xFF45C46A)
```

duplicates the newly public `DetailLaunchFill` in `core-ui/detail/DetailScaffold.kt`. Separately, `TextPrimary`/`TextMuted` are now triplicated across `GameDetailScreen.kt:119-120`, `AppDetailScreen.kt:92-93` and `DetailComponents.kt:24-25`, while core-ui exposes `DetailTextPrimary`/`DetailTextMuted`. The extraction was the moment to collapse these.

### 2.6 Dead adapter seams *(judgement call — Speculative Generality)*

`GameDetailNav.acceptsInput` (`GameDetailNav.kt:140`) and `GameDetailNav.setFocused` (`:360`) have no production caller anywhere in the repo (the `acceptsInput` hits under `feature-settings` belong to a different class). They mirror `ControllerNavigation`'s surface rather than this screen's needs.

### 2.7 Nav stack derived in two places, and one file changing for two reasons *(judgement call — Divergent Change)*

Three `init` collectors in `GameDetailViewModel` reconcile `nav` from state, and `handleGamepadAction` reconciles again before dispatching (`syncNavStack()` at the top of the function). The fourth reconciliation path exists because a state emission and a press could be ordered such that a modal's asynchronously loaded rows were registered after the press had already been dispatched.

Compounding it, `GameDetailViewModel.kt` is now 1 663 lines and owns both business logic and navigation-graph construction (`navContentOf`, `modalNodesFor`, `preferredModalFocus`, `syncNavStack`, `publishNav`).

### 2.8 Middle Man *(judgement call)*

```kotlin
// GameDetailNav.kt
private fun activate(key: String) { onActivate(key) }
```

A one-call wrapper; `onActivate` could be invoked directly.

### 2.9 Deliberately suppressed

- **Hardcoded UI strings.** 17 `Text("…")` literals before the change, 15 after, and **zero** `stringResource` calls in the whole `ui/detail` package. The banlist row loses to a repo-wide convention (a documented standard wins nothing here — there is no documented standard, but the established pattern is unambiguous).
- **`GameDetailUiState` lacking `@Immutable`.** Pre-existing; unchanged by the diff.
- **mockk-heavy interaction assertions.** The testing skill says "prefer fakes implementing real interfaces", but the repo's existing ViewModel tests use mockk throughout; only the *use* in a Compose test is new, and it is called out under 2.1 where it matters.

---

## 3. Spec

### 3.1 Touch does not hide the body's focus cursor *(§7, §10 — partial; worst finding on this axis)*

Spec §7: *"Touch input hides the visual cursor but does not destroy logical focus. The next controller input restores the cursor at the preserved logical node."* Spec §10: *"Touch hides the controller cursor."*

`state.cursorVisible` is consulted in exactly one place — the footer:

```kotlin
// GameDetailScreen.kt:339
visible = !showTouchControls && state.cursorVisible,
```

Every body row is passed focus unconditionally (`GameDetailScreen.kt:278 val focus = state.navFocusKey`, then `focused = focus == GameDetailKeys.COINS`, `… == GameDetailKeys.LAUNCH`, …), so after a touch the focus ring stays painted on the last logically focused node. The engine tracks the state correctly (`NavigationEngine.markTouchInput`), and the repo already ships the consumption pattern this spec describes:

```kotlin
// SettingsScaffold.kt:1063
if (isFocused && cursorVisible && !(hideRowHighlightOnActionFocus && anyActionFocused))
// SettingsSliderRow.kt:120
if (isFocused && cursorVisible) menuCursorFill()
```

delivered through `LocalSettingsCursorVisible`. The detail page body needs an equivalent gate (a shared `LocalDetailCursorVisible` in core-ui would let `DetailScaffold` provide it once for all detail pages).

### 3.2 Coins row has no distinct loading state *(§4.5 — partial)*

Spec §4.5: *"If achievement data is loading, preserve the row's geometry and use an appropriate loading state."*

Geometry is preserved, but the row renders `coins?.let { … } ?: "Not tracked yet"`, and `AchievementController.observeGameCoins(gameId): Flow<GameCoins?>` is nullable — a summary still loading is indistinguishable from a game that has no achievements at all. Loading and untracked need different copy.

### 3.3 Launch-recovery surface is implemented outside the page's context stack *(§9 — confirm intent)*

Spec §9 lists the "Launch-recovery surface" among the overlays that must own the active navigation context. The page's `requestLaunchHelp()` (`GameDetailViewModel.kt:1224`) calls `launchDispatcher.requestRecovery(...)`, and that request is collected by `XMBViewModel.kt:1444` — the recovery UI belongs to the shell, so it never pushes a modal context here. That looks deliberate; it deserves an explicit note in the code or a correction in the doc, because as written the spec implies a page-level context push that does not exist.

### 3.4 Verified as implemented

The following spec requirements were checked against the code (and, where relevant, against a passing test), so the findings above are the whole gap set:

- **§12 state migration complete** — no `mainFocus` / `discFocusIndex` / `mediaFocus` / `pageScrollSteps` remain in the detail package. (The surviving `mainFocus` hits are `VideoDetailViewModel`/`VideoDetailScreen`, a different screen outside this design.)
- **§8.1 stable keys** — the implemented keys match the design table verbatim, including `game-detail:disc:{gameId}` and `game-detail:media:{mediaStableId}`; `mediaStableId` is the asset identity (`v:`/`i:` + uri), never a list index.
- **§8.2 directional behaviour** — geometry-driven UP/DOWN, sibling LEFT/RIGHT, boundary stop with no wrap, nearest-neighbour entry into a band row, and input dropped (never queued) under the recovery lock.
- **§8.3 readiness** — initial focus `game-detail:launch`; input before readiness is ignored, not replayed; the missing-game dead end calls `onPageLaidOut()` so the engine can never be left permanently unready.
- **§8.4 scroll-to-focus** — a `BringIntoViewRequester` per page node, driven by the focused key, with the footer's height excluded by construction (it is a real layout row below the scrolling body).
- **§8.6 helper footer** — a permanent layout row whose reserved height survives hint fading; this is now measured, see `DetailScaffoldLayoutTest`.
- **§9 modal navigation** — every blocking overlay pushes its own context, the page graph is paused, Back closes the topmost overlay before the page, and closing restores the exact page node.
- **§4.8 media band** — absent (not empty) when a game has no media.
- **§4.3 / §4.7 package-backed entries** — neither the emulator quick action nor the emulator information field is rendered or reachable for a package-backed entry.

---

## 4. Verification at review time

```
./gradlew :feature:feature-xmb:testDebugUnitTest   # 499 tests, 2 failures
./gradlew :core:core-ui:testDebugUnitTest          #  98 tests, 0 failures
```

Both failures are in `ArtworkStudioViewModelTest` — `close cancels a suspended Change Match search` and `Forget Match is offered only once a match is confirmed, and clears it`. That class is untouched by this change set, both tests fail when the class is run in isolation, and the failing pair differs between runs (one of them fails on `Method decodeFile in android.graphics.BitmapFactory not mocked`), so they are pre-existing/flaky rather than fallout from this work. They should not be left as an excuse for a red suite.

---

## 5. Summary

| Axis | Findings | Worst |
| --- | --- | --- |
| Standards | 8 (1 inherited documented-standard breach, 1 pre-existing banlist row, 6 judgement calls) | Screen is not split into container/content, against the shape `PfpPreviewWrapper.kt` documents — which is why the new Compose test must mock a `@HiltViewModel` |
| Spec | 3 (2 partial, 1 confirm-intent) | Touch does not hide the body's focus ring (§7, §10) |

No single winner is picked across axes: the two are deliberately separate, and a change can pass one while failing the other.

---

## 6. Addendum — runtime crash on opening Game Detail (fixed after this review)

**Symptom.** Opening a game's detail page killed the process:

```
FATAL EXCEPTION: main
Process: com.playfieldportal.launcher.debug
java.lang.ClassCastException: java.lang.Float cannot be cast to kotlin.Unit
	at GameDetailScreenKt$GameDetailContent$3$1.invokeSuspend(GameDetailScreen.kt:321)
```

**Root cause.** The focus-driven scroll effect branched on `ScrollState.animateScrollTo`:

```kotlin
if (inTopBand) pageScrollState.animateScrollTo(0) else requester?.bringIntoView()
```

`ScrollState.animateScrollTo` is compiled as a **discarded** `ScrollExtensionsKt.animateScrollBy` — a suspend call whose continuation type is `Continuation<? super Float>` (it returns the consumed delta). It `areturn`s the `COROUTINE_SUSPENDED` sentinel straight through, so when that scroll suspends, the resumed value reaching the call site is a **Float**. In a branch, Kotlin materializes the call's Unit result as `checkcast kotlin.Unit`, and that cast is what throws.

**Evidence, in order:**

1. `javap -c` on the compiled effect lambda showed `checkcast kotlin/Unit` on the resume paths of the two calls inside the branch (offsets 316 and 432), and none on the plain-statement call after it (`animateScrollToItem`).
2. `javap -c` on `ScrollState.animateScrollTo` showed the leak directly: `invokestatic animateScrollBy(...); dup; getCOROUTINE_SUSPENDED; if_acmpne; areturn` — the caller's continuation is passed straight through to a Float-returning call.
3. A scratch Robolectric test rendering the real screen with the state-driving fake reproduced the exact exception (`ClassCastException: Float cannot be cast to kotlin.Unit`, `GameDetailContent$3$1.invokeSuspend`) on open with Launch focused, and again when focus returned to the top band.
4. A control probe proved the shape matters, not the API: `if (true)` (constant-folded) did **not** crash, a runtime condition did. Rewriting the branches as statement blocks did **not** help — the bytecode was byte-identical.

**Fix.** The page top became its own `BringIntoViewRequester` target, and the effect now makes exactly one call in statement position:

```kotlin
val pageTopRequester = remember { BringIntoViewRequester() }
…
(if (inTopBand) pageTopRequester else requester)?.bringIntoView()
```

with a 1dp anchor at the very top of the body carrying `Modifier.bringIntoViewRequester(pageTopRequester)`. Returning to the hero now travels the same path as bringing any other node into view, and no `animateScrollTo` call remains anywhere in the screen. This also keeps the hero reachable, which was the reason the top band had a special case at all.

**Regression test.** `feature/feature-xmb/src/test/.../GameDetailScrollTest.kt` renders the real screen: opening with Launch focused (the crash repro), plus a behavioural pass that scrolls to a media tile and asserts the top band returns to its exact unscrolled position when focus comes back to it.

**Re-verified after the fix.** `:feature:feature-xmb:testDebugUnitTest` 501 tests / 2 failures (both the pre-existing `ArtworkStudioViewModelTest` pair), `:core:core-ui:testDebugUnitTest` 98 / 0.

**Related, checked and safe.** The same API appears at `MetadataPreviewPanel` (metadata overlay), `EmulatorPickerPanel`, and the options-row scroll in `GameDetailScreen` — their call sites are statements or single-expression lambdas with no Unit materialization on the resume path (verified by `javap`), so they do not carry this bug. `PlayerStatusScreen` materializes a Unit for `bringIntoView`, which returns a genuine Unit and is therefore safe.
