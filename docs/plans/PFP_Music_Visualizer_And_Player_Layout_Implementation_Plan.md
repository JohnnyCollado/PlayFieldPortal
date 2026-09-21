# Play Field Portal — Music Visualizer & Player Layout: Implementation Plan

Follow-on to `PFP_Music_Player_And_Pickers_Implementation_Plan.md`, which brought the Music
surfaces to touch/controller parity. That pass left the player's centre occupied by a large album
tile. This one gives the centre to a visualizer, rebuilds the chrome around it in the PSP Visual
Player's language, and adds a live picker to choose between three fields.

**No audio analysis, no engine migration.** See §3 — every visualizer here is time-driven, and
the seam is shaped so a real signal can replace the synthetic one later without touching a single
renderer. `MusicPlayerController` keeps its `MediaPlayer`.

---

## 1. What the reference actually does

From the PSP Visual Player (`Underwater Bubbles`, t=0:33 — the only frame where all chrome is up):

| Element | Placement |
|---|---|
| Tinted banner, full width | `♫` + playlist name left · `(4/28)` right |
| Album art | ~95px thumbnail, top-left — **not** the hero |
| Title | large, with a **hairline rule running the full width beneath it** |
| Artist + codec chip | under the rule / right end of the rule |
| Centre of screen | **empty** — the visualizer owns it |
| Transport | transient glyph cluster, centred, fades with the chrome |
| Play state | large standalone `▶` / `❚❚`, bottom-left, last to fade |
| Time | elapsed in **cyan** + total in white, above a bar spanning the **right half only** |
| Visualizer picker | bottom filmstrip, `OFF` first, box around the selected tile |
| After ~5s idle | **all chrome gone** — 0:42 to 6:34 is pure field |

The inversion is the point: art is a thumbnail, the field is the hero. Our current player does the
opposite.

---

## 2. The three fields

Each is a pure renderer (§3), not a composable, and each ships at two budgets — hero and tile.

### 2.1 Portal

The house visualizer, and the one the app is named for. Circles orbit a centre and spiral
outward, brightening at the core and dissolving as they travel, so the field reads as a mouth
opening away from the viewer.

- Each particle holds `angle`, `radius ∈ [0,1]`, `phase`. Radius advances per frame and wraps to 0.
- `alpha = (1 - radius)²`, `size = lerp(2dp, 9dp, radius)` — small and bright at the throat, large
  and faint at the rim.
- Angular velocity is constant plus an energy term, so the whole ring *turns* fractionally faster
  on a swell rather than jumping.
- Particles seed on 5 rings with a golden-angle offset per ring, which is what stops it reading as
  concentric stripes.
- Alpha fades *out* toward the rim, so particles dissolve into the backdrop rather than hardening
  against it — as built in the approved mockup. The inverse is one line on the alpha curve if it
  ever needs to flip.
- **Decay:** energy scales both the spiral rate and per-particle alpha, so a paused field slows and
  dims to a faint, almost-still throat rather than stopping dead.

### 2.2 Ripple

The water surface. Concentric rings expand from one or two drop points, thinning and fading as
they grow.

- A ring is `(originX, originY, birthTime, birthEnergy)`. Radius is a function of age; stroke alpha
  and width both decay with radius.
- A new ring is emitted on an energy threshold crossing with a refractory period, so swells produce
  a drop rather than a continuous smear.
- Ring count is the budget: hero ~14 live rings, tile ~5.
- Origins drift slowly so it never looks like a fixed sprinkler.
- **Timing — slower than the mockup.** The approved mockup ran a 2.6s ring life and read as
  agitated rather than as water. Ship it at a **~4.2s** life to the same peak radius (0.75 × the
  larger screen dimension), i.e. expansion about 40% slower, with the emission interval stretched
  to match: **~900ms at rest falling to ~420ms on a swell**. Both numbers move together on purpose —
  longer-lived rings at the old emission rate would just crowd the surface and undo the calm. Ring
  budgets are unchanged, because concurrency is life ÷ interval and both scaled by roughly the same
  factor.
