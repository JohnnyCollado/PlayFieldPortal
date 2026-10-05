# Emulator Knowledge Base (EKB), Security-First

**Status: Not started (planned 2026-10-02 on `final-polish` at `90feb7ad`).** The security design and
the main settings screen are approved.

**Amended 2026-10-02 with the user's answers:**
- **Q1:** Tink is approved.
- **Q2:** The repo is public and the rolling `emulator-kb` release tag is approved.
- **Q3:** An updated mockup is coming. 6.3 and 7.2 stay gated on its sign-off.
- **Q4a/b:** Citra and Sudachi keep both package names. This led to AD-15.
- **Q4c:** Still open.
- **Q5:** The KB stays out of backups. Backup & Restore is disabled for now (AD-13), and the plan does
  not depend on it.

> **Working rules for the implementing session**
> - Write the tests first. Each task's tests must exist and fail before its production code is
>   written. Revert any production code written ahead of its tests.
> - Do not run Gradle unless the user asks. Give the task's command in a bash block.
> - A task is done only when its tests pass and the build has **no new Kotlin warnings**.
> - Do not commit.
> - `SettingsNavHost.kt`, `SettingsScaffold.kt` and parts of `feature-xmb` had uncommitted edits
>   when this plan was written. Work on top of whatever is in the tree; do not revert those edits.

## Context

The app's emulator knowledge is compiled in. Changing a launch recipe, adding an emulator, or adding
a ROM extension to a console takes an APK release. The user wants to ship that data as a signed JSON
file, let users import their own files, and let users share their custom emulator settings. The user
asked for a design that is **security first**.

A launch recipe is not inert data. It picks the `ComponentName` an intent targets, the extras it
carries, and the package that receives `grantUriPermission(…, READ)` on the ROM
(`EmulatorIntentResolver.buildComponentIntent` / `grantReadPermissionIfNeeded`). The core risk is a
file that turns PFP into a confused deputy. The rest of this plan follows from that.

## Problem

1. **Two sources for the same knowledge, and they have drifted.**
   - `feature/feature-launcher/.../KnownEmulatorCatalog.kt` has 85 `KnownEmulator` recipes with no
     stable ids. `EmulatorDetector.detect` turns each installed package into a profile with id
     `autoId(pkg)` = `auto_<pkg with . → _>`.
   - `feature/feature-launcher/src/main/assets/emulator_profiles/bundled_profiles.json` has 38
     profiles with ids such as `duckstation` and `ppsspp_gold`. `EmulatorProfileRepository` loads
     them through `loadBundledProfiles()`. That path **skips `EmulatorProfileAdmission`**, which only
     guards the persisted file.
   - The same installed emulator therefore appears twice: the bundled id and `auto_<pkg>`. The two
     copies launch differently (see the drift table in Task 8.1).
2. **There is no update channel.** Changing a recipe or an extension means an APK release, and
   extension fixes have been shipped as one-off Room migrations: `MIGRATION_22_23`'s `zip` append,
   `MIGRATION_48_49`'s PS3 list, and `MIGRATION_54_55`'s X360 default.
3. **Users cannot share setups.** Custom profiles live only in
   `filesDir/emulator_profiles/custom_profiles.json`.
4. **Some launch-time hardening is still missing.**
   - `CUSTOM_COMMAND` is refused on load (`EmulatorProfileAdmission`), but it is still built at
     launch: `buildCustomCommandIntent` → `parseAmCommand`, whose `-n` can aim at any component.
     The editor still offers it (`EmulatorProfileEditorScreen.kt`, the `IntentType.entries` picker
     at L123).
   - Nothing checks that the installed app behind a package name is the app the recipe was
     written for.

## Current Behavior

| Area | Where | Today |
|---|---|---|
| Runtime model | `core-domain/.../model/EmulatorProfile.kt` | `@Serializable`. `IntentType` {ACTION_VIEW, COMPONENT, SHORTCUT, CUSTOM_COMMAND}. `LaunchTemplate` has 9 placeholders. |
| Admission | `core-domain/.../model/EmulatorProfileAdmission.kt` | Allow-list. Refuses CUSTOM_COMMAND, bad package names (`PACKAGE_NAME` regex), the app's own package, COMPONENT without an activity, and flags outside {NEW_TASK, CLEAR_TOP, CLEAR_TASK}. Used by `EmulatorProfileRepository.loadPersistedProfiles` and `RestoreArchive.sanitizeProfiles`. |
| Profile set | `EmulatorProfileRepository.kt` | Bundled asset + persisted file, merged by id; the persisted copy wins. |
| Detection | `EmulatorDetector.detect` | Iterates `KnownEmulatorCatalog.entries`, takes the first installed package, and builds an `auto_<pkg>` profile. RetroArch cores come from `RetroArchCoreScanner`. |
| Refresh | `EmulatorAutoConfigService.runOnStartup` | Adopts fresh recipes for auto entries that are not `userModified`, and marks undetected ones `isAvailable=false`. Runs from `PFPApplication.initEmulators` and after RetroArch link/rescan. |
| Editor | `EmulatorsSettingsViewModel.saveEditorProfile` | Every save sets `isCustom=true` (via `draftProfile`) and `userModified=true`. Custom profiles get UUID ids. |
| Launch | `EmulatorIntentResolver.validateBeforeLaunch` / `resolve` | Checks that the package is installed, the COMPONENT activity exists, and the ROM grant is live. Every intent is explicit, by component or by `setPackage`. |
| Ladder | `EmulatorLaunchResolver.resolve` | Matches a stored value by profile **id**, then by **package**. `games.emulator_package` and `memory_cards.emulator_id` hold profile ids (`XMBViewModel` L7848 compares `game.emulatorPackage == it.id`; `MemoryCardDao.setEmulator`). `platforms.preferred_emulator_package` holds a package. |
| Platforms | `core-data/.../seeder/PlatformSeeder.kt` | 43 `DEFAULT_PLATFORMS`, `INSERT OR IGNORE` on every launch. `rom_extensions` is a CSV. |
| Extensions in use | `MemoryCardRepository.addCard`, `ScanSourceResolver`, `LibraryManagerViewModel` L691/699 | Scanning reads **`memory_cards.supported_extensions`**, a copy taken from `platforms.rom_extensions` when the card is created. **Users edit the card's list** (add/remove). Nothing in the app writes `platforms.rom_extensions` after seeding. |
| Backup | `BackupManager.BUNDLED_FILE_ROOTS`, `FeatureFlags.BACKUP_RESTORE` | **Disabled** (`false`) pending a rework: hidden from Settings › System, route closes. When on, bundles the whole `emulator_profiles` folder; `RestoreArchive` re-admits `custom_profiles.json`. |
| Networking | `SteamClientModule` (bare OkHttp), `DiscordNetworkModule` (qualified Ktor client in core-data, no logging) | `core-data` already has the Ktor bundle. `feature-launcher` has no HTTP dependency. |
| Bounded reads | `core-data/.../repository/SafeMedia.kt` | `InputStream.readCapped(cap)` returns null once the cap is passed, with no reliance on Content-Length. |

## Root Cause

Emulator knowledge is code (a Kotlin list plus an unvalidated asset), and nothing defines what a
trustworthy piece of knowledge looks like. Until a schema, a validator and a source-of-trust model
exist, opening a data channel would only add attack surface.

## Goals

- One schema (`emulators.json`) for built-in, official and user knowledge, enforced by one strict
  validator.
- Official updates are Ed25519-signed and fetched from one fixed HTTPS URL. They are anti-rollback,
  bounded in size, written atomically, and the last good copy is kept on any failure. Checks run at
  most once a day, plus on demand.
- Users import their own files through the SAF picker only, review each entry, and choose which
  ones to apply. They can remove a file in one step or reset to built-in.
- Users export chosen custom and edited profiles in the same schema.
- Updates can add ROM extensions to existing platforms without overriding anything the user
  changed.
- After consolidation, the knowledge has one source. The drift is resolved and existing Room
  assignments keep resolving.
- Launching stays data-only: no commands, explicit targets only, a fixed set of placeholders, and
  optional signer pinning.

## Non-Goals

- **RetroArch.** `RetroArchCoreScanner` core tables and `retroarch-core` profiles stay in code.
- **PC launchers.** `PcLauncherCatalog`, `PcLauncherAdapter` and SHORTCUT launching are out of scope.
  SHORTCUT is not allowed in the KB.
- **New platforms.** A KB cannot add a platform (no icons, no seeder row). Platform names, short
  names, accent colours, aliases (`EmulatorPlatformMapping.platformAliases`) and default emulators
  do not move into the KB.
- **Removing extensions.** Extension changes are additive only (AD-9).
- **Importing from a URL**, and any user-configurable update URL.
- **Backup integration.** Backup & Restore is disabled pending a rework
  (`core-domain/.../model/FeatureFlags.kt`, `BACKUP_RESTORE = false`), so no task touches
  `feature-backup`. KB files and KB preferences join the backup once the rework lands (follow-up).
- **App drawer tagging.** `KnownEmulatorPackages` stays a hand-kept list. Tagging KB-added packages
  in the drawer is a follow-up.
- **Automatic library rescans** after an extension change.
- **The per-profile editor's fields and layout**, apart from removing the CUSTOM_COMMAND option (2.5).
- **Backing up the KB folders** (AD-13).

## Existing Systems to Reuse

| System | File | Use |
|---|---|---|
| Admission rules | `EmulatorProfileAdmission.kt` | The validator reuses its package regex and flag allow-list. It is not a parallel copy. |
| Runtime model | `EmulatorProfile.kt` | KB entries are turned into `EmulatorProfile`s. Two optional fields are added (2.4). |
| Detection / refresh | `EmulatorDetector.kt`, `EmulatorAutoConfigService.kt` | Detection reads the effective KB instead of the catalog. Refresh logic and the `userModified` rule are unchanged. |
| Launch | `EmulatorIntentResolver.kt` (`validateBeforeLaunch`) | The signer check is added at the existing preflight. |
| Ladder | `EmulatorLaunchResolver.kt` | Unchanged; its id/package matching is what legacy ids must keep working with. |
| Bounded reads | `SafeMedia.readCapped` | Download body and SAF import. |
| Qualified Ktor client | `DiscordNetworkModule.kt` | Pattern for `@EmulatorKbHttpClient` in core-data. No second unqualified HTTP binding. |
| DataStore | `core-data/.../datastore/PFPDataStore.kt` (`pfpDataStore`) | Auto-update toggle, last check, status. |
| Settings UI | `SettingsScaffold`, `SettingsGroup`, `SettingsRow` (`actions`), `SettingsToggleRow`, `SettingsValueRow`, `rememberSettingsModal` + `PfpModalSpec.Confirm` | Every row on the new screens. Sub-screens follow `EmulatorsSettingsScreen`'s `WizardPickAppStep` / `TestLaunchFlow` pattern. |
| SAF pickers | `rememberLauncherForActivityResult(OpenDocument())` (e.g. `BackupSettingsScreen.kt`) | Import. Export uses `CreateDocument("application/json")`. |
| Settings routing | `SETTINGS_SCREEN_ROUTES` + `SettingsNavHost`, `settingsSectionItems(EMULATORS)` in `XMBViewModel.kt`, `SettingsHierarchyTest` | New L2 entry. |
| Extension DAO writes | `MemoryCardDao.setSupportedExtensions` | Card updates. |
| Startup hook | `PFPApplication.initEmulators` | KB refresh at startup. |

