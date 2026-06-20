---
name: kotlin-build-kmp-gradle-governance
description: Use when reviewing or designing Gradle build structure for KMP projects — shared build logic, convention plugins, version catalogs, Android KMP plugin usage, source-set configuration, and module dependency hygiene.
license: Apache-2.0
metadata:
  author: Mariano Miani
  version: "1.0.0"
---

# Kotlin Multiplatform Gradle Governance

Use this skill when reviewing or designing Gradle build structure for a Kotlin Multiplatform project.

This skill is intentionally limited to guidance that is grounded in official Android, Gradle, and Kotlin Multiplatform documentation. It should avoid embedding stack-specific assumptions such as a particular DI framework, persistence library, obfuscation configuration, CI provider, or fixed plugin/version matrix unless the user explicitly asks for those.

## Primary goals

The review or design should optimize for:

- shared build logic instead of repeated module configuration
- clear, stable module boundaries
- source-set-correct KMP configuration
- dependency consistency across modules
- minimal public module surfaces
- maintainable plugin and dependency management

## Official defaults to prefer

Unless the project has a strong reason not to, prefer:

- convention plugins to share repeated build logic across modules
- version catalogs for centralized dependency and plugin coordinates
- explicit source-set-aware KMP configuration
- narrow module APIs and `implementation` by default instead of exposing transitive dependencies unnecessarily
- Android KMP library modules configured with the officially supported Android KMP library plugin when that plugin matches the project’s needs

---

## Review dimensions

### 1. Shared build logic

Check whether repeated Gradle configuration is centralized instead of duplicated.

Prefer:
- convention plugins or equivalent shared build logic
- one place to define repeated module defaults
- module build files that stay focused on what is unique to that module

Two common approaches for organizing convention plugins:
- **`build-logic/` as an included build**: a standalone Gradle build included via `includeBuild("build-logic")` in `settings.gradle`. This approach gives better IDE support, better build caching, and clearer isolation. It is the pattern used in the Android Now in Android reference project and is generally preferred for new projects.
- **`buildSrc/`**: a special Gradle directory that is automatically included before the main build. It is simpler to set up but offers weaker caching, slightly worse IDE performance on large projects, and is harder to migrate away from.

Either approach can work well. The key is that one of them is used rather than scattering convention logic across module scripts.

Flag as a concern when:
- many modules copy the same Android, Kotlin, or publishing configuration
- plugin and compiler settings drift across modules
- build logic is scattered across unrelated module scripts
- neither `buildSrc` nor an included build is used in a project large enough to benefit from shared build logic

### 2. Convention plugins

Review whether convention plugins are used appropriately.

**Build-logic location:** Convention plugins can live in `buildSrc/` (treated as an implicit included build) or an explicitly included build directory (commonly `build-logic/`). An explicit included build is generally preferred in modular projects because it supports better caching, cleaner IDE support, and clearer isolation. `buildSrc/` rebuilds on every sync even when unchanged, which can slow multi-module projects. If the project uses `buildSrc/`, consider flagging this for future migration if build performance is a concern.

Check whether:
- conventions are grouped around real module roles (e.g., `android-library`, `kmp-library`, `app`)
- the plugin logic configures existing plugins cleanly without unnecessary indirection
- conventions reduce duplication without hiding important module differences
- convention plugins enforce standards rather than introducing opaque magic
- the build-logic location is consistent and clearly understood by contributors

Flag as a concern when:
- convention plugins become dumping grounds for unrelated logic
- modules still require large repeated setup despite having convention plugins
- convention plugins bake in stack-specific choices (DI setup, obfuscation config) that should stay opt-in
- `buildSrc/` causes noticeable sync slowdowns in a large project that could benefit from an explicit included build

### 3. Version catalogs

Check whether dependency and plugin coordinates are centralized through version catalogs.

Prefer:
- consistent aliases for shared dependencies
- central version management
- reduced string-literal dependency declarations across modules

Flag as a concern when:
- versions are repeated in many module build files
- plugin and library coordinates drift across the build
- catalog usage is inconsistent enough that it no longer provides governance value

### 4. KMP source-set-aware build design

Check whether build configuration respects KMP source sets.

Review whether:
- shared dependencies are added to shared source sets intentionally
- platform-specific dependencies stay in platform-specific source sets
- the build does not treat `commonMain` as a generic dumping ground
- the build remains correct as more targets are added

Flag as a concern when:
- platform-only dependencies are configured as if they were common
- source-set structure is ignored in favor of convenience
- build logic assumes one platform and applies it globally

### 5. Android target integration in KMP modules

When configuring Android library support inside KMP, prefer official Android/Kotlin-supported integration patterns.

