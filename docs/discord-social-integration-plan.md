# Discord Social Integration — Technical Plan

**SDK:** Discord Social SDK 1.9.17379 · Branch: `discord-integration`
**Target features (v1):** Activity Status (presence), Voice chat, Friends list — real Discord OAuth (device grant / QR). Text chat, Servers, DMs deferred.

---

## 0. Status & how to resume (read this first)

**Done & verified on-device (AYN Thor, Android 13). Commits on `discord-integration`, not pushed:**
- **M0** — `:discord:discord-native` NDK module; SDK aars vendored via **Git LFS**; prefab/CMake; JNI bridge links the SDK (arm64-v8a + armeabi-v7a).
- **M1** — QR login (OAuth2 **device grant**, endpoint `/oauth2/device/authorize`); **Keystore AES-256-GCM** encrypted token store; native session lifecycle (`UpdateToken`→`Connect`, `RunCallbacks` pump); session restore on launch. Client ID `1522836772847878216` in `DiscordConfig` (public; **"Public Client" must be ON** in the portal OAuth2 tab).
- **M2** — **Social XMB column** (Groups glyph `catbar_social`); **in-XMB sibling drill** (`SocialNav` Root→Account→Friends/ActivitySettings/DiscordSettings, mirrors Music/Video/Photo two-pane flyout + `computeDrillTitle`/`computeDrillSiblings`/`isInSubItem`/back-pop); account row shows real **avatar + username** + **Reconnect** options menu (Y/△); **Friends** list with presence **colored dot in the subtext** + live "N online" hub count + **PFP-scoped activity** (`GameActivity`); **offline resilience** (`NetworkMonitor`); **refresh-token exchange** on session restore; **Discord Settings** drill (Sign Out lives here); **Activity Settings** = opt-in `UpdateRichPresence` broadcast (`DiscordPresenceController`, default OFF + generic mode, native `setActivity`/`clearActivity`); debug-menu test entry retired. **Per-game presence title** — while a game runs the presence shows *that game's* title ("Playing <title>"); back in the launcher it reverts to the app name; generic mode still collapses everything to "a game". Current game is held in-memory only and cleared on `MainActivity.onResume`; it's set at every launch path — ROM/native-game via `GameDetailViewModel.sendLaunchIntent`, direct XMB app-row launch (`appCategoryRepository.launch` call site), the `AppDetailViewModel.launchApp` button, and the **App Drawer** (`AppDrawerViewModel.launchApp`) — so apps broadcast the same as ROMs. Titles are sanitized (control/RTL-override strip + 128-char clamp) in `DiscordPresenceController` before the wire (unit-tested, 8 tests).

**Remaining:** a final **on-device verification pass** with a 2nd Discord account (voice call audio, invites, presence visibility) — the voice foreground service is provided by the SDK aar (`com.discord.socialsdk.ForegroundService`, `microphone` type). The §12 security checklist is code-reviewed complete. Seeing your broadcast presence needs a **2nd Discord account**; friend "activity" is SDK-scoped to **this app only** — not general Discord presence.

### Resuming on a new machine
1. `git clone` + `git lfs pull` (the SDK aars live in Git LFS under `discord/discord-native/libs/`; the **debug aar / source zip are NOT committed** — get them from the original SDK zip if needed).
2. Install NDK + CMake: `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"` (pinned in `discord/discord-native/build.gradle.kts`).
   - **If `sdkmanager`/Gradle downloads fail on SSL** (e.g. antivirus HTTPS interception), export `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` — mirrors `gradle.properties`.
3. In the Discord Developer Portal → your app → **OAuth2 → enable "Public Client"** (device grant fails otherwise).
4. Build/install debug: `./gradlew :app:installDebug`. On-device test entry also lives in the **debug menu** (long-press Settings → "DISCORD (TEST)") as a fallback.
5. Architecture map + design details: the rest of this doc, esp. §4 (modules) and §10 (Social section).

---

## 1. Guiding principles

- **Opt-in and inert by default.** The SDK is not initialized, no token exists, no mic is
  touched, and no network traffic to Discord occurs until the user explicitly connects
  Discord from the Social section.