## Architectural Decisions

- **AD-1 Data, never code.** The KB can express ACTION_VIEW and COMPONENT recipes only.
  - CUSTOM_COMMAND and SHORTCUT are rejected.
  - Every launch targets an explicit package, plus the component for COMPONENT. The app only calls
    `startActivity`: no broadcasts, no services.
  - Allowed flags: NEW_TASK, CLEAR_TOP, CLEAR_TASK. The resolver alone adds
    `FLAG_GRANT_READ_URI_PERMISSION`, on the single ROM URI.
  - The launch path also refuses CUSTOM_COMMAND (2.5), so a profile that bypassed admission still
    cannot run a command.
  - *Rules out:* any "advanced" escape hatch in the schema.
- **AD-2 One strict validator for every KB source** (built-in, official, user files, and export
  output), in core-domain, pure Kotlin.
  - **Structure:** unknown keys are rejected (strict decode per entry), plus length and count caps.
  - **Packages:** the `EmulatorProfileAdmission` package regex. Not the app's own package, nor
    anything starting with `com.playfieldportal.`. Not starting with `android.`, `com.android.` or
    `com.google.android.`, which rejects pointing a recipe at system components.
  - **Activity, action, category:** dotted Java identifiers, at most 200 characters.
  - **Extras:**
    - A string extra or array item is **either exactly one** `LaunchTemplate` placeholder **or** a
      literal matching `[A-Za-z0-9._,=+@ -]{0,64}`. There is no `/` and no `:`, so a literal can
      never be a path or a URI.
    - At most 16 entries of each extras kind, and at most 8 items per array.
  - **MIME type:** `type/subtype`.
  - **Extensions:** `[a-z0-9]{1,10}`, at most 32 per platform.
  - **Signers:** `signerSha256` values are 64 hex digits; colons are optional and stripped.
  - **Duplicates:** no duplicate id, and no package claimed twice within one document.
  - *Rules out:* trusting the bundled asset (today it skips admission). The built-in file must pass
    with zero rejections, and a test pins that (2.1).
- **AD-3 Unknown platform ids mean "not applicable", not "invalid".**
  - An emulator's platform id that is neither seeded nor an alias is dropped from that entry. An
    entry left with no platform is inert and omitted. A `platforms` item for an unknown id is
    skipped.
  - This keeps an official file written for a newer app from failing on an older one. Unsafe
    content is still rejected.
  - The known set is `PlatformSeeder.DEFAULT_PLATFORMS` ids plus every `platformAliases` expansion.
    The caller passes it in, so core-domain does not depend on core-data.
- **AD-4 How errors are handled depends on the source.**
  - **Official and built-in files are all or nothing.** The publisher wrote them, so any rejection
    is a publishing bug. The last good copy is kept.
  - **User files are handled per entry.** Rejected entries show as Blocked with a reason, and the
    rest stay usable.
- **AD-5 Layers and precedence:** built-in (APK asset) < official update < user files (later import
  wins) < per-profile editor edits.
  - Merging is by KB id. A higher layer that claims a package removes that package from lower
    entries; a lower entry left with no packages drops out. This is the de-dup by package.
  - The editor layer already wins through `EmulatorAutoConfigService`, which never touches
    `userModified` entries.
  - An official file whose `version` ≤ the built-in asset's `version` is ignored. An app update
    with newer built-in knowledge is never shadowed by a stale download.
- **AD-6 Identity.**
  - Every KB entry has a stable `id` (`[a-z0-9_]{2,48}`), `packageNames` (1–8, tried in order like
    `KnownEmulator.packageNames`) and optional `legacyIds`.
  - **Runtime profile ids do not change.** Detection still produces `auto_<installedPkg>` and sets a
    new `EmulatorProfile.knowledgeId`. That id is already what `games.emulator_package` and
    `memory_cards.emulator_id` hold for detected emulators, and what the editor's `userModified`
    copies are keyed by. Phases 2–7 therefore need no Room rewrite.
  - Only the 38 retired bundled ids need mapping. They are listed as `legacyIds` and rewritten in
    8.2.
  - *Rules out:* renaming every profile to its KB id, which would need a rewrite of every stored
    reference and every in-memory `== it.id` comparison.
- **AD-7 Signature check.**
  - Official files carry a detached Ed25519 signature over the exact file bytes
    (`emulators.json.sig`, base64 of 64 bytes). It is checked **before** parsing.
  - The app pins a **list** of raw 32-byte public keys and accepts a file if any pinned key verifies
    it, which allows rotation.
  - Ed25519 is not available on API 29 (minSdk) from the platform, so the verifier uses **Tink**
    (`com.google.crypto.tink:tink-android`, `subtle.Ed25519Verify`). It is Google-maintained, ships
    its own R8 rules, and only the verify path is reached. **Approved (Q1).**
  - The offline signing tool uses the JDK's built-in Ed25519 and needs no dependencies. JVM tests
    sign fixtures with the JDK too, which also cross-checks Tink against a second implementation.
  - With an empty pinned list (before the key ceremony), updates are disabled, not unverified.
- **AD-8 Update channel.**
  - **Source:** fixed constants, approved under Q2:
    `https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/emulator-kb/emulators.json`
    and `.../emulator-kb/emulators.json.sig`.
    - The repository is public, so no token is needed.
    - `emulator-kb` is a dedicated rolling release whose two assets are replaced on each publish. It
      is independent of APK releases, which is why `releases/latest` is not used.
    - HTTPS only. Redirects to GitHub's asset CDN are followed but never downgraded to HTTP (the Ktor
      `HttpRedirect` default).
  - **What is sent:** no query parameters, no cookies, and only `User-Agent: PlayFieldPortal`.
  - **Bounded reads:** the body is capped at 1 MiB and the signature at 1 KiB with `readCapped`.
  - **Gates:**
    - `format` must match.
    - A newer `schemaVersion` is skipped with the status "needs app update". So is a
      `minAppVersion` above the running versionCode.
    - `version` must be ≥ the highest version ever accepted. Equal is allowed so a reset can
      re-fetch.
  - **Write:** a temp file, then rename. On any failure the last good copy stays and the status
    records why.
  - **Schedule:** a startup check when the toggle is on and the last check was at least 24 h ago,
    plus Check for updates.
  - *Rules out:* WorkManager scheduling. A launcher is in the foreground daily, and a worker adds
    wiring for no gain.
- **AD-9 Platform info is additive and guarded.**
  - The KB `platforms` section can only **add** extensions to existing platforms. Removing one would
    drop games from the library on the next scan, and a data file must not be able to do that.
  - Each platform's last KB-applied set is recorded. A row whose list still equals (as a set) the
    previous applied set or the seed default takes every KB extension. A row the user edited takes
    only the extensions that are new since the last apply (the KB list minus that baseline): its
    removals stay removed and its additions stay (changed 2026-10-04; before, edited rows took
    nothing). A row already holding everything is left as is (idempotent). This follows
    `MIGRATION_48_49`'s rule that "a knowledge-base update must never overwrite an override", and it
    needs **no schema change**.
  - New extensions take effect on the next scan. The KB screen lists the consoles that gained file
    types and suggests a rescan.
- **AD-10 Signer pinning.**
  - An optional `signerSha256` list per entry is copied onto the profile.
  - `validateBeforeLaunch` refuses when the list is non-empty and
    `PackageManager.hasSigningCertificate(pkg, sha256, CERT_INPUT_SHA256)` matches none. The check is
    behind a small injectable seam so it can be tested. API 28+, so no version branch is needed at
    minSdk 29, and it follows Android's own rotation lineage.
  - The import review shows "Blocked: signer differs" when the package is installed and does not
    match.
- **AD-11 Import is reviewed and decided per entry.**
  - Files come from the SAF picker (`OpenDocument`) only, and are capped at 1 MiB.
  - Each entry is matched to the effective KB by id, then legacyId, then package:
    - **An entry that matches** is shown as a change with "Override?", **unchecked by default**. It
      shows a diff of launch fields and an "Overrides official" note when the entry it replaces comes
      from the built-in or official layer.
    - **A new entry** is shown with "Add?", **checked by default**.
    - **A blocked entry** shows its reason and has no choice.
    - **A platform item** for an existing platform is shown with "Update?" and its added
      extensions, unchecked by default.
  - Confirm applies **only the chosen entries**. They are re-serialized and stored as one user layer
    file, never the original bytes. The button reads "Import N emulators", where N counts the
    selection.
- **AD-12 Export is the chosen subset, in the same schema.**
  - **What can be exported:** persisted profiles with `isCustom || userModified`. Excluded:
    RetroArch / `coreMap` profiles, SHORTCUT, and CUSTOM_COMMAND. The user picks which to include;
    all are checked by default.
  - **What is written:** only schema fields. `notes`, timestamps, availability, and anything tied to
    the library are stripped. Ids are slugged, and the profile's `knowledgeId` is used when present.
  - **Validation:** the output passes through the AD-2 validator. Entries that fail are listed as
    "can't be shared" with the reason and are left out, so an export is always importable and never
    carries a literal path.
- **AD-13 Storage, outside the backup (Q5 confirmed).**
  - Layout: `filesDir/emulator_kb/official/{emulators.json, state.json}`,
    `filesDir/emulator_kb/user/{index.json, <uuid>.json}`, and
    `filesDir/emulator_kb/platform_applied.json`. Every layer is re-validated on every load.
  - These folders are **not** added to `BUNDLED_FILE_ROOTS`. A restore must not become an import
    that skips review. Export is the portable path.
  - Backup & Restore is currently disabled (`FeatureFlags.BACKUP_RESTORE = false`). No task in this
    plan edits `feature-backup` or depends on a backup or restore path.
