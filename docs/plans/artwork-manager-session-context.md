# Session context: Artwork Manager hardening (C16), Merge 5

Written 2026-09-15 at the end of an implementation session. Branch `artwork-revisions`.
Replaces the previous Merge 4 context file, which had said to delete it once Merge 4 landed.

**This file is a pointer, not a second record.** The plan
[`artwork-manager-hardening-plan.md`](artwork-manager-hardening-plan.md) is the record, and its
Execution Task Index is the status. If this file and the plan disagree, the plan wins; if the plan
and the repository disagree, the repository wins (`PLANNING_WORKFLOW.md` §6, §12). Delete this file
once Merge 5 lands.

---

## ⚠ Read this first if you are moving machines

Two things do **not** travel on their own:

1. **The work is staged, not committed.** See "Repository state" below. Commit and push from this
   machine before picking up elsewhere, or the 6.1 implementation and both new specs will not be
   there. The user commits through GitHub Desktop and decides when — a suggested commit message is
   in the session transcript and can be regenerated from the diff.
2. **The governing policy document is now in the repo** at
   [`../PFP_Artwork_Dimensions_and_Aspect_Ratio_Policy.md`](../PFP_Artwork_Dimensions_and_Aspect_Ratio_Policy.md)
   (copied in 2026-09-15 from the user's Downloads, where it was originally supplied). Tasks 6.2 and
   6.5 both cite it. It travels with a push like anything else. Note the plan still has one dangling
   reference of the older kind, to `PFP_Artwork_Manager_Hardening_Design.md`, which is absent from
   the repo.

Machine-specific paths in `CLAUDE.md` and in the notes below (the adb path, `D:\NEXTJJEN\repos\`)
are for this PC and will differ elsewhere.

---

## Repository state

HEAD is `9bced7d` ("Add single-art duplicate detection and a stored-artwork reorder panel to the
Artwork Studio") — that commit carries tasks 5.3 and 5.4, so **Merge 4 is complete and committed**.

Staged and uncommitted on top of it:

| File | |
|---|---|
| `feature/feature-artwork/.../store/CropProfiles.kt` | new — the 6.1 registry |
| `feature/feature-artwork/.../store/CropProfilesTest.kt` | new — 7 tests, all green |
| `feature/feature-xmb/.../detail/ArtworkStudioViewModel.kt` | `cropTargetAspect` deleted, resolves through the registry |
| `docs/plans/artwork-manager-hardening-plan.md` | 6.1 marked DONE; 6.2 and 6.5 specs added; index rows updated |
| `docs/PFP_Artwork_Dimensions_and_Aspect_Ratio_Policy.md` | new — the supplied policy, copied into the repo so 6.2/6.5 references resolve |
| `docs/plans/artwork-manager-session-context.md` | this file |

## What landed this session

**Task 6.1 — the crop profile registry. Implemented, tests green, device check outstanding.**

- `CropProfileRegistry.resolve(kind, platformId, region)` returns a `CropProfile(key, aspect: Float?)`,
  resolving kind+platform+region → kind+platform → kind → Original Image. A **null** aspect means
  Original Image; keys are stable and parseable (`"ICON:psx:NTSC_U"`, `"ICON:psx"`, `"ICON"`,
  `"original"`) because 6.3 persists them.
- Pure Kotlin in `:feature:feature-artwork`, one import (`GameRegion`), so it tests without
  Robolectric.
- The shipped table is **kind defaults only** — ICON/ICON1 144:80, HERO 920:430, BACKGROUND 16:9,
  everything else Original Image. The platform and region tiers are built and tested against an
  injected table but ship empty. **6.1 therefore changes no pixels**, which is the intended outcome.
- `recomputeCropRect`'s window arithmetic is byte-for-byte unchanged; only the two lines that obtain
  `target` differ.
- Verified: the four ratios are the same float expressions lifted out of the deleted function, not
  retyped decimals. Zero remaining references to `cropTargetAspect`.

Tests run and green:
`./gradlew :feature:feature-artwork:testDebugUnitTest --tests "com.playfieldportal.feature.artwork.store.CropProfilesTest"`

**Tasks 6.2 and 6.5 — specced, not implemented.** Both are written into the plan under
"Merge 5: crop profiles" in the same shape as every other task spec, and both are ready to hand to a
helper as-is.

## Next actions, in order

1. **Commit and push the staged work** (user's call, GitHub Desktop).
2. **Task 6.2** — live final-result preview in the crop editor. Specced, no open questions.
3. **Task 6.5** — centralize the artwork dimension policy. Specced, independent of 6.1/6.2.
4. **Device check** for 5.3, 5.4 and 6.1 — see below.
5. `6.3` and `6.4` are index rows only, not yet specced. `6.4` also waits on `3.2`/`6.2`.
   `4.3`/`4.4` wait on plan C17; `7.1` is blocked on B2.

## Grounding already done — do not re-derive

**For 6.2:**

- `StudioCropEditor` is at `ArtworkStudioScreen.kt:1432`, rendered from `state.cropEditorPath` at
  `:1326`. It is layered, not stacked: layer 1 is the `Canvas` (image + dim mask + frame stroke),
  layer 2 floats title/buttons/hints. The preview inset goes on layer 2.
- **The bitmap is already decoded in the composable** — `bmp`, one `decodeDisplayBitmap(path)` per
  open at `:1443`, downsampled to ≤1600px. `cropL/T/R/B` and `srcW/srcH` are already parameters.
  So the preview needs no second decode, no new `ArtworkStudioUiState` field and no ViewModel
  change. This is the whole reason 6.2 is small.
- `frameSizeFor` (`:1553`) already derives the crop window's on-screen aspect. The inset uses that
  same number — never a number re-derived from the registry.
- `GameIconView.kt` has exactly **two** tile treatments, not four: `PspIcon0Icon` (`:287`, framed PSP
  chrome — 4.dp rounded clip, `0xFF0A0A0F` backing, 1.dp `0x55FFFFFF` border, top gloss) and
  `NaturalAspectArtIcon` (`:229`, shrink-wrap + `ContentScale.Fit`), the latter **framed only when
  the uri is the box-art uri** (`:132`), because 3D boxes and physical media are transparent
  silhouettes.

**For 6.5:**

- `boxArtAspectFor` (`GameIconView.kt:176`) is the **only** artwork ratio table in the repo, with
  exactly **one** call site — the placeholder at `:204`. Nothing is duplicated; the table is simply
  in the wrong module and incomplete.
- Against the supplied policy it has three defects: `psvita` shares PSP's 0.59 (policy: ~0.78, and
  the policy names this correction explicitly); ~20 listed platforms are absent and fall through;
  the generic branch is 0.70 where the policy specifies 0.72.
- The tree carries alias keys the policy's canonical-id table does not (`ps1`, `sfc`, `dc`, `nx`,
  `ds`, `3ds`). Dropping them would silently re-shape those platforms' placeholders.

## Decisions taken this session

1. **The 6.2 preview is a fixed corner inset on layer 2** (user choice). A side rail was rejected
   because it moves the crop frame's centring, which drags in the gesture maths.
2. **3D Box is included in 6.2** (user choice), alongside ICON0, box art and physical media, even
   though the index row named only three — `BOX_3D` and `PHYSICAL_MEDIA` share one treatment, so it
   is one map row and no extra rendering code.
3. **The policy's box-art platform table is placeholder data, not crop-registry data.** This is the
   important one. The policy's rule is *source dimensions beat platform preset*; 6.1's registry
   resolves *platform beats kind default*, overriding the source. They reconcile only because the
   policy splits artwork into fixed-crop types (ICON0/Hero/Background — exactly 6.1's four rows) and
   source-preferred types (box art and the contain-only kinds). In the crop editor source dimensions
   are **always** known, so a box-art platform row in the crop registry could only override a ratio
   the policy says must win. **6.1's platform tier stays empty for box art**, now as an argued
   position rather than a holding pattern, and the policy's table goes to `boxArtAspectFor` instead.

## Device check debt

Never done for 5.3, 5.4 or 6.1. One pass through the Artwork Studio covers all three:

- **5.3** — open a single-art tab (ICON0, BOX ART, HERO, LOGO…) and apply art the slot already
  holds; the Replace Anyway / Cancel prompt should open. Note the shipped prompt is **two** rows, not
  the specced three — the View Existing row was cut by user decision (the candidate preview is
  already on screen by then).
- **5.4** — open the stored-assets manager on a game with several screenshots: D-pad moves the
  cursor, LB/RB move the asset, A makes it primary, B closes, and touch gets pills plus tap-to-focus.
- **6.1** — open the crop editor on ICON0 and on a kind with no default; framing must be identical
  to before, since 6.1 changes no pixels.

## Still unconsumed — do not assume any of these is wired up

`ArtworkRecordDao.findByProviderAssetId`, `findByOriginUrl`, `findByChecksum`,
`ArtworkRecordEntity.cropProfileKey` (6.3 is the task that writes it; 6.1 deliberately does not).
`RoutingArtworkStore.reorderAssets` is consumed as of 5.4.

## Working constraints

- **Never run a Gradle build unless asked.** The user runs them and pastes results.
- Don't run `./gradlew --stop` — it kills the daemon the user's terminal is using.
- Don't drive the device. Ask the user to navigate/reproduce; screenshots only once they say ready.
- **No commits without approval.** Stage and propose; the user triggers every commit via GitHub
  Desktop.
- The user's Android Studio checkout may be a different worktree — check `git worktree list`.
- Stale red in Android Studio is not real; the Gradle CLI is the truth.
- Test task is `testDebugUnitTest` — `:feature:feature-xmb` and `:feature:feature-artwork` are plain
  libraries with no flavors. (`fullDebug` is the **app** module's flavor.)
- Methodology: one bounded task per helper, `PLANNING_WORKFLOW.md` §4 budget (2–4 existing files,
  1–2 new, 1 test file, no new deps without approval). Plan wins over notes; repo wins over plan.