- **We hold the tokens → we encrypt them.** The auth flow hands the app the access +
  refresh tokens. These are the crown-jewel secrets and are stored encrypted at rest via
  the Android Keystore.
- **Never weaken transport.** The SDK already uses TLS/WSS (control) and DTLS-SRTP (voice).
  We add app-level cleartext blocking and never open a side channel to Discord.
- **Untrusted in, sanitized out.** All inbound content (usernames, activity text, avatars)
  is treated as untrusted; all outbound presence is sanitized and length-clamped.
- **Data minimization.** Request the minimum OAuth scopes; persist only what session
  restore requires.

## 2. SDK facts that shape the design

- The `.aar` Java classes are internal glue: `NativeCalls` (native→Java), `AuthenticationActivity`
  (Custom Tabs OAuth, **PKCE, no client secret**), `DiscordAudioManager`, `ForegroundService`.
- The real API is C++ in `discordpp.h` (`Client`, `Activity`, `Call`, `RelationshipHandle`,
  `MessageHandle`, `LobbyHandle`). **→ An NDK/C++ wrapper with a JNI-exposed Kotlin API is required.**
- Auth: `CreateAuthorizationCodeVerifier()` → challenge/verifier; `Authorize()` → code;
  `GetToken()` → access + refresh + expiry. Restore via `UpdateToken()`.
  Refresh via `SetTokenExpirationCallback`.