- **AD-14 Order of the phases.** The settings screen shell (Phase 5) comes ahead of the import and
  export UI so those flows have a home. Every other part of the requested phase order is kept.
- **AD-15 One emulator, several builds: a per-package launch override (Q4).**
  - An entry may carry `launchByPackage`, a map from one of its own `packageNames` to a complete
    `launch` object. That object **replaces** the entry's `launch` for that package only. There is no
    field-by-field merge.
  - Detection uses the override for the package it finds installed. An entry without the map behaves
    exactly as before.
  - **Validation:**
    - Each key must be in the entry's `packageNames`.
    - Each value passes the same AD-2 launch rules.
    - At most 8 overrides per entry.
  - **Why this and not device verification:** the user confirmed `org.sudachi.android` and
    `org.sudachi.sudachi_emu` are builds of the same emulator, and they need different recipes. That
    means ACTION_VIEW for the first, as bundled, and COMPONENT for the second, as in the catalog. One
    entry with one recipe cannot launch both, and splitting them into two entries is what the user
    rejected.
  - Device checks in 8.1 still confirm each recipe, but a failed check is not what keeps one build
    working.
  - *Rules out:* partial overrides, overrides for packages outside the entry, and per-package
    platform lists.

## Rejected Alternatives

- **ECDSA P-256 through the platform `Signature`** (zero new dependencies). Viable, but the
  approved design is Ed25519 through Tink (Q1): deterministic signatures, with no per-signature
  randomness at signing time.
- **`releases/latest`.** It follows APK releases, so every APK release would also have to carry the
  KB assets.
- **Splitting multi-build emulators into separate entries** (Citra, Sudachi). The user wants one
  entry per emulator. AD-15 covers the builds that differ.
- **BouncyCastle.** It is a much larger surface than one verify path, and Android's bundled copy is
  not public API.
- **A hand-written Ed25519 verifier.** Hand-written cryptography is not acceptable.
- **A host allow-list for the redirect target.** GitHub has changed its asset CDN host before. The
  signature is the authority and HTTPS covers transport, so a host list would only add a way for
  updates to fail silently.
- **Importing from a URL, or a user-configurable official URL.** Either one makes a phishing link
  enough to plant an import.
- **Rewriting every runtime id to the KB id** (see AD-6).
- **A `user_modified` column for extensions.** It needs a schema bump, and a set comparison against
  known defaults answers the same question (AD-9).
- **Partial application of an official file.** A half-applied signed file is a state nobody
  published.
- **Backing up user KB files.** They would skip review on restore (AD-13).
- **Blocking launches of system-flagged apps.** Handheld vendors preinstall emulators as system
  apps. The validator's package-prefix deny list covers the real risk.

## Data / Persistence

- **Room: no schema change.** Writes use existing columns. New DAO update queries are added in 4.2
  (`PlatformDao`) and 8.2 (legacy id rewrite).
- **`EmulatorProfile`** gains `knowledgeId: String? = null` and
  `signerSha256: List<String> = emptyList()`. Both default, so old persisted JSON still decodes.
- **KB document** (`format: "pfp-emulator-kb"`, `schemaVersion: 1`):

  ```json
  {
    "format": "pfp-emulator-kb", "schemaVersion": 1,
    "version": 2026100200, "label": "2026.10.02", "minAppVersion": 10,
    "emulators": [{
      "id": "duckstation", "name": "DuckStation",
      "packageNames": ["com.github.stenzek.duckstation"], "legacyIds": [],
      "platformIds": ["psx"],
      "launch": { "intentType": "COMPONENT",
                  "activityClass": "com.github.stenzek.duckstation.EmulationActivity",
                  "extras": {"bootPath": "{rom_uri}"}, "boolExtras": {"resumeState": false},
                  "flags": ["CLEAR_TASK", "CLEAR_TOP"] },
      "signerSha256": []
    }],
    "platforms": [{ "id": "psx", "romExtensions": ["cue", "bin", "chd"] }]
  }
  ```

  `launch` also allows `arrayExtras`, `action`, `category`, `attachRomData`, `mimeType` and
  `useSafUri`, matching the `KnownEmulator` fields one to one. Caps: 1 MiB, 500 emulators, 64
  platforms, name ≤ 64 characters. User files and exports use `version: 0`, which is ignored.
- **Optional `launchByPackage` (AD-15):** maps a package to a complete `launch` object for that build
  only. For example, the Sudachi entry has
  `"packageNames": ["org.sudachi.sudachi_emu", "org.sudachi.sudachi_emu.ea", "org.sudachi.android"]`,
  a COMPONENT `launch`, and `"launchByPackage": {"org.sudachi.android": { "intentType": "ACTION_VIEW", ... }}`.
- **DataStore keys:** `emulator_kb_auto_update` (Boolean, default true), `emulator_kb_last_check_at`,
  and `emulator_kb_last_result`. They are not added to `BackupManager`'s key list now. Backup is
  disabled and its key list will be revisited in the rework (follow-up).

## Compatibility

- **Phases 1–7 change no stored id.** Bundled profiles keep loading until 8.3, so every existing
  assignment resolves exactly as it does today.
- **Detection switches from the catalog to the built-in KB in 2.4.** The asset is a parity
  conversion of the catalog (2.1). Launch behaviour only changes where AD-3 drops non-seeded
  platform ids (`naomi`, `atomiswave`, `symbian`), and those can never match a game.
- **When bundled profiles retire (8.2/8.3),** stored references to a bundled id are rewritten to the
  matching `auto_<pkg>`, except where a persisted profile with that id exists (a user's edited copy
  keeps its id). The rewrite is idempotent and runs on every startup.
- **Backup & Restore is disabled** (`FeatureFlags.BACKUP_RESTORE = false`) pending a rework.
  - Nothing here relies on it.
  - The KB folders and the `emulator_kb_*` preferences are not in the backup.
  - When backup returns, the rework must keep KB files out of restore, or send them through import
    review (AD-13). It must decide whether to carry `emulator_kb_auto_update`.
  - The rework must also re-admit `custom_profiles.json` with the two new optional profile fields.
- **CUSTOM_COMMAND:** any existing CUSTOM_COMMAND profile that slipped past admission now fails at
  launch with a named reason instead of running.

## Implementation Phases

1. **Schema and validator** (core-domain, pure).
2. **Layers, identity, launch guards.** Built-in asset, merge, store, detection from the KB, signer
   and command guards.
3. **Signed official updates.** Verifier, downloader, updater, signing tool.
4. **Platform info.** Additive extension planner and applier.
5. **Settings screen shell.** Status, check, toggle, your files, reset.
6. **Import.** Review planner with per-entry selection, flow, review UI.
7. **Export.** Builder, picker + SAF write.
8. **Consolidation.** Drift audit, legacy id rewrite, retire the catalog and bundled asset.

## Verification Strategy

- **Unit tests (core-domain, JVM):** decoder, validator, merge, extension planner, import planner,
  export builder. These are where the security properties are pinned, with negative tests for every
  rule.
- **Robolectric:** the store (atomic write, fallback on corrupt files), the built-in asset (zero
  rejections, catalog parity), detector/auto-config, the platform applier with Room, the legacy
  rewrite, and the ViewModels.
- **Crypto:** fixtures signed by the JDK in-test. Tampered body, tampered signature, wrong key, an
  empty pinned list, and a second pinned key all have tests.
- **Network:** Ktor `MockEngine` covers over-cap bodies without Content-Length, 404, and an HTTP
  URL.
- **Release build:** `:app:assembleFullRelease` once after 3.1. This checks R8 with Tink and the new
  `@Serializable` classes.
- **Device (after Phases 5, 6, 7 and 8; the user drives):**
  - Check for updates against a published test release.
  - Import a shared file and decline one override.
  - Export, then re-import.
  - Launch DuckStation, Dolphin and Eden after consolidation.

## Execution Task Index

| ID  | Task | Depends On | Status |
| --- | ---- | ---------- | ------ |
| 1.1 | KB document model and strict structural decoder | None | DONE |
| 1.2 | Semantic validator shared with admission rules | 1.1 | DONE |
| 2.1 | Built-in `emulators.json` as a parity conversion of the catalog | 1.2 | DONE |
| 2.2 | Pure layer merge with provenance and package de-dup | 1.2 | DONE |
| 2.3 | Knowledge store: filesDir layers, atomic writes, effective KB flow | 2.1, 2.2 | DONE |
| 2.4 | Detection and refresh from the effective KB | 2.3 | DONE |
| 2.5 | Launch guards: signer pinning and CUSTOM_COMMAND removal | 2.4 | DONE |
| 3.1 | Ed25519 verifier with pinned key list | 1.1 (Q1 approved) | DONE |
| 3.2 | Bounded, qualified KB downloader (core-data) | None | DONE |
| 3.3 | Official updater: gates, anti-rollback, install, schedule | 2.3, 3.1, 3.2 | DONE |
| 3.4 | Offline signing tool and key ceremony | 3.1 | DONE (tool; PINNED_KEYS awaits the user ceremony) |
| 4.1 | Additive extension planner with user-edit guard | 1.2 | DONE |
| 4.2 | Platform knowledge applier and refresh wiring | 2.4, 4.1 | DONE |
| 5.1 | Emulator knowledge ViewModel (status, check, toggle, files, reset) | 3.3 | DONE |
| 5.2 | Emulator knowledge screen, route and L2 entry | 5.1 | DONE |
| 6.1 | Import review planner with per-entry selection | 2.2 | DONE |
| 6.2 | Import flow (SAF read, review state, selective apply) | 5.1, 6.1 | DONE |
| 6.3 | Import review screen | 6.2, Q3 mockup sign-off | DONE |
| 7.1 | Export builder | 1.2 | DONE |
| 7.2 | Export picker and SAF write | 5.2, 7.1, Q3 mockup sign-off | DONE |
| 8.1 | Drift audit and built-in KB reconciliation | 2.1, 2.4 (Q4a/b answered; Q4c open, only blocks the winlator/gamehub row) | DONE |
| 8.2 | Legacy id rewrite for retired bundled ids | 8.1 | DONE |
| 8.3 | Retire `bundled_profiles.json` and `KnownEmulatorCatalog` | 8.2 | DONE |

---

### Task 1.1 — KB document model and strict structural decoder

- **Objective:** Decode a KB file into typed entries. Structural problems are reported per entry, and
  the decoder never throws.
