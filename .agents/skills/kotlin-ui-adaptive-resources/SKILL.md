---
name: kotlin-ui-adaptive-resources
description: Use when designing, implementing, or reviewing adaptive Compose UI for KMP or Android projects — window-size layouts, adaptive navigation, canonical layouts, multi-window support, and resource strategy.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.1.0"
---

# Adaptive UI and Resource Strategy

Use this skill when designing, implementing, or reviewing adaptive UI in a Compose-based project.

This skill is intentionally strict. Its purpose is to keep layouts resilient across display sizes, window sizes, orientations, fold states, and resizable environments while preserving clear UI architecture and avoiding fragile one-device assumptions.

## Primary goals

The adaptive strategy should optimize for:

- support for a wide range of display and window sizes
- structured layout changes rather than ad hoc breakpoints
- navigation chrome that adapts with available space
- correct use of canonical multi-pane layouts where appropriate
- resilience in split-screen, freeform, and desktop-style windowing
- resource and presentation decisions that stay configurable
- Compose layouts that remain understandable under adaptation pressure
- previewability and testability across adaptive states

Do not treat “works on my phone” as sufficient.
Adaptive UI should remain coherent when the window changes shape, size, or posture.

---

## Official defaults to prefer

Unless the project has a strong reason not to, prefer:

- responsive and adaptive UI that supports a wide range of screens and app window sizes
- window size classes as the primary high-level breakpoint system
- canonical layouts such as list-detail, feed, and supporting-pane when they fit the use case
- adaptive navigation patterns that switch chrome based on available space
- layouts that remain usable in multi-window mode and desktop-style windowing
- resource and presentation values that stay configurable rather than hard-coded
- Compose layouts whose modifier chains and measurement behavior remain explainable

---

## Review dimensions

### 1. Adaptive mindset and target environments

Check whether the design explicitly supports:

- phones
- tablets
- foldables
- ChromeOS / large-screen environments
- portrait and landscape
- resizable windows
- split-screen mode
- desktop windowing / freeform windows where applicable

Flag as a concern when:
- the design assumes one full-screen phone window shape
- large-screen behavior is just stretched phone UI
- resizable environments are ignored
- adaptation is deferred until after feature implementation

### 2. Window size class strategy

Window size classes are the default high-level tool for adaptive decisions. In Compose, `WindowWidthSizeClass` and `WindowHeightSizeClass` (from the `androidx.compose.material3.adaptive` or `androidx.window` artifact) classify the current window into compact, medium, or expanded buckets. `rememberWindowAdaptiveInfo()` (from `androidx.compose.material3.adaptive`) is the current recommended API for querying the full adaptive context including window size and posture.

Check whether:
- major layout decisions are driven by window size classes rather than raw dp breakpoints
- `rememberWindowAdaptiveInfo()` or equivalent structured APIs are used at the root shell level
- breakpoints are not reinvented casually without reason
- navigation chrome and pane layout decisions are tied to structured size input
- fine-grained layout work sits underneath, rather than replacing, size-class strategy

Flag as a concern when:
- arbitrary pixel/dp thresholds replace standard size-class reasoning
- different parts of the app use incompatible breakpoint logic
- layout changes are too ad hoc to scale consistently
- window-size queries are scattered across many composables instead of hoisted to the shell

### 3. Navigation adaptation

Adaptive navigation should change with available space.

Check whether:
- bottom navigation, navigation rail, drawer, or other shell chrome are chosen intentionally based on window size
- current destination tracking remains stable while the chrome changes
- navigation adaptation is a shell concern rather than duplicated in every feature

Flag as a concern when:
- phone navigation chrome is forced onto large layouts without reason
- large-screen navigation is bolted on with special cases
- shell adaptation and route state are tightly tangled

### 4. Canonical layout choice

Android’s canonical layouts are proven patterns for common use cases.

Check whether the design should use:
- list-detail
- supporting pane
- feed-like or other canonical structures

Prefer canonical layouts when the product problem matches them.

Flag as a concern when:
- bespoke layouts re-solve a standard list-detail or supporting-pane problem poorly
- large-screen UI is over-customized without product value
- canonical multi-pane opportunities are missed

### 5. List-detail behavior

For master-detail style problems, review whether list-detail behavior is properly adaptive.

Check whether:
- compact windows can show one pane at a time
- larger windows can present list and detail simultaneously
- selection state and detail navigation remain coherent across size changes
- back behavior still makes sense when moving between single-pane and multi-pane presentations

Flag as a concern when:
- list-detail patterns are modeled as unrelated screens with no shared selection state
- expanding to large layouts creates duplicated or conflicting detail logic
- detail presentation is not resilient to resizing

### 6. Supporting-pane behavior

Supporting-pane layouts should be used when a secondary pane adds context or tools rather than acting like a full peer destination.

Check whether:
- the supporting pane has a clear role
- supporting content collapses gracefully when space is constrained
- pane visibility and priority rules are explicit

Flag as a concern when:
- supporting-pane content becomes a permanent cluttered sidebar
- collapse/expand behavior is implicit and fragile
- pane ownership is unclear