- **Decay — the water goes still.** Emission is gated on `energy > EMIT_FLOOR`, so as the envelope
  decays on pause the drops simply stop. Rings already in flight finish expanding and fade out on
  their own, and the surface settles to nothing over roughly one ring lifetime.

  This is the behavioural twin of Portal's decay and it matters for the same reason: a paused
  visualizer that keeps emitting is the single clearest tell that nothing is listening. A ripple
  field that stops being disturbed and then flattens is exactly what a real one does.

### 2.3 Off — wallpaper / wave

Not black. The player's backdrop drops to a translucent scrim so the **live XMB wave and the
user's wallpaper read through it**, exactly as they do behind the Settings screens.

- Costs nothing: no clock, no particles, no draw. It is the absence of a renderer, not a renderer.
- The scrim alpha is the only knob, and it has to serve two masters — the wave must be legible,
  and the title over it must stay readable. Start at the value the settings scrim uses and tune
  against a bright wallpaper, not a dark one.
- **This is the default.** Nobody who never opens the picker pays for any of the rest.

---

## 3. Architecture

### 3.1 The seam, defined now for a signal that does not exist yet

```kotlin
data class VisualizerFrame(
    val timeNanos: Long,
    val energy: Float,          // 0..1 — synthetic today, RMS later
    val bands: FloatArray?,     // null until there is a real FFT
)

interface PfpVisualizer {
    val id: String
    val label: String
    fun DrawScope.render(frame: VisualizerFrame, budget: Int, sprite: ImageBitmap)
}
```

Today `energy` comes from §3.2 and `bands` is null. If the ExoPlayer migration ever lands,
`energy` becomes RMS off a `TeeAudioProcessor` and `bands` fills in — **and no renderer changes.**
Bar and waveform styles are deliberately excluded from this plan: a spectrum uncorrelated with the
audio reads as a lie within seconds, because the eye treats bars as a measurement.

### 3.2 Synthetic energy

Three sines at incommensurate periods (1.7s / 2.9s / 4.3s), summed and normalised, with the phase
seeded from the track id so each song has its own character, consistently, every play.

The one behaviour that carries the illusion: **on pause, energy decays to a floor over ~600ms
rather than freezing.** That is how people test a visualizer.

Decay is a contract on the *energy source*, not on any one renderer — which is what makes it
uniform for free. Every field reads the same falling number and each settles in its own idiom:
Portal slows and dims, Ripple stops being disturbed and flattens. A renderer that ignores `energy`
would sail on through a pause, so **every renderer must consume it in at least one term**, and that
is a review check on T3 and T4, not a suggestion.

### 3.3 One clock, one array, N draws

The rule that makes a live picker affordable — nine live previews are not nine visualizers, they
are one simulation drawn nine times:

- **One `withFrameNanos` loop**, owned by the player screen. Not `rememberInfiniteTransition`, and
  not one per tile — that would be nine animation subscriptions and nine recomposition scopes.
- **One particle array**, advanced once per frame in the holder. Each tile draws the *first N*
  entries scaled into its own bounds, so simulation cost is O(1) in tile count; only draws scale.
- **State is read in the draw phase.** The frame holder is read *inside* the `Canvas` lambda, so a
  frame invalidates draw only. Read it in a composable body instead and the whole player
  recomposes at 60Hz.
- **One sprite.** A soft-circle `ImageBitmap` pre-rendered once per theme and `drawImage`d — not
  N radial-gradient `drawCircle` calls.

### 3.4 Budget

| | Hero, strip closed | Hero, strip open | Tile |
|---|---|---|---|
| Portal | 260 | 140 | 22 |
| Ripple | 14 rings | 8 rings | 5 rings |

The hero drops when the strip opens, so total draw count stays roughly flat across the one
transition where jank would be most visible. A single `budgetScale` multiplier on the holder turns
everything down at once for a low-end device or a thermal signal, without touching a renderer.