- **Parent:** Phase 1 (AD-2, AD-4, AD-15).
- **Scope:**
  - `@Serializable` document, emulator entry, launch and platform types (schema in §Data).
  - A decoder that takes a `String` and returns either a document-level rejection, or a decoded
    document whose emulator and platform items are each `Ok(entry)` or `Rejected(index, id?, reason)`.
- **Existing Code:** `EmulatorProfile.kt` (field names for `launch`), `KnownEmulatorCatalog.kt`
  (`KnownEmulator` shape), `PcGameExportCodec` in `feature-settings/.../pc/PcGameExport.kt` (the
  Valid/Rejected decode precedent and caps style).
- **Requirements:**
  - **Top level:** strict on unknown keys. `format == "pfp-emulator-kb"`. A non-integer
    `schemaVersion` is rejected. The caller compares schema and app versions; the decoder only
    reports them.
  - **Entries:** each item is decoded separately with `ignoreUnknownKeys = false`, so one bad entry
    does not sink the document.
  - **Caps:** count caps (500 / 64), a 1 MiB character cap on the input, and string caps.
  - **Errors:** malformed JSON yields a document-level rejection, never an exception.
  - **`launchByPackage`:** an optional map from package to a full `launch` object (AD-15). Each value
    is decoded strictly, like `launch`.
- **Do Not Change:** `EmulatorProfile`, `EmulatorProfileAdmission`, any feature module.
- **Expected Files:**
  - Add: `core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/emulatorkb/EmulatorKbDocument.kt`,
    `.../emulatorkb/EmulatorKbDecoder.kt`
  - Test: `core/core-domain/src/test/kotlin/com/playfieldportal/core/domain/model/emulatorkb/EmulatorKbDecoderTest.kt`
- **Acceptance (tests first):**
  - A valid two-entry document decodes.
  - An unknown top-level key rejects the document.
  - An unknown key inside one entry rejects only that entry, with a reason naming the key.
  - A wrong `format`, non-JSON input, and input over the caps are each rejected with a reason.
  - 501 emulators are rejected.
  - A missing optional field takes its default.
  - A `launchByPackage` with one override decodes. An unknown key inside an override rejects that
    entry.
- **Change Budget:** 2 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*EmulatorKbDecoderTest*"
  ```
- **Stop Condition:** The decoder exists and its tests pass. It is not called from anywhere yet.
- **If Blocked:** Stop and report what was attempted, what blocked it, which file caused it, and
  what decision is needed.

### Task 1.2 — Semantic validator shared with admission rules

- **Objective:** Turn decoded entries into admitted entries or per-entry refusals, using the AD-2 and
  AD-3 rules.
- **Parent:** Phase 1.
- **Scope:**
  - `EmulatorKbValidator.validate(doc, knownPlatformIds, selfPackage)` returns admitted emulators,
    admitted platform items, and refusals with reasons. Not-applicable platform ids are dropped
    (AD-3).
  - Expose `EmulatorProfileAdmission`'s package regex and flag set as `internal` so both use one
    copy.
- **Existing Code:** `EmulatorProfileAdmission.kt`, `LaunchTemplate`, `EmulatorProfileAdmissionTest.kt`.
- **Requirements:**
  - Every AD-2 rule has its own reason string.
  - The validator is pure, with no Android imports.
  - `EmulatorProfileAdmission` behaviour is unchanged.
- **Do Not Change:** What `EmulatorProfileAdmission.admit` accepts or refuses. `RestoreArchive`.
- **Expected Files:**
  - Add: `.../emulatorkb/EmulatorKbValidator.kt`
  - Modify: `EmulatorProfileAdmission.kt` (visibility only)
  - Test: `EmulatorKbValidatorTest.kt`
- **Acceptance (tests first):** One rejection test per rule:
  - CUSTOM_COMMAND, SHORTCUT, own package, `com.playfieldportal.x`, `com.android.settings`, a bad
    package.
  - COMPONENT without an activity, a bad action or category, a disallowed flag.
  - A literal containing `/`, a literal containing `:`, `"file://{rom_path}"` (mixed), an unknown
    `{placeholder}`, more than 16 extras.
  - An extension `.ISO` or `toolongext1`, more than 32 extensions.
  - A malformed signer.
  - A duplicate id or package.
  - A `launchByPackage` key outside `packageNames`, an override that fails a launch rule (for example
    CUSTOM_COMMAND), and more than 8 overrides (AD-15).

  Plus:
  - Unknown platform ids are dropped. An entry with only unknown platforms is omitted, not refused.
  - A platform item for an unknown id is skipped.
  - A fully valid entry passes.
  - `EmulatorProfileAdmissionTest` still passes unchanged.
- **Change Budget:** 1 new, 1 modified, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*EmulatorKbValidatorTest*" --tests "*EmulatorProfileAdmissionTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 2.1 — Built-in `emulators.json` as a parity conversion of the catalog

- **Objective:** Ship the built-in KB layer as an asset that encodes exactly what
  `KnownEmulatorCatalog` encodes today.
- **Parent:** Phase 2 (AD-3, AD-6).
- **Scope:**
  - Add `feature/feature-launcher/src/main/assets/emulator_kb/emulators.json`: `version` 1,
    `label` "built-in", `platforms` empty.
  - One entry per `KnownEmulator`.
    - Where a bundled profile describes the same emulator, the entry reuses the bundled id
      (`duckstation`, `dolphin`, `nethersx2`, `ppsspp`, …). Otherwise its id is a slug of
      `suggestedName`.
    - `legacyIds` stay empty until 8.1.
  - Remove `naomi`, `atomiswave` and `symbian` (AD-3). EKA2L1 is then omitted, and the test records
    that as a known exception.
  - Add a test that decodes the asset, validates it with zero refusals, and compares it field by
    field with `KnownEmulatorCatalog.entries`.
- **Existing Code:** `KnownEmulatorCatalog.kt`, `KnownEmulatorCatalogTest.kt` (seeded platform set),
  `bundled_profiles.json` (ids to reuse), `EmulatorProfileRepositoryTest.kt` (Robolectric asset
  access pattern).
- **Requirements:**
  - Nothing reads the asset at runtime yet.
  - The known-platform set in the test is `PlatformSeeder.DEFAULT_PLATFORMS` ids plus
    `platformAliases`.
- **Do Not Change:** `KnownEmulatorCatalog.kt`, `bundled_profiles.json`, `EmulatorDetector`.
- **Expected Files:**
  - Add: the asset; `feature/feature-launcher/src/test/.../BuiltInKnowledgeBaseTest.kt`
- **Acceptance (tests first):**
  - The asset validates with zero refusals.
  - Every catalog entry has exactly one KB entry with equal packageNames (in order), name, intent
    type, activity, extras, bool and array extras, action, flags, category, attachRomData, MIME type
    and useSafUri. The only exceptions are the three documented platform-id removals.
  - Ids are unique and match `[a-z0-9_]{2,48}`.
- **Change Budget:** 1 asset, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*BuiltInKnowledgeBaseTest*"
  ```
- **Stop Condition:** Parity is proven. Do not touch the drifted bundled entries; that is 8.1.
- **If Blocked:** If a catalog field has no schema equivalent, stop and report it. Do not widen the
  schema silently.

### Task 2.2 — Pure layer merge with provenance and package de-dup

- **Objective:** Compute the effective KB from the built-in, official and ordered user layers.
- **Parent:** Phase 2 (AD-5).
- **Scope:**
  - `EmulatorKbMerge.merge(builtIn, official?, users)` returns effective entries. Each carries its
    `source` (BUILT_IN / OFFICIAL / USER(fileId)) and the `replaced` source it overrode, if any.
  - Platform items merge the same way.
- **Existing Code:** Task 1.1 and 1.2 types. `EmulatorProfileRepository.mergeProfiles` (today's
  merge, for contrast).
- **Requirements:**
  - Override matches by id, legacyId, then package.
  - A higher layer's package claim strips that package from lower entries, and an entry with no
    packages left drops out.
  - The official layer is ignored when its `version` ≤ the built-in `version`.
  - User layers apply in import order.
- **Do Not Change:** Anything outside core-domain.
- **Expected Files:**
  - Add: `.../emulatorkb/EmulatorKbMerge.kt`
  - Test: `EmulatorKbMergeTest.kt`
- **Acceptance (tests first):**
  - An official entry overrides a built-in entry with the same id.
  - A user entry matching an official one by package overrides it, and `replaced == OFFICIAL`.
  - Two user files: the later wins.
  - A stale official file (version ≤ built-in) is ignored.
  - The package strip removes a lower entry that is left empty.
  - Platform items follow the same precedence.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*EmulatorKbMergeTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 2.3 — Knowledge store: filesDir layers, atomic writes, effective KB flow

- **Objective:** One singleton owns the layer files and publishes the effective KB.
- **Parent:** Phase 2 (AD-4, AD-13).
- **Scope:**
  - `EmulatorKnowledgeStore` in feature-launcher:
    - Loads lazily, once, behind a mutex, on `@ProfileIoDispatcher`.
    - Reads the built-in asset, `emulator_kb/official/*` and `emulator_kb/user/*` with the AD-13
      layout.
    - Validates each layer: all or nothing for built-in and official, per entry for user files.
    - Merges with 2.2 and exposes `effective: StateFlow<EffectiveKb>` plus a suspend `current()`.
  - Mutators:
    - `installOfficial(bytes, doc)`
    - `addUserFile(displayName, doc)`, which returns a file id
    - `removeUserFile(id)`
    - `resetToBuiltIn()`, which deletes the official file and all user files
  - Each write goes to a temp file and then renames.
- **Existing Code:** `EmulatorProfileRepository.kt` (dispatcher qualifier, file I/O style, Timber
  logging), `EmulatorPlatformMapping.platformAliases`, `PlatformSeeder.DEFAULT_PLATFORMS`.
- **Requirements:**
  - A corrupt or invalid official file is ignored with a warning, and the built-in layer stands.
  - A corrupt user file is skipped and its index entry is kept, marked "unreadable".
  - Nothing in this task triggers detection.
- **Do Not Change:** `EmulatorProfileRepository`, the detector, the bundled asset.
- **Expected Files:**
  - Add: `feature/feature-launcher/.../kb/EmulatorKnowledgeStore.kt`
  - Test: `EmulatorKnowledgeStoreTest.kt` (Robolectric)
- **Acceptance (tests first):**
  - Fresh install: the effective KB equals the built-in layer.
  - A valid official file overrides it.
  - A tampered official file is ignored.
  - Adding a user file survives a new store instance (restart).
  - Remove and reset delete their files.
  - A crash mid-write, simulated by leaving the temp file behind, leaves the previous file intact.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*EmulatorKnowledgeStoreTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 2.4 — Detection and refresh from the effective KB