- Event-driven: `discordpp::RunCallbacks()` must be pumped on a dedicated thread.
- `minSdkVersion 21` in the aar (compatible with app minSdk 29).
- Manifest merge adds: `INTERNET`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH`/`BLUETOOTH_CONNECT`.

## 3. Decisions locked

| Decision | Choice |
|---|---|
| ABIs | `arm64-v8a` + `armeabi-v7a` |
| Voice noise-cancel | Include Krisp (`discord_partner_sdk_krisp.aar` + `.kef` models) |
| SDK binary storage | Git LFS for release `.aar`; debug aar + zip gitignored |
| Presence default | Opt-in, default **OFF**, with generic "Playing a game" mode |

## 4. Module layout

Fits the existing `core/` + `feature/` clean-architecture split; the native integration is
isolated and optional.

- **`:discord:discord-native`** — vendored `.aar`(s) via Git LFS, CMake/C++ wrapper around
  `discordpp`, JNI, `RunCallbacks` pump thread, and an internal `DiscordNativeBridge`.
  All native concerns live here and nowhere else.
- **`:core:core-domain`** — social domain models (`SocialUser`, `Friend`, `PresenceActivity`,
  `VoiceCallState`) and the `DiscordSocialRepository` interface. No Android/native deps.
- **`:core:core-data`** — `DiscordSocialRepositoryImpl` (maps native callbacks → Kotlin
  `Flow`), the **encrypted token store**, and Hilt bindings.
- **`:feature:feature-social`** — Compose XMB Social section, ViewModel, consent/permission
  flows, controller-first UX (reuses `XmbHelpBar` / `ButtonPrompt`).

## 5. Native bridge (`:discord:discord-native`)

- `externalNativeBuild` (CMake) + `buildFeatures.prefab = true` to consume the
  `discord_partner_sdk` prefab module. Link `libwebrtc` + Krisp.
- `ndk { abiFilters += ["arm64-v8a", "armeabi-v7a"] }`.
- `DiscordBridge.cpp`: owns the `discordpp::Client`, wires status/log callbacks, pumps
  `RunCallbacks()` on a dedicated thread whose lifecycle is tied to the connected session.
- JNI surface (minimal, purpose-built): `initClient`, `authorize`, `restoreSession(token)`,
  `logout`, `setActivity`, `clearActivity`, `observeRelationships`, `startVoice`,
  `joinCall`, `setMute`, `leaveVoice`, `shutdown`.
- Callbacks C++→Kotlin via a registered listener object → repository emits to `Flow`.

## 6. Auth & token security (core requirement)

1. **PKCE flow** (public client, no secret in the APK): verifier/challenge → `Authorize`
   (opens `AuthenticationActivity` Custom Tab) → `GetToken` → tokens.
2. **Encrypted at rest:** generate an AES-256-GCM key in the **Android Keystore**
   (StrongBox when available; key never leaves the TEE). Encrypt the token blob
   (access, refresh, expiry, scopes, Discord user id) and store ciphertext in DataStore.
   Nothing else is persisted.
3. **Session restore:** on launch, decrypt → `UpdateToken`. On refresh failure/expiry, use
   `SetTokenExpirationCallback`; on hard failure, wipe and require re-auth.
4. **Secure logout:** wipe token blob **and** delete the Keystore key; clear SDK session.
5. **Redirect URI:** dedicated custom scheme / verified App Link; validated strictly on
   callback.

## 7. Network & transport hardening

- `network-security-config` with `cleartextTrafficPermitted=false` (verify no existing
  feature needs cleartext; scope per-domain if so).
- No app-authored HTTP to Discord — everything flows through the SDK.
- Voice media stays within the SDK's DTLS-SRTP; we only route audio via `DiscordAudioManager`.

## 8. Privacy, consent & sanitization

- **Consent gate:** initialize the SDK / request mic / open network only after explicit
  "Connect Discord".
- **Presence:** default OFF; generic-mode toggle ("Playing a game" vs real title); never
  broadcast file paths — display name only; clamp to Discord's field limits; strip control
  and RTL-override characters.
- **Inbound sanitization:** clamp lengths, strip control/RTL chars (anti-spoofing), and load
  avatars only from an **https Discord-CDN host allowlist** via Coil with restricted schemes.
- **Mic:** requested only at first voice-join, with rationale; foreground service runs only
  during an active call with a clear notification.
- **Scopes:** request the minimum set (identity, relationships read, presence write, voice).
- **Logging:** no tokens/PII in logs; release ships a no-op Timber tree; scrub before log.
- Everything here maps back to the published Privacy Policy (`docs/legal/privacy-policy.md`).

## 9. Build & repo

- **Git LFS** tracks `discord/discord-native/libs/*.aar` (release). Debug aar (287MB) and the
  source zip are gitignored.
- NDK required for builds and CI.
- Keep consumer ProGuard rules (aar ships `proguard.txt`); keep `com.discord.socialsdk.**`.
- Prefer App Bundle / ABI splits so each device downloads one ABI; note universal-APK size.

## 10. XMB Social section — unified drill structure

Reuses the existing `XMBItem` / `XMBItemType` / `XmbItemLeadingIcon` pipeline
(`feature-xmb/.../ui/XMBItemList.kt`) and the two-pane `XmbDrillFlyout` — **no bespoke list
widget**. Only new item types + leading-glyph branches are added.

**Column glyph:** a white house-style `catbar_social` drawable derived from Material **Groups**,
registered in `core-ui/.../icons/CategoryIcons.kt` as
`CategoryIcon("ic_social", "Social", R.drawable.catbar_social)` so it resolves through
`categoryIconFor` exactly like every other column (keeps the bitmap-art set consistent rather
than dropping a raw Material vector into the bar).

**New `XMBItemType`s and their leading icons** (added to the enum + `XmbItemLeadingIcon`):

| Type | Level | Row | Leading glyph | Title / subtitle |
|---|---|---|---|---|
| `SOCIAL_ACCOUNT` | 1 | a logged-in account (one row each) | **avatar** via `coverUri` → `AsyncImage`, circle-clipped (fallback `AccountCircle`) | display name / "Online" |
| `SOCIAL_ADD` | 1 | Add | `PersonAdd` | "Add" / "Sign in another account" |
| `SOCIAL_LOGIN_QR` | 2 · under Add | Login via QR | `QrCode2` | "Login via QR" / "Scan with your phone" |
| `SOCIAL_LOGIN_BROWSER` | 2 · under Add | Login via Browser | `OpenInBrowser` | "Login via Browser" / "Sign in on this device" |
| `SOCIAL_FRIENDS` | 2 · under Account | Friends | `People` | "Friends" / "N Online" |
| `SOCIAL_VOICE` | 2 · under Account | Voice | `Headset` | "Voice" / "N Active" |
| `SOCIAL_ACTIVITY_SETTINGS` | 2 · under Account | Activity Settings | `SportsEsports` | "Activity Settings" / presence on-off + generic mode |
| `SOCIAL_DISCORD_SETTINGS` | 2 · under Account | Discord Settings | `Settings` gear | "Discord Settings" / "Notifications & more" |

**Drill hierarchy** — each level is a standard drill-in, reusing `XmbDrillFlyout` / nested
`XMBItemList` with the same visuals + focus as Games / Music:

- **Level 1 — Social column selected:** the connected **account(s)** (avatar rows) + **Add**.
  Pre-login there are no accounts, so Level 1 is just **Add**.
- **Level 2:**
  - **Account →** Friends · Voice · Activity Settings · Discord Settings
  - **Add →** Login via QR · Login via Browser  *(QR is primary per §6 / approved plan)*
- **Level 3 (designed further later):** Friends → friend list (avatar + presence); Voice →
  rooms / participants (join, mute, leave via `XmbHelpBar` / `ButtonPrompt`); Settings → options.

**Multi-account:** each account is its own Level-1 `SOCIAL_ACCOUNT` row with its avatar, and its
Friends / Voice / Activity / Settings live under it at Level 2 — so accounts stay fully isolated.
A newly logged-in account's profile picture becomes its row icon automatically (avatar URL →
`coverUri`).

**Deferred (post-v1, with text chat):** Servers and Direct Messages slot in as extra Level-2
rows under an Account (`SOCIAL_SERVERS` / `SOCIAL_DMS`), same style.

Controller-first focus per `project_settings_focus` / `project_controller_layout`; help bar
shows Enter / Back (plus voice controls inside the voice drill). Avatar URLs are loaded only
from the https Discord-CDN allowlist (see §8) before Coil renders them.

## 11. Milestones

- **M0** — Module scaffolding + Git LFS + aar wired + native build compiles (no features).
- **M1** — PKCE auth + encrypted token store + session restore + Connect/Disconnect UI.
- **M2** — Activity Status (presence + generic mode).
- **M3** — Friends list (relationships → Flow → UI).
- **M4** — Voice (call/lobby, mic permission, foreground service, Krisp, audio routing,
  controller controls).
- **M5** — Hardening pass (network config, logging, sanitization) + security review +
  Data Safety form update.

## 12. Security checklist (acceptance gate)

Code-reviewed complete; a final on-device pass (voice + a 2nd account) still recommended.

- [x] Tokens encrypted at rest (Keystore AES-256-GCM) — `DiscordTokenStore`
- [x] No secret in the APK (PKCE / device grant, public client)
- [x] `cleartextTrafficPermitted=false` — `res/xml/network_security_config.xml` (release trusts system CAs only)
- [x] Redirect URI validated — N/A for the device grant (no redirect); auth traffic scoped to `discord.com`
- [x] Inbound content sanitized (length, control/RTL chars, avatar host allowlist) — `DiscordSanitize`, applied at the native activator boundary to friend/user/participant names, activity, and avatar URLs
- [x] Outbound presence sanitized/clamped, generic mode honored — `DiscordPresenceController` (shared `DiscordSanitize`)
- [x] No PII/token logging; release no-op logger — Timber tree planted only in debug (release = silent)
- [x] SDK init / mic / network gated behind explicit consent — opt-in throughout; voice mic requested at first join
- [x] Minimal OAuth scopes — `openid sdk.social_layer`
- [x] Secure wipe on logout (blob + Keystore key) — `DiscordTokenStore.clear()`

## 13. Open items / risks

- `RunCallbacks` pump lifecycle vs. battery (pause when Social inactive; keep presence?).
- Play foreground-service-type policy compliance for voice.
- No x86 ABI → test voice on an arm device or arm emulator image.
- App Bundle vs. sideload distribution affects delivered size.