The clock stops entirely when: the player is closed, the app is not resumed
(`LifecycleResumeEffect`), or `Off` is selected.

---

## 4. The picker strip

Bottom filmstrip, `Off` first, then Portal and Ripple. Selected tile carries the menu cursor's
border, matching every other list in the app.

- Opens from the player's Options menu (**Y ▸ Visualizer**) and by tapping the strip affordance.
- Auto-hides with the rest of the chrome; hidden tiles stop drawing.
- **Input conflict (§8 Q2):** ◀/▶ are seek in the player as of the last pass. While the strip is
  open it must capture them for tile navigation — A confirms, B closes the strip and hands seek
  back — the same capture pattern the context menu already uses in `XMBViewModel`.
- Touch taps a tile directly; the focused tile frames itself with `BringIntoViewRequester` in a
  `LazyRow`, per the repo convention.
- Selection persists to the existing music DataStore prefs. Default `Off`.

---

## 5. Player layout rework

Rebuilt on §1, replacing the centred-album-tile layout:

1. **Banner** — full-width tinted band: `♫` + queue/playlist name left, `(2 / 14)` right, Back pill
   and kebab at the outer ends in touch mode.
2. **Metadata** — 62dp art thumbnail left; title with a full-width hairline beneath; artist, album
   and a codec chip on the rule line.
3. **Centre** — the field. Nothing else.
4. **Transport cluster** — centred, transient, touch only (a pad has the ladder and the prompt bar
   names it).
5. **Bottom-left** — large play-state glyph, persistent.
6. **Bottom-right** — elapsed in accent-cyan over total, above a bar spanning the right ~45%.
7. **Auto-hide** after `CONTROLS_TIMEOUT_MS` (3.5s, shared with the video player), any input
   restores it. Auto-hide is gated on a field being selected — with `Off`, hiding everything
   would leave a bare wallpaper with no way back except a blind tap.

`MediaScrubBar` from the last pass still does the scrubbing; only its placement and the time
treatment change.

---

## 6. Execution Task Index

Handoff-grade: each task is self-contained, names its files, and stops where it stops. Order is
T1 → T2 → (T3 ∥ T4) → T5 → T6 → T7. T5 may start once T2 lands.

### Shared contracts — do not rename these

So separate helpers cannot invent divergent names for the same thing:

| Thing | Exact name |
|---|---|
| Package | `com.playfieldportal.feature.xmb.ui.visualizer` |
| Frame data | `VisualizerFrame(timeNanos: Long, energy: Float, bands: FloatArray?)` |
| Renderer | `PfpVisualizer` — `id`, `label`, `DrawScope.render(frame, budget, sprite)` |
| Ids | `"off"`, `"portal"`, `"ripple"` |
| Holder | `VisualizerHost` |
| Preference key | `stringPreferencesKey("music_visualizer_id")`, default `"off"` |
| Seek step | `MUSIC_SEEK_STEP_MS` (exists, `XMBViewModel`) |
| Chrome timeout | 3.5s, the video player's `CONTROLS_TIMEOUT_MS` value |

### T1 — Seam, synthetic energy, sprite

- **Files:** new `ui/visualizer/PfpVisualizer.kt`, `ui/visualizer/VisualizerEnergy.kt`,
  `ui/visualizer/VisualizerSprite.kt`; new test `VisualizerEnergyTest`.
- **Build:** the three contracts above; the pure energy function (three incommensurate sines,
  track-id seeding, pause decay to floor); the soft-circle `ImageBitmap` builder, cached per theme.
- **Stop when:** energy is unit tested for range clamping, seed determinism, and monotonic decay to
  the floor on pause. No renderer, no UI, nothing wired.
- **Budget:** ~3 files, ~180 lines.

### T2 — Frame holder and the single clock