The Android KMP library plugin (`com.android.kotlin.multiplatform.library`) is the officially supported path for adding Android target support to KMP library modules. Verify that it is available for and compatible with the AGP version the project uses.

Check whether:
- Android support in KMP library modules uses the appropriate official plugin path (`com.android.kotlin.multiplatform.library` or an equivalent current recommendation from Android docs)
- Android-specific config is isolated cleanly from target-agnostic build logic
- the build structure reflects the difference between general KMP configuration and Android-specific configuration

Flag as a concern when:
- Android config is mixed into shared build logic without clear separation
- unofficial patterns are treated as baseline without a project reason
- plugin usage obscures which parts of the build are Android-specific

### 6. Modular dependency hygiene

Review module dependencies with Android modularization guidance in mind.

Check whether:
- each module has a clear purpose
- public APIs are as small as possible
- `implementation` is preferred unless consumers truly need exposed types
- module dependency direction is intentional and understandable

Flag as a concern when:
- modules expose too much through `api`
- “shared/core/common” modules collect unrelated responsibilities
- module boundaries are weak enough that build structure no longer protects architecture

### 7. Plugin management and repository governance

Check whether plugin and dependency resolution are centralized clearly.

Prefer:
- explicit plugin management
- explicit repository declaration
- repository policy that avoids ad hoc per-module repository configuration when that would weaken governance

Flag as a concern when:
- modules declare repositories inconsistently
- plugin resolution is scattered
- build reproducibility depends on hidden local configuration

### 8. Keep build logic generic unless explicitly stack-opinionated

This public skill should not assume:
- a specific DI framework
- a specific database library
- a specific obfuscation setup
- a specific config-generation tool
- a specific CI platform

Flag as a concern when:
- stack-specific setup is treated as universal KMP guidance
- sample build logic is presented as official baseline when it is only one possible stack choice

### 9. Version and compatibility guidance

Do not hard-code fast-changing version advice into the skill unless the user explicitly asks for a current compatibility recommendation.

Prefer:
- structural guidance that remains valid as tools evolve
- directing the user to official release notes and compatibility matrices for current version requirements

When a user explicitly asks for current compatibility information, direct them to:
- KGP (Kotlin Gradle Plugin) and AGP compatibility table: https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin
- AGP release notes: https://developer.android.com/build/releases/gradle-plugin
- Kotlin releases: https://kotlinlang.org/docs/releases.html

Flag as a concern when:
- the build guidance embeds stale version floors or compatibility claims as timeless rules
- the project's AGP, KGP, and Kotlin versions are not explicitly pinned in version catalogs
- the build assumes a compatibility relationship that is not verified against current docs

### 10. Build review output quality

When reviewing build structure, the output should identify:
- duplicated build logic
- weak module dependency boundaries
- source-set mistakes
- overexposed APIs
- catalog/convention-plugin opportunities
- anything stack-specific that should be opt-in rather than baseline

---

## Required output format

When using this skill, respond with:

1. **Build structure summary**
   - root build organization
   - module/build-logic layout
   - plugin/dependency management shape

2. **What is structurally sound**
   - concrete strengths only

3. **Issues by dimension**
   - shared build logic
   - convention plugins
   - version catalogs
   - source sets
   - Android KMP integration
   - modular dependency hygiene
   - plugin/repository governance
   - stack-specific leakage
   - version/compatibility risk

4. **Severity for each issue**
   - high / medium / low

5. **Concrete recommendations**
   - exact restructuring steps
   - which logic should move to convention plugins
   - which dependencies should move to catalogs
   - where source-set placement is wrong
   - where module APIs should narrow

6. **Suggested target structure**
   - proposed `build-logic/`, catalog, and module dependency layout if useful

7. **Open risks**
   - migration cost
   - likely breakage points
   - compatibility checks still required

---

## Anti-patterns to flag aggressively

- repeated Gradle config across many modules
- no convention-based shared build logic in a large modular project
- platform-specific dependencies placed in common source sets
- excessive `api` exposure
- repositories declared ad hoc in many modules
- stack-specific tools presented as universal KMP baseline
- stale version rules treated as timeless guidance

---

## References

- Android Modularization Patterns: https://developer.android.com/topic/modularization/patterns
- Gradle Convention Plugins: https://docs.gradle.org/current/userguide/implementing_gradle_plugins_convention.html
- Gradle Version Catalogs: https://docs.gradle.org/current/userguide/version_catalogs.html
- Kotlin Multiplatform project structure and source sets: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
- Kotlin Multiplatform DSL reference: https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-dsl-reference.html
- Android Kotlin Multiplatform plugin: https://developer.android.com/kotlin/multiplatform/plugin