### 7. Multi-window and resizable-window support

Multi-window mode means the app may run side-by-side, stacked, or in a resizable freeform window.

Check whether:
- the app remains functional in smaller-than-expected windows
- layout assumptions are based on current window bounds, not only device type
- state and layout respond correctly to window resizing
- split-screen and desktop windowing are considered for important flows

Flag as a concern when:
- device category is used as a proxy for actual available space
- resizing breaks layout hierarchy or interaction patterns
- important content becomes inaccessible in constrained windows

### 8. Orientation, aspect ratio, and resizability assumptions

Orientation locks and aspect-ratio assumptions are increasingly fragile on large/resizable devices. Android 16 was announced to further reduce the effect of several of these restrictions on large screens for apps targeting API 36 (verify against the current Android 16 compatibility documentation, as this behavior was in development as of mid-2025).

Android 16 (API 36) is expected to further reduce the effect of orientation lock and aspect-ratio restriction APIs on large screens for apps targeting API 36. This behavior was announced prior to Android 16's release — verify its current status in the Android 16 release notes or behavior changes documentation.

Check whether:
- the UI can adapt rather than relying on orientation locks
- layout logic depends on current window size and structure rather than a fixed aspect ratio assumption
- resizability is treated as normal rather than exceptional

Flag as a concern when:
- layout depends on portrait-only or landscape-only assumptions
- the app relies on fixed aspect-ratio expectations that may no longer be honored on newer OS versions
- resizing support is effectively disabled in architecture rather than handled in UI
- orientation/aspect-ratio restrictions are used as a substitute for proper adaptive layout design

### 9. Adaptive do’s and don’ts

Use adaptive design principles consistently.

Prefer:
- current-window reasoning instead of device stereotypes
- scalable layouts instead of stretched single-column phone UI
- pane/chrome changes that preserve task flow
- explicit adaptation strategy for important user journeys

Avoid:
- hard-coded one-device assumptions
- content that becomes too sparse or too crowded on larger windows
- duplicated flows created only to support one size class
- adaptation that changes too much without preserving user mental model

Flag as a concern when:
- adaptation is visually inconsistent across screens
- users must relearn flows purely because the window got larger
- the UI wastes large-screen space or overloads compact screens

### 10. Layout structure quality

Adaptive UI still depends on good Compose layout structure.

Check whether:
- layout trees are understandable
- rows, columns, boxes, lazy containers, scaffolds, and panes are used clearly
- the shell is decomposed into meaningful layout responsibilities
- layout adaptation does not turn root composables into giant conditional trees

Flag as a concern when:
- one giant composable owns all adaptive branches inline
- layout structure is too tangled to reason about
- adaptation logic is duplicated across many screens

### 11. Modifier discipline

Modifier order and composition affect layout and behavior.

Check whether:
- modifier chains remain readable
- modifier order is intentional
- adaptive behavior is not hidden inside long opaque modifier chains
- size, padding, offset, click, visibility, and scroll behavior are composed in a way that is understandable

Flag as a concern when:
- modifier order creates accidental layout behavior
- the layout can only be understood by trial and error
- adaptive rules are buried in long modifier chains

### 12. Intrinsic measurements

Compose normally measures children once; intrinsic measurements are for cases where a parent layout needs child size information before normal measurement. In adaptive UI, this pattern can appear when a pane needs to size itself relative to sibling content — but it is often a sign that the layout structure needs rethinking rather than a special measurement pass.

Check whether:
- intrinsic measurements are used only when justified by a real layout requirement
- they solve a specific adaptive or measurement problem that has no cleaner structural solution
- they do not become a default fix for unclear layout design

Flag as a concern when:
- intrinsic sizing is scattered casually through adaptive layout code
- the layout relies on intrinsics where a clearer pane/scaffold structure would be better
- intrinsic behavior is used without understanding the measurement-pass cost

### 13. Alignment lines

Alignment lines let parent layouts align children by semantically meaningful baselines rather than raw bounds — for example, aligning a label's first text baseline to another element's text baseline across different adaptive layouts. This is relevant in adaptive UI when two panes or panels need to visually align even as their internal content structure differs.

Check whether:
- alignment lines are used for real cross-composable alignment requirements in adaptive layout contexts
- custom alignment behavior is documented enough to be understood by future contributors
- they support reusable layout polish rather than clever but obscure tricks

Flag as a concern when:
- alignment lines are used to patch unclear layout structure instead of fixing the structure
- custom alignment contracts are hidden and fragile
- maintainers cannot explain the alignment requirement that motivated the line

### 14. Visibility and on-screen behavior

Visibility tracking modifiers can support analytics, autoplay/pause behavior, and state changes based on whether content is actually visible.

Check whether:
- visibility tracking is used for clear product or performance reasons
- analytics and resource-management behavior are tied to meaningful visibility semantics
- visibility callbacks do not create accidental recomposition or side-effect issues