- **Objective:** Installed emulators are detected from the effective KB, and KB changes reach
  existing auto profiles.
- **Parent:** Phase 2 (AD-5, AD-6, AD-10).
- **Scope:**
  - **`EmulatorProfile`:** add `knowledgeId` and `signerSha256` (both defaulted).
  - **`EmulatorDetector.detect`:** iterate effective entries instead of `KnownEmulatorCatalog.entries`,
    with the same first-installed-package rule and the same `autoId`. Fill the two new fields.
    RetroArch is unchanged.
    - Build the profile from `launchByPackage[installedPkg]` when present, otherwise from `launch`
      (AD-15).
  - **`EmulatorAutoConfigService`:** an auto, non-`userModified` entry whose package no longer
    appears in the effective KB is **deleted** rather than marked unavailable, because "unavailable"
    means uninstalled.
  - **`EmulatorKnowledgeRefresher.run()`:** new; it calls `runOnStartup()`. Every KB-changing flow
    calls it, and `PFPApplication.initEmulators` calls it in place of `runOnStartup()`.
- **Existing Code:** `EmulatorDetector.kt`, `EmulatorAutoConfigService.kt`, `EmulatorAutoConfigServiceTest.kt`,
  `PFPApplication.kt`.
- **Requirements:**
  - Detected profiles are identical to today's for every catalog emulator, apart from the AD-3
    platform drops.
  - `userModified` and custom entries are never touched.
- **Do Not Change:** `EmulatorProfileRepository`'s bundled loading. RetroArch logic. The ladder.
- **Expected Files:**
  - Modify: `EmulatorProfile.kt`, `EmulatorDetector.kt`, `EmulatorAutoConfigService.kt`,
    `app/.../PFPApplication.kt`
  - Add: `kb/EmulatorKnowledgeRefresher.kt`
  - Test: `EmulatorAutoConfigServiceTest.kt` (extend)
- **Acceptance (tests first):**
  - A KB entry with an installed second package yields `auto_<thatPkg>` with `knowledgeId` set.
  - An entry whose installed package has a `launchByPackage` override yields that override's intent
    type and activity. A sibling package without an override uses the base `launch`.
  - A user-layer override changes an untouched auto profile on refresh.
  - A `userModified` profile is unchanged.
  - A package dropped from the KB deletes its untouched auto profile.
  - Old persisted JSON without the new fields decodes.
- **Change Budget:** 4 modified, 1 new, 1 test. Over the 2–4 guideline: the startup call site and
  the model field are one-line changes that cannot be separated without leaving the app in a state
  where detection and refresh disagree.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*EmulatorAutoConfigServiceTest*" --tests "*EmulatorProfileRepositoryTest*" --tests "*BuiltInKnowledgeBaseTest*"
  ```
- **Stop Condition:** Acceptance is met. `KnownEmulatorCatalog` stays (8.3 deletes it).
- **If Blocked:** Stop and report.

### Task 2.5 — Launch guards: signer pinning and CUSTOM_COMMAND removal

- **Objective:** A launch cannot run a command, and it refuses an app whose signer does not match
  its recipe's pin.
- **Parent:** Phase 2 (AD-1, AD-10).
- **Scope:**
  - **`EmulatorIntentResolver`:**
    - `resolve` fails CUSTOM_COMMAND with a named message. Delete `buildCustomCommandIntent` and
      `parseAmCommand`.
    - `validateBeforeLaunch` checks `signerSha256` through an injected `SignerCheck`. The default
      uses `PackageManager.hasSigningCertificate(..., CERT_INPUT_SHA256)`.
  - **Editor:** stop offering CUSTOM_COMMAND in the `IntentType.entries` picker.
- **Existing Code:** `EmulatorIntentResolver.kt`, `EmulatorIntentResolverPreflightTest.kt`,
  `EmulatorProfileEditorScreen.kt` (L123, `intentTypeDescription`).
- **Requirements:**
  - The signer message names the emulator and says the installed app is not the expected build.
  - An empty pin list means no check.
- **Do Not Change:** The VIEW/COMPONENT intent construction, grants, other editor fields,
  `EmulatorsSettingsViewModel`.
- **Expected Files:**
  - Modify: `EmulatorIntentResolver.kt`, `EmulatorProfileEditorScreen.kt`
  - Test: `EmulatorIntentResolverPreflightTest.kt` (extend)
- **Acceptance (tests first):**
  - A pinned profile with a matching signer passes the preflight.
  - A mismatched signer fails with the signer message.
  - No pins: no check.
  - A CUSTOM_COMMAND profile fails `resolve` and no intent is built.
- **Change Budget:** 2 modified, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*EmulatorIntentResolver*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 3.1 — Ed25519 verifier with pinned key list

- **Objective:** Verify a detached signature over exact bytes against a pinned key list.
- **Parent:** Phase 3 (AD-7). Q1 approved: the Tink dependency is authorized.
- **Scope:**
  - Add the `tink-android` version to `gradle/libs.versions.toml` and to feature-launcher's
    dependencies.
  - `KbSignatureVerifier(pinnedKeys: List<ByteArray>)` exposes `verify(body, sigBase64): Boolean`.
  - The production `PINNED_KEYS` starts **empty**, which disables updates.
- **Existing Code:** `feature-launcher/build.gradle.kts`, `libs.versions.toml`.
- **Requirements:**
  - A malformed base64 or wrong-length signature returns false and never throws.
  - Keys are raw 32-byte values.
  - Only `Ed25519Verify` is used.
- **Do Not Change:** Other modules' dependencies.
- **Expected Files:**
  - Modify: `libs.versions.toml`, `feature/feature-launcher/build.gradle.kts`
  - Add: `kb/KbSignatureVerifier.kt`
  - Test: `KbSignatureVerifierTest.kt`. It generates keys and signs with the JDK's `Ed25519`.
- **Acceptance (tests first):**
  - A JDK-signed body verifies.
  - One flipped body byte fails, and so does a flipped signature byte.
  - The wrong key fails.
  - With two pinned keys, the second verifies.
  - An empty key list fails.
  - Garbage signature text fails without throwing.
- **Change Budget:** 2 modified, 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*KbSignatureVerifierTest*"
  ./gradlew :app:assembleFullRelease
  ```
- **Stop Condition:** Acceptance is met and the release build passes R8.
- **If Blocked:** If Tink's Ed25519 verify path is unavailable or fails R8, stop and report. Do not
  switch algorithms without a plan amendment.

### Task 3.2 — Bounded, qualified KB downloader (core-data)

- **Objective:** Fetch the two official assets safely and return bytes or a typed failure.
- **Parent:** Phase 3 (AD-8).
- **Scope:**
  - An `@EmulatorKbHttpClient` qualified Ktor client (OkHttp engine, timeouts, `expectSuccess=false`,
    **no Logging plugin**, no cookies).
  - `EmulatorKbDownloader.fetch(url, cap)` returns `Bytes` or `Failure(reason)`. It refuses non-HTTPS
    URLs and caps the body with `SafeMedia.readCapped` over the body stream.
  - The two URL constants (AD-8):
    `https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/emulator-kb/emulators.json`
    and the same path ending in `emulators.json.sig`.
  - No auth header and no token. The repository is public.
- **Existing Code:** `core-data/.../discord/DiscordNetworkModule.kt` (qualifier + module pattern),
  `SafeMedia.kt`, `LocalSteamHiddenDescriptions.readCapped` (same "no Content-Length" lesson).
- **Requirements:**
  - The only header sent is `User-Agent: PlayFieldPortal`.
  - Cancellation propagates.
  - Every other failure becomes a typed result.
- **Do Not Change:** `DiscordNetworkModule`, `ArtworkModule`'s client, the Steam OkHttp client.
- **Expected Files:**
  - Add: `core/core-data/.../kb/EmulatorKbNetworkModule.kt`, `.../kb/EmulatorKbDownloader.kt`
  - Test: `EmulatorKbDownloaderTest.kt` (MockEngine)
- **Acceptance (tests first):**
  - A 200 response under the cap returns the bytes.
  - A body over the cap with no Content-Length returns Failure("too large").
  - 404 and an IOException each return Failure.
  - An `http://` URL is refused before any request.
  - The request carries no query string and only the expected UA.
  - Cancellation is rethrown.
- **Change Budget:** 2 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-data:testDebugUnitTest --tests "*EmulatorKbDownloaderTest*"
  ```
- **Stop Condition:** Acceptance is met. Nothing calls the downloader yet.
- **If Blocked:** Stop and report.

### Task 3.3 — Official updater: gates, anti-rollback, install, schedule

- **Objective:** Check for, verify, gate and install an official update; record its status; run at
  most daily.
- **Parent:** Phase 3 (AD-4, AD-7, AD-8).
- **Scope:**
  - `EmulatorKbUpdater.check(manual: Boolean)` runs these steps in order:
    1. Throttle and toggle check (a manual check bypasses both).
    2. Download the body and the signature.
    3. Verify the signature.
    4. Decode.
    5. Check `format`, `schemaVersion` and `minAppVersion`.
    6. Validate, all or nothing.
    7. Check `version` ≥ the highest version accepted.
    8. `store.installOfficial`.
    9. `refresher.run()`.
    10. Persist the status: last check, result, version, label, counts.
  - The startup call goes in `PFPApplication.initEmulators`, after the refresher.
  - The `emulator_kb_*` DataStore keys are defined here. Backup is disabled, so they are **not** added
    to `BackupManager`.
- **Existing Code:** Tasks 2.3, 3.1, 3.2. `PFPApplication.appVersionCode()`. `pfpDataStore`.
  `FeatureFlags.kt` (backup is off).
- **Requirements:**
  - Every failure leaves the official file untouched and records a user-readable reason:
    offline, too large, signature invalid, needs app update, older than installed, invalid
    content.
  - The highest accepted version lives in `official/state.json`.
- **Do Not Change:** The store's validation, the verifier, the downloader, `feature-backup`.
- **Expected Files:**
  - Add: `kb/EmulatorKbUpdater.kt`
  - Modify: `PFPApplication.kt`
  - Test: `EmulatorKbUpdaterTest.kt`
- **Acceptance (tests first):**
  - A signed valid newer file installs and the refresher runs once.
  - A bad signature, a newer schema, a `minAppVersion` above the app, a lower version, and one
    invalid entry each result in no install and the matching status.
  - An equal version after a reset re-installs.
  - Automatic: within 24 h, no network call; toggle off, no call; manual bypasses both.
  - An empty pinned list returns "updates not configured" with no network call.
- **Change Budget:** 1 new, 1 modified, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*EmulatorKbUpdaterTest*"
  ```