- **Files:** new `ui/visualizer/VisualizerHost.kt`.
- **Build:** one `withFrameNanos` loop; one particle array advanced once per frame; `budgetScale`;
  lifecycle gating (`LifecycleResumeEffect`); clock fully stopped when the selection is `off`.
- **Stop when:** a scratch screen drawing the holder shows **zero recompositions per frame** —
  verify with a recomposition counter, this is the whole point of the task. Delete the scratch
  screen before handing back.
- **Do not:** touch the player layout or build the strip.
- **Budget:** 1 file, ~150 lines.

### T3 — Portal renderer

- **Files:** new `ui/visualizer/PortalVisualizer.kt`.
- **Stop when:** it reads as a portal at **budget 22**, not only at 260 — check the tile size first,
  the hero flatters everything. Energy must affect both spiral rate and alpha (§3.2).
- **Budget:** 1 file, ~90 lines.

### T4 — Ripple renderer

- **Files:** new `ui/visualizer/RippleVisualizer.kt`.
- **Stop when:** rings emit on swells rather than continuously, **and** emission stops as energy
  falls to the floor, with in-flight rings finishing and the surface settling (§2.2).
- **Timing is specified, not a free parameter** — ~4.2s ring life, ~900ms→420ms emission interval
  (§2.2). The faster version was reviewed and rejected for reading as agitation rather than water.
- **Budget:** 1 file, ~90 lines.

### T5 — Player layout rework

- **Files:** `ui/MusicPlayerScreen.kt` (substantial rewrite), `ui/XMBShell.kt` (call site),
  `viewmodel/XMBViewModel.kt` (chrome-visibility state).
- **Build:** §5 — banner, thumbnail + hairline metadata, empty centre, transient cluster,
  bottom-left state glyph, bottom-right cyan time over a right-half bar, auto-hide.
- **Stop when:** chrome auto-hides after 3.5s with a field selected and **stays up** under `off`.
- **Do not:** touch `MusicBrowserScreen` or `MusicTrackPicker`. They are finished.
- **Budget:** ~3 files, ~300 lines net.

### T6 — Picker strip, input capture, persistence

- **Files:** new `ui/visualizer/VisualizerPickerStrip.kt`; `viewmodel/XMBViewModel.kt`;
  `core-data` music repository + `core-domain` repository interface for the new preference.
- **Build:** §4 — `LazyRow` of live tiles, `BringIntoViewRequester` focus, ◀/▶ capture while open,
  DataStore persistence, Options ▸ Visualizer entry point.
- **Stop when:** ◀/▶ seek again the moment the strip closes. That regression is the one this task
  is most likely to ship.
- **Budget:** ~5 files, ~260 lines.

### T7 — Docs, changelog, previews

- **Files:** `ARCHITECTURE.md`, `CHANGELOG.md`, `@CombinedPreviews` for the player.
- **Stop when:** the player has previews in all three field states, and `ARCHITECTURE.md` carries
  the one-clock/one-array rule from §3.3 — it is the rule a future contributor is most likely to
  break by adding a second animation.

```bash
./gradlew :feature:feature-xmb:testDebugUnitTest
```

## 7. What is explicitly out

- Any audio tap, permission, FFT, or RMS.
- The ExoPlayer migration (`MusicPlayerController` keeps `MediaPlayer`).
- Bar-spectrum and waveform visualizers — they need a real signal (§3.1).
- The browser and the track picker. They are finished.

---

## 8. Decisions

1. **Portal alpha fades out toward the rim** — as built in the approved mockup.
2. **The strip captures ◀/▶ while open**, the same way the context menu captures input in
   `XMBViewModel`, and returns them to seek on close. Cheap to flip to ▲/▼ if it feels wrong on a
   real pad.
3. **The banner tint follows the theme accent, not the album art.** Art-derived sampling is closer
   to the PSP, which tints from the field itself, but it needs a palette pass on every track change
   for a band a few pixels tall. Revisit only if the accent version looks dead.

Items 2 and 3 were my calls rather than the author's; either reverses in well under an hour if the
built version disagrees with the intent.