Flag as a concern when:
- visibility modifiers are added everywhere without purpose
- side effects triggered by visibility are unstable or repetitive
- resource control depends on poorly defined visibility assumptions

### 15. Resource and presentation strategy

Adaptive UI should keep presentation values configurable.

Check whether:
- strings, dimensions, icons, and other presentation concerns are not buried inside business logic
- window-size-driven presentation differences stay in UI/presentation layers
- resource decisions are organized enough to evolve with additional layouts, locales, themes, or size variants

Flag as a concern when:
- adaptive values are hard-coded deep in feature logic
- presentation choices are scattered across domains that should not own them
- future adaptive variants would require broad refactors

### 16. Previewability

Adaptive UIs should be previewable in meaningful states.

Check whether:
- compact, medium, and expanded variants can be previewed where useful
- list-detail and supporting-pane variants can be previewed separately
- root shell and destination content can be inspected without full app bootstrapping

Flag as a concern when:
- adaptive review requires always running the whole app
- layout branches are too entangled for previews
- preview coverage ignores the important adaptive states

### 17. Testability

Adaptive decisions should be testable as behavior, not only manually inspected.

Check whether:
- size-class-driven shell decisions can be validated
- pane visibility rules can be tested
- state survives or adapts coherently across resize-related changes
- large-screen and constrained-window scenarios have intentional verification paths

Flag as a concern when:
- adaptive correctness depends only on manual QA
- resizing behavior is too implicit to verify
- shell adaptation is hard-coded in a way that resists testing

---

## Severity framework

### High severity
Likely to cause broken or misleading adaptive behavior.

Examples:
- app only works well in one full-screen phone shape
- no structured size-class strategy
- major content inaccessible in multi-window mode
- hard dependence on orientation/aspect-ratio assumptions
- list-detail/supporting-pane logic breaks on resize

### Medium severity
Workable, but likely to create maintenance cost or UX inconsistency.

Examples:
- adaptive navigation is patchy
- canonical layouts are ignored where they fit well
- modifier/layout structure makes adaptive behavior brittle
- previews do not cover important adaptive variants

### Low severity
Structurally acceptable but worth improving.

Examples:
- modifier order could be clarified
- some adaptive states could be previewed better
- resource ownership is slightly scattered

---

## Required output format

When performing the review, respond with:

1. **Adaptive UI summary**
   - target environments
   - size-class strategy
   - navigation adaptation
   - canonical layout choice
   - multi-window / resize posture
   - resource strategy

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by review dimension**
   - adaptive mindset
   - window size classes
   - navigation adaptation
   - canonical layout choice
   - list-detail
   - supporting-pane
   - multi-window support
   - orientation/aspect-ratio/resizability assumptions
   - adaptive do’s/don’ts
   - layout structure
   - modifier discipline
   - intrinsic measurements
   - alignment lines
   - visibility behavior
   - resource strategy
   - previewability
   - testability

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact layout/shell changes
   - what should become size-class-driven
   - where canonical layouts should replace custom structure
   - what should move into previews/tests
   - where modifier/resource ownership should be cleaned up

6. **Suggested target structure**
   - proposed shell / pane / destination / resource split if useful

7. **Open risks**
   - migration cost
   - UX/regression risk during adaptation
   - platform/window scenarios still to validate

---

## Tone

Be direct and practical.
Do not praise a layout just because it stretches.
If the adaptive strategy is weak, say why clearly.

---

## Anti-patterns to flag aggressively

- designing for one phone-sized full-screen window only
- device-type assumptions used instead of current window-size reasoning
- stretched phone UI presented as tablet support
- no adaptive navigation strategy
- bespoke multi-pane designs where canonical layouts would fit
- brittle resize behavior
- long opaque modifier chains hiding adaptive logic
- casual intrinsic-measurement usage
- visibility tracking without clear purpose
- adaptive UI that cannot be previewed or tested meaningfully

---

## References

- Android: Adaptive layouts in Compose: https://developer.android.com/develop/ui/compose/layouts/adaptive
- Android: Support different display sizes: https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes
- Android: Use window size classes: https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes
- Android: Support multi-window mode: https://developer.android.com/develop/ui/compose/layouts/adaptive/support-multi-window-mode
- Android: Orientation, aspect ratio, and resizability: https://developer.android.com/develop/ui/compose/layouts/adaptive/app-orientation-aspect-ratio-resizability
- Android: Build adaptive navigation: https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation
- Android: Canonical layouts: https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts
- Android: List-detail layout: https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail
- Android: Supporting pane layout: https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout
- Android: Adaptive do's and don'ts: https://developer.android.com/develop/ui/compose/layouts/adaptive/adaptive-dos-and-donts
- Android: Alignment lines in Compose: https://developer.android.com/develop/ui/compose/layouts/alignment-lines
- Android: Intrinsic measurements in Compose: https://developer.android.com/develop/ui/compose/layouts/intrinsic-measurements
- Android: Visibility modifiers in Compose: https://developer.android.com/develop/ui/compose/layouts/visibility-modifiers
- Compose Multiplatform: Adaptive layouts: https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html