- **Stop Condition:** Acceptance is met. No UI.
- **If Blocked:** Stop and report.

### Task 3.4 — Offline signing tool and key ceremony

- **Objective:** The user can generate a key pair, sign a release, and verify it without any
  project dependency.
- **Parent:** Phase 3 (AD-7, Appendix A).
- **Scope:**
  - `tools/emulator-kb/KbSign.java`, a single-file JDK 17 program run with `java KbSign.java …`,
    with three subcommands:
    - `keygen --out <dir>` writes a PKCS#8 private key and prints the raw public key as base64. It
      **refuses an output directory inside the git worktree.**
    - `sign --key <pem> --in emulators.json --out emulators.json.sig`
    - `verify --pub <base64> --in … --sig …`
  - `tools/emulator-kb/README.md` mirrors Appendix A.
  - After the user runs keygen **themselves** and pastes the public key into the conversation, add
    it to `KbSignatureVerifier.PINNED_KEYS`.
- **Existing Code:** `KbSignatureVerifier.kt` (3.1). `.gitignore`.
- **Requirements:**
  - The private key never enters the repo, a log or the conversation.
  - Add `*.private.pem` to `.gitignore`.
- **Do Not Change:** Any build file.
- **Expected Files:**
  - Add: `tools/emulator-kb/KbSign.java`, `tools/emulator-kb/README.md`
  - Modify: `.gitignore`, `KbSignatureVerifier.kt` (after the ceremony)
- **Acceptance (tests first):**
  - `KbSignatureVerifierTest` gains a case that verifies a fixture signed by `KbSign.java sign`
    with a throwaway test key. Commit only the fixture and the test public key.
  - Manually: keygen, then sign, then verify reports OK, and verify fails after editing one byte.
- **Change Budget:** 2 new, 2 modified.
- **Verification:**
  ```bash
  java tools/emulator-kb/KbSign.java keygen --out /path/outside/repo
  java tools/emulator-kb/KbSign.java sign --key /path/outside/repo/pfp-kb.private.pem --in feature/feature-launcher/src/main/assets/emulator_kb/emulators.json --out /tmp/emulators.json.sig
  java tools/emulator-kb/KbSign.java verify --pub <BASE64> --in feature/feature-launcher/src/main/assets/emulator_kb/emulators.json --sig /tmp/emulators.json.sig
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*KbSignatureVerifierTest*"
  ```
- **Stop Condition:** The tool works and the real public key is pinned.
- **If Blocked:** If the user has not run keygen, stop after the tool and README, and leave
  `PINNED_KEYS` empty.

### Task 4.1 — Additive extension planner with user-edit guard

- **Objective:** Decide, as a pure function, which platform rows and cards receive which
  extensions.
- **Parent:** Phase 4 (AD-9).
- **Scope:**
  - `PlatformExtensionPlanner.plan(seedDefault, lastApplied?, kbList, platformCurrent, cards)`
    returns the new platform value or none, the per-card new values or none, the new `lastApplied`,
    and the added tokens.
- **Existing Code:** `PlatformEntity.romExtensions` / `MemoryCardEntity.supportedExtensions` (CSV,
  lowercase, no dots). `MIGRATION_48_49`'s KDoc, which states the guard rule.
- **Requirements:**
  - The target is `current ∪ kbList`, additive only.
  - A row is "untouched" when its current set equals `lastApplied`, `seedDefault`, or the target
    (idempotent).
  - Order is preserved and new tokens are appended.
  - Comparison is case-insensitive and ignores order.
- **Do Not Change:** Anything outside core-domain.
- **Expected Files:**
  - Add: `.../emulatorkb/PlatformExtensionPlanner.kt`
  - Test: `PlatformExtensionPlannerTest.kt`
- **Acceptance (tests first):**
  - An untouched card gains `chd`.
  - A card where the user removed `zip` is left alone.
  - A card where the user added `7z` is left alone.
  - A KB list missing a current token removes nothing.
  - Re-running after a partial apply is idempotent.
  - A card equal to the seed default after a restore counts as untouched.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*PlatformExtensionPlannerTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 4.2 — Platform knowledge applier and refresh wiring

- **Objective:** Apply the effective KB's platform items to Room and record what was applied.
- **Parent:** Phase 4 (AD-9).
- **Scope:**
  - Add `PlatformDao.setRomExtensions(id, csv)`.
  - New `PlatformKnowledgeApplier` (core-data). In one Room transaction it plans each platform with
    4.1, writes the platform and its card, and saves `filesDir/emulator_kb/platform_applied.json`.
    It returns the list of consoles that gained extensions.
  - `EmulatorKnowledgeRefresher.run()` calls the applier, then auto-config, and keeps the
    "gained file types" list for the status card.
- **Existing Code:** `PlatformDao.kt`, `MemoryCardDao.setSupportedExtensions`, `PlatformSeeder.DEFAULT_PLATFORMS`,
  `core-data` DAO Robolectric tests (e.g. `AchievementDaoTest.kt`).
- **Requirements:**
  - No rescan is triggered.
  - Unknown platform ids are skipped.
  - A Room failure leaves `platform_applied.json` unchanged.
- **Do Not Change:** `PlatformSeeder`, the migrations, the scanners.
- **Expected Files:**
  - Modify: `PlatformDao.kt`, `kb/EmulatorKnowledgeRefresher.kt`
  - Add: `core-data/.../kb/PlatformKnowledgeApplier.kt`
  - Test: `PlatformKnowledgeApplierTest.kt` (Robolectric, in-memory Room)
- **Acceptance (tests first):**
  - A seeded psx platform and an untouched card both gain `chd`.
  - A customized card does not.
  - A second run makes no writes.
  - The returned list names psx.
- **Change Budget:** 2 modified, 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-data:testDebugUnitTest --tests "*PlatformKnowledgeApplierTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 5.1 — Emulator knowledge ViewModel

- **Objective:** State and actions for the approved status screen.
- **Parent:** Phase 5.
- **Scope:**
  - `EmulatorKnowledgeViewModel` (feature-settings) exposes:
    - status (source Built-in or Official, label, verified, emulator count, last check time and
      result, consoles that gained file types)
    - `checkNow()` and the auto-update toggle
    - the "Your files" list (name, entry count, unsigned, unreadable flag)
    - `removeFile(id)`, one step, followed by a refresh
    - reset to built-in, behind a `PfpModalSpec.Confirm`, followed by a refresh
- **Existing Code:** `EmulatorsSettingsViewModel.kt` (state + Hilt + test style), Tasks 2.3, 3.3, 4.2.
- **Requirements:**
  - Status text follows the mockup, e.g. "Official v2026.10.02 · verified · 87 emulators · checked
    today 09:14".
  - Updates-not-configured shows as "Built-in · updates not configured".
- **Do Not Change:** `EmulatorsSettingsViewModel`.
- **Expected Files:**
  - Add: `feature-settings/.../viewmodel/EmulatorKnowledgeViewModel.kt`
  - Test: `EmulatorKnowledgeViewModelTest.kt`
- **Acceptance (tests first):**
  - Status maps each updater result to its text.
  - A manual check shows progress, then the result.
  - The toggle persists.
  - Remove calls the store and then the refresher.
  - Reset requires the confirm.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-settings:testDebugUnitTest --tests "*EmulatorKnowledgeViewModelTest*"
  ```
- **Stop Condition:** Acceptance is met. No import or export yet.
- **If Blocked:** Stop and report.

### Task 5.2 — Emulator knowledge screen, route and L2 entry

- **Objective:** Settings › Emulators › Emulator knowledge, as in the approved mockup.
- **Parent:** Phase 5.
- **Scope:**
  - `EmulatorKnowledgeScreen` on `SettingsScaffold`, with:
    - a status group (`SettingsValueRow` / hint)
    - Check for updates (`SettingsRow`)
    - Automatic updates (`SettingsToggleRow`)
    - an Import row and an Export row, which stay disabled (`enabled=false`) until 6.2 and 7.2
    - Your files: `SettingsRow` with a "Custom · unsigned" sublabel and a Remove `SettingsRowAction`
    - Reset to built-in
  - Route `settings_emulators_knowledge` in `SETTINGS_SCREEN_ROUTES` and in the `when`.
  - An L2 item in `settingsSectionItems(EMULATORS)` after Per-System Defaults.
- **Existing Code:** `EmulatorsSettingsScreen.kt`, `SettingsScaffold.kt` (rows), `SettingsNavHost.kt`,
  `XMBViewModel.kt` L598–607, `SettingsHierarchyTest.kt`.
- **Requirements:**
  - Controller navigation comes from the scaffold, with no custom focus code.
  - Every row has a stable `focusKey`.
- **Do Not Change:** `SettingsScaffold.kt`, other screens, other L2 items.
- **Expected Files:**
  - Add: `ui/EmulatorKnowledgeScreen.kt`
  - Modify: `SettingsNavHost.kt`, `XMBViewModel.kt`
  - Test: `SettingsHierarchyTest.kt` (extend)
- **Acceptance (tests first):**
  - The hierarchy test expects the new L2 id and the route set includes it.
  - On device: every row is reachable with the D-pad, Remove is reachable with RIGHT, and Back
    returns to the L2 list.
- **Change Budget:** 1 new, 2 modified, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-xmb:testDebugUnitTest --tests "*SettingsHierarchyTest*" :feature:feature-settings:testDebugUnitTest
  ```
  Then a device check (the user drives).
- **Stop Condition:** The screen matches the mockup minus the import and export flows.
- **If Blocked:** Stop and report.

### Task 6.1 — Import review planner with per-entry selection

- **Objective:** Build the per-entry review for a user file, and apply exactly the chosen entries.
- **Parent:** Phase 6 (AD-11).
- **Scope:**
  - `EmulatorKbImportPlan.build(effective, validated, signerProbe: (pkg, pins) -> SignerState)`
    returns items:
    - `New(entry, selected=true)`
    - `Change(entry, current, diff, overridesOfficial, userEdited, selected=false)`
    - `Unchanged(entry)`, shown but not selectable
    - `Blocked(id?, name?, reason)`
    - `PlatformUpdate(id, addedExtensions, selected=false)`
  - `toggle(key)`
  - `selectedDocument()` returns a document with only the selected emulators and platform items.
- **Existing Code:** Tasks 1.2 and 2.2 (matching by id, legacyId, package).
- **Requirements:**
  - The diff lists changed launch fields as before → after: intent type, activity, action, extras,
    flags, MIME type, URI mode, packages, platforms, and per-package overrides (AD-15).
  - `userEdited` is true when a `userModified` profile exists for that knowledge id, so the UI can
    note that the user's edits still win.
  - A signer mismatch on an installed package is Blocked.
  - `selectedCount` counts selected emulators only.
- **Do Not Change:** The validator, merge and store.
- **Expected Files:**
  - Add: `.../emulatorkb/EmulatorKbImportPlan.kt`
  - Test: `EmulatorKbImportPlanTest.kt`
- **Acceptance (tests first):**
  - **Coordinator cases:**
    - An existing entry with Override declined: the selected document omits it, and merging the
      result leaves the current entry untouched.
    - A new entry with Add declined adds nothing.
    - A mixed selection yields exactly the chosen entries.
  - **Also:**
    - Defaults are New = checked, Change and PlatformUpdate = unchecked.
    - `overridesOfficial` is true against OFFICIAL and BUILT_IN.
    - A signer mismatch is Blocked.
    - An identical entry is Unchanged.
    - The diff names exactly the changed fields.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*EmulatorKbImportPlanTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 6.2 — Import flow (SAF read, review state, selective apply)

- **Objective:** Import a picked file, hold the review state, and apply the selection.
- **Parent:** Phase 6 (AD-11, AD-13).
- **Scope:**
  - In `EmulatorKnowledgeViewModel`:
    - `importFrom(uri)` reads with `SafeMedia.readCapped(1 MiB)` and decodes, validates and plans.
      The review state goes into UI state.
    - `toggleReviewItem`, `cancelImport`.
    - `confirmImport()` → `store.addUserFile(displayName, selectedDocument)` → `refresher.run()`.
  - A launcher-side `SignerProbe` that uses `PackageManager`.
  - The Import row is enabled and opens `OpenDocument(arrayOf("application/json", "text/plain", "application/octet-stream"))`.
- **Existing Code:** `ArtworkSettingsViewModel` L371 / `DisplaySettingsViewModel` L550 (SAF reads),
  `BackupSettingsScreen.kt` L39 (picker), Task 6.1.
- **Requirements:**
  - Oversize, unreadable and invalid files end in a Notice modal with the reason and are never
    stored.
  - Nothing is stored when the selection is empty; confirm is disabled.
  - The file display name comes from `OpenableColumns.DISPLAY_NAME` and is capped at 64 characters.
- **Do Not Change:** The store API (2.3), the screen layout beyond enabling Import.
- **Expected Files:**
  - Modify: `EmulatorKnowledgeViewModel.kt`, `EmulatorKnowledgeScreen.kt`
  - Add: `feature-launcher/.../kb/SignerProbe.kt`
  - Test: `EmulatorKnowledgeViewModelTest.kt` (extend)
- **Acceptance (tests first):**
  - A valid file opens a review with the expected defaults.
  - Confirm stores only the selected entries and runs the refresher.
  - Cancel stores nothing.
  - An oversize file shows "too large" and stores nothing.
  - An empty selection disables confirm.
- **Change Budget:** 2 modified, 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-settings:testDebugUnitTest --tests "*EmulatorKnowledgeViewModelTest*"
  ```
- **Stop Condition:** Acceptance is met. A minimal list is acceptable until 6.3.
- **If Blocked:** Stop and report.

### Task 6.3 — Import review screen

- **Objective:** The review screen with per-entry choices, as approved in the updated mockup.
- **Parent:** Phase 6 (AD-11). **Gate:** Q3 (sign-off of the updated mockup).
- **Scope:**
  - A sub-screen on `SettingsScaffold` with:
    - An unsigned-file warning hint.
    - **New**: a `SettingsToggleRow` "Add?" per entry.
    - **Changes**: a `SettingsToggleRow` "Override?" per entry. The sublabel carries the diff lines
      and "Overrides official" or "Your edits still apply" where they apply.
    - **Platform updates**: a "Update?" toggle per platform, with the added extensions.
    - **Blocked**: a plain `SettingsRow` with the reason and no click.
    - Cancel, and "Import N emulators", disabled at N = 0.
  - Confirm on a row toggles it.
- **Existing Code:** `SettingsToggleRow`, `SettingsGroup`, `EmulatorsSettingsScreen.TestLaunchFlow`
  (sub-screen pattern).
- **Requirements:**
  - Reuse the existing row components. Do not modify `SettingsScaffold.kt`. Badges go in the
    sublabel.
  - Long diffs are capped at 4 lines with "+n more".
- **Do Not Change:** `SettingsScaffold.kt`, `PfpModalHost`.
- **Expected Files:**
  - Modify: `EmulatorKnowledgeScreen.kt`
  - Add: `ui/EmulatorKnowledgeReview.kt` (optional split)
- **Acceptance:**
  - The UI composes from 6.2's tested state, which holds the behaviour.
  - On device: every toggle flips with Confirm, the button count follows, Blocked rows are not
    actionable, and Back cancels.
- **Change Budget:** 1 modified, 1 new.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-settings:testDebugUnitTest
  ./gradlew :app:installFullDebug
  ```
  Then a device check (the user drives).
- **Stop Condition:** Matches the signed-off mockup.
- **If Blocked:** Stop and report.

### Task 7.1 — Export builder

- **Objective:** Turn chosen profiles into a valid, shareable KB document.
- **Parent:** Phase 7 (AD-12).
- **Scope:**
  - `EmulatorKbExport.candidates(persisted)` returns the eligible profiles.
  - `EmulatorKbExport.build(selected, knownPlatformIds, selfPackage)` returns the document and a
    can't-share list with reasons.
- **Existing Code:** `EmulatorProfile.kt`, `EmulatorProfileRepository.getAllPersistedProfiles`, Task 1.2.
- **Requirements:**
  - Eligible profiles are `isCustom || userModified`, excluding RetroArch / `coreMap`, SHORTCUT and
    CUSTOM_COMMAND.
  - The id is `knowledgeId`, or else a slug of the name with `_2`, `_3` on collision.
  - Output carries schema fields only.
  - The output is re-validated, and failing entries are moved to can't-share.
- **Do Not Change:** The repository.
- **Expected Files:**
  - Add: `.../emulatorkb/EmulatorKbExport.kt`
  - Test: `EmulatorKbExportTest.kt`
- **Acceptance (tests first):**
  - Untouched auto profiles and RetroArch profiles are not candidates.
  - Notes and timestamps are absent from the output JSON.
  - A profile with literal extra `"/storage/emulated/0/x"` lands in can't-share and the path is
    absent from the output.
  - Id collisions are suffixed.
  - The output round-trips through the decoder and validator with zero refusals.
- **Change Budget:** 1 new, 1 test.
- **Verification:**
  ```bash
  ./gradlew :core:core-domain:testDebugUnitTest --tests "*EmulatorKbExportTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 7.2 — Export picker and SAF write

- **Objective:** The user picks which custom emulators to share, all checked by default, and saves
  one JSON file.
- **Parent:** Phase 7. **Gate:** Q3 (mockup for the picker).
- **Scope:**
  - The Export row opens a sub-screen: a `SettingsToggleRow` per candidate (default on), a
    can't-share group, and "Export N emulators", which launches `CreateDocument("application/json")`
    with the suggested name `pfp-emulators.json`.
  - The ViewModel writes the bytes through `contentResolver.openOutputStream` and reports the
    result with a Notice.
- **Existing Code:** Tasks 5.2 and 7.1, `EmulatorProfileRepository.getAllPersistedProfiles`.
- **Requirements:**
  - Write nothing at N = 0.
  - A write failure shows a Notice, and the partial document is deleted where possible.
- **Do Not Change:** The import flow.
- **Expected Files:**
  - Modify: `EmulatorKnowledgeViewModel.kt`, `EmulatorKnowledgeScreen.kt`
  - Test: `EmulatorKnowledgeViewModelTest.kt` (extend)
- **Acceptance (tests first):**
  - Defaults are all selected.
  - Deselecting one excludes it from the written bytes.
  - N = 0 disables export.
  - The written bytes decode and validate.
  - On device: export, then re-import on the same device. The review shows every exported entry as
    Unchanged or Change, and none as Blocked.
- **Change Budget:** 2 modified, 1 test.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-settings:testDebugUnitTest --tests "*EmulatorKnowledgeViewModelTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 8.1 — Drift audit and built-in KB reconciliation

- **Objective:** Fold `bundled_profiles.json` into the built-in KB: resolve each drift, and add every
  bundled id as a `legacyId`.
- **Parent:** Phase 8 (AD-15). Q4a and Q4b are answered. Q4c is open and blocks only the
  winlator/gamehub row.
- **Scope:**
  - **Default rule: the catalog recipe wins.** It is what auto-detection launches today for every
    user who has the emulator installed, so it is the field-tested one.
  - Drift found while planning:

    | Bundled id | Bundled | Catalog | Resolution |
    |---|---|---|---|
    | `dolphin`, `duckstation`, `nethersx2` | `{rom_path}` extra | `{rom_uri}` extra | Catalog |
    | `eden` | ACTION_VIEW | COMPONENT + TECH_DISCOVERED + attachRomData | Catalog |
    | `azaharplus` | MIME, no flags | CLEAR_TASK/CLEAR_TOP, no MIME | Catalog |
    | `citra` | package `org.citra_emu.citra` | `org.citra.citra_emu` (+ canary) | One entry: add `org.citra_emu.citra` as a third package (Q4a). If its bundled recipe differs from the catalog's beyond the package name, add a `launchByPackage` override for it (AD-15). |
    | `sudachi` | `org.sudachi.android`, ACTION_VIEW | `org.sudachi.sudachi_emu` (+ ea), COMPONENT | One entry with all three packages (Q4b). Catalog COMPONENT recipe as `launch`, plus `launchByPackage["org.sudachi.android"]` = the bundled ACTION_VIEW recipe (AD-15). |
    | `winlator`, `gamehub` | SHORTCUT | none (PC launcher subsystem) | **Open (Q4c).** Leave them out of the KB and keep `bundled_profiles.json` loading them until Q4c is answered. Do not drop them. |
    | `ppsspp_gold`, `flycast_gles2`, `m64pfz_pro`, `m64pfz` | separate profiles | merged multi-package entries | `legacyIds` on the merged entry |

  - Re-diff every remaining bundled entry field by field, and list any further drift in the task
    report.
  - Device check, which the user drives: launch one game with each Sudachi build and each Citra
    build that is available, to confirm each recipe.
- **Existing Code:** `bundled_profiles.json`, the built-in asset, `BuiltInKnowledgeBaseTest.kt`.
- **Requirements:**
  - Every one of the 38 bundled ids appears exactly once as an entry id or a `legacyId`, or is
    listed as held back pending Q4c (`winlator`, `gamehub`).
  - Any drift outside this table: stop and ask. Do not guess.
- **Do Not Change:** Runtime loading. `bundled_profiles.json` stays until 8.3.
- **Depends on:** AD-15 support in 1.1 (decode), 1.2 (validate) and 2.4 (detection picks the
  override).
- **Expected Files:**
  - Modify: the built-in asset, `BuiltInKnowledgeBaseTest.kt`
- **Acceptance (tests first):**
  - The test asserts the 38-id coverage and the table's resolutions.
  - Sudachi has one entry with three packages and an ACTION_VIEW override for `org.sudachi.android`.
  - Citra has one entry that includes `org.citra_emu.citra`.
  - Parity with the catalog still holds, except for the approved additions.
- **Change Budget:** 2 modified.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*BuiltInKnowledgeBaseTest*"
  ```
- **Stop Condition:** The table is resolved and approved.
- **If Blocked:** Stop on any unlisted drift.

### Task 8.2 — Legacy id rewrite for retired bundled ids

- **Objective:** Stored references to a bundled id keep resolving after bundled profiles retire.
- **Parent:** Phase 8 (AD-6).
- **Scope:**
  - Add DAO update queries: `GameDao.renameEmulatorRef(old, new)`,
    `MemoryCardDao.renameEmulatorId(old, new)`, `PlatformDao.renamePreferredEmulator(old, new)`.
  - `LegacyEmulatorIdRewriter.run(map, keepIds)` runs in one transaction. `map` is legacy id →
    `auto_<pkg>` of the entry's first package, or of the installed package when one is installed.
    Ids in `keepIds` (persisted profile ids) are skipped.
  - The refresher calls it on every run.
- **Existing Code:** `GameDao.kt` (`emulator_package`), `MemoryCardDao.setEmulator`,
  `PlatformDao.setPreferredEmulator`, `EmulatorLaunchResolver` (id-then-package matching),
  `LibraryConsolidation` (startup data-fix precedent).
- **Requirements:**
  - Idempotent: a second run makes no writes.
  - `winlator` and `gamehub` are not in the map while Q4c is open; their profiles still exist.
  - A legacy id that a user-edited persisted profile still holds is never rewritten.
- **Do Not Change:** The Room schema version, migrations, the ladder.
- **Expected Files:**
  - Modify: `GameDao.kt`, `MemoryCardDao.kt`, `PlatformDao.kt`, `EmulatorKnowledgeRefresher.kt`
  - Add: `core-data/.../kb/LegacyEmulatorIdRewriter.kt`
  - Test: `LegacyEmulatorIdRewriterTest.kt` (Robolectric Room)
- **Acceptance (tests first):**
  - A card holding `duckstation` becomes `auto_com_github_stenzek_duckstation`.
  - A game override holding `ppsspp_gold` is rewritten.
  - A kept id is untouched.
  - A second run makes no changes.
  - Unrelated values are untouched.
- **Change Budget:** 4 modified, 1 new, 1 test. Over budget: each DAO gains a single query, and the
  three tables must move together.
- **Verification:**
  ```bash
  ./gradlew :core:core-data:testDebugUnitTest --tests "*LegacyEmulatorIdRewriterTest*"
  ```
- **Stop Condition:** Acceptance is met.
- **If Blocked:** Stop and report.

### Task 8.3 — Retire `bundled_profiles.json` and `KnownEmulatorCatalog`

- **Objective:** The built-in KB is the only emulator knowledge in the APK.
- **Parent:** Phase 8.
- **Scope:**
  - `EmulatorProfileRepository` stops loading bundled profiles. `initialize`, `mergeProfiles` and
    `resetPersistedProfiles` operate on persisted profiles only.
  - The settings "Available (Not Installed)" list is built from effective KB entries with no
    installed package.
  - Delete `KnownEmulatorCatalog.kt`.
  - Delete `bundled_profiles.json` only if Q4c has been answered "drop". Otherwise trim it to the
    `winlator` and `gamehub` entries and keep loading just those.
  - Rename `KnownEmulatorCatalogTest` to `KnowledgeBaseInvariantsTest` over the built-in asset,
    keeping every invariant: one package per entry, every package tagged in `KnownEmulatorPackages`,
    COMPONENT pins an activity, COMPONENT delivers the ROM, and the X360 package id.
- **Existing Code:** `EmulatorProfileRepository.kt`, `EmulatorsSettingsViewModel.observeProfiles`,
  `KnownEmulatorCatalogTest.kt`, `EmulatorProfileRepositoryTest.kt`.
- **Requirements:**
  - After this task, no code references the catalog or the bundled asset (`rg` is clean).
  - Device check: launch DuckStation, Dolphin and Eden; open Per-System Defaults; existing choices
    still show.
- **Do Not Change:** RetroArch, `PcLauncherCatalog`, `KnownEmulatorPackages` contents.
- **Expected Files:**
  - Modify: `EmulatorProfileRepository.kt`, `EmulatorsSettingsViewModel.kt`
  - Delete: 2 files
  - Test: rename and extend `KnownEmulatorCatalogTest.kt`, update `EmulatorProfileRepositoryTest.kt`
- **Acceptance (tests first):**
  - The invariants test passes on the asset.
  - The repository test proves that bundled profiles are gone (except `winlator` and `gamehub` while
    Q4c is open) and that persisted ones are intact.
  - The available list comes from the KB.
- **Change Budget:** 2 modified, 2 deleted, 2 tests. The deletions are the point of the task.
- **Verification:**
  ```bash
  ./gradlew :feature:feature-launcher:testDebugUnitTest :feature:feature-settings:testDebugUnitTest :core:core-domain:testDebugUnitTest
  ./gradlew :app:installFullDebug
  ```
- **Stop Condition:** Acceptance is met and the device check passes.
- **If Blocked:** Stop and report.

---

## Appendix A — Release Process

**One-time key ceremony (the user does this on their own machine, offline if possible):**

1. Generate the key pair:
   ```bash
   java tools/emulator-kb/KbSign.java keygen --out "D:/pfp-kb-keys"
   ```
2. Store `pfp-kb.private.pem` outside the repo, in an encrypted location, with one offline backup
   (for example a password manager attachment and an encrypted USB drive). Never commit it, paste
   it, or upload it.
3. Give the printed **public** key (base64) to the implementer. It goes into
   `KbSignatureVerifier.PINNED_KEYS` and ships in an app release. Updates work only on app versions
   that carry it.

**Each knowledge release:**

1. Edit `feature/feature-launcher/src/main/assets/emulator_kb/emulators.json`, which is the single
   source. Bump `version` (format `YYYYMMDDnn`, strictly greater than the last published) and
   `label` (`YYYY.MM.DD`). Raise `minAppVersion` if the file uses anything an older app cannot read.
2. Validate:
   ```bash
   ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*BuiltInKnowledgeBaseTest*" --tests "*KnowledgeBaseInvariantsTest*"
   ```
3. Sign and verify:
   ```bash
   java tools/emulator-kb/KbSign.java sign --key "D:/pfp-kb-keys/pfp-kb.private.pem" --in feature/feature-launcher/src/main/assets/emulator_kb/emulators.json --out emulators.json.sig
   java tools/emulator-kb/KbSign.java verify --pub <PUBLIC_KEY_BASE64> --in feature/feature-launcher/src/main/assets/emulator_kb/emulators.json --sig emulators.json.sig
   ```
4. Upload both files to the dedicated rolling release `emulator-kb` on the public repo
   `JohnnyCollado/PlayFieldPortal`. Before the first publish, create it once:
   `gh release create emulator-kb --title "Emulator knowledge" --notes "Signed emulator knowledge base"`.
   Then on each release:
   ```bash
   gh release upload emulator-kb feature/feature-launcher/src/main/assets/emulator_kb/emulators.json emulators.json.sig --clobber
   ```
   Between the two uploads, a client can fetch a mismatched pair. It fails verification, keeps its
   last good copy, and succeeds on the next check.
5. On a device: Settings › Emulators › Emulator knowledge › Check for updates. The status shows the
   new label, marked verified.

**Key rotation:** Ship the new public key alongside the old one in an app release. Once enough users
have that release, sign with the new key. Remove the old key in a later release.

**Key compromise:** Ship an app release without the compromised key, and re-sign with a new one. An
attacker holding the key can publish a higher `version` until users update. The damage is bounded by
AD-1, AD-2 and AD-10: the attacker can change which listed emulator a ROM opens in, but cannot run a
command, target system packages, pass a path or URI literal, or grant more than read access to the
one ROM.

## Open Risks

- **GitHub access.** Unauthenticated release-asset downloads are not covered by the REST API's
  60/hour limit, but github.com can still throttle or block. Checks run once a day per device and
  failures are silent, keeping the last good copy. The repository is public (Q2). If it ever goes
  private, every update check fails, with the last good copy kept.
- **The single offline key is a single point of failure.** Losing it means shipping a new key in an
  app update; there is no recovery without one.
- **Diff readability.** Recipe diffs are technical. The review shows plain field names, but a user
  can still approve a harmful *change of emulator*. The deny list and data-only rules bound that.
- **Hidden users of SHORTCUT bundled profiles** (`winlator`, `gamehub`). They stay until Q4c is
  answered, so 8.3 cannot delete `bundled_profiles.json` outright until then (see 8.3).
- **Uncommitted edits.** `SettingsNavHost.kt` and parts of `feature-xmb` had uncommitted user
  edits at planning time. Expect merge friction in 5.2.

## Open Questions (need the user)

**Answered 2026-10-02:**
- **Q1:** Tink approved.
- **Q2:** The repo is public; the `emulator-kb` rolling tag is approved.
- **Q3:** An updated mockup is coming; 6.3 and 7.2 stay gated on it.
- **Q4a/b:** Keep both Citra and Sudachi packages on one entry (AD-15).
- **Q5:** Keep the KB out of backups. Backup is disabled pending a rework.

**Still open:**
- **Q4c — SHORTCUT profiles.** May the bundled `winlator` and `gamehub` profiles be dropped? Until
  this is answered, 8.1 leaves them out of the KB, and 8.3 keeps a minimal loader for those two
  entries instead of deleting `bundled_profiles.json`.
