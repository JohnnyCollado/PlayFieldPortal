# Changelog

All notable changes to Play Field Portal are documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/) and [Semantic Versioning](https://semver.org/).

## [1.0.0-alpha.2] — 2026-07-01

Second alpha. Adds a full Music section, a PSP-authentic XMB redesign (cross layout,
two-pane drill flyout, and the real "wave" background), menu sound effects, and a round of
security hardening. (`versionName 1.0.0-alpha.2` / `versionCode 2`.)

### Added
- **Music library & player.** Scan SAF music folders, browse **All Music** as
  `[cover] [title] {artist}` rows, and play in a full-screen player (play/pause, seek,
  previous/next). Album art is extracted from tags and cached on device.
- **Background playback.** "Play in Background" keeps music going via a foreground media
  service with a media-style notification (play/pause, prev, next, stop) and MediaSession,
  so you can leave PFP and keep listening.
- **Playlists.** Create, rename, and delete playlists; add/remove tracks from a song's
  options menu or an in-playlist "Add Tracks" picker.
- **Music section redesign.** Root shows **Now Playing** (only while a track is loaded),
  **Playlist**, **Music Apps** (your picked installed music apps), and a single **Music**
  memory-card item collecting all scanned tracks. A fullscreen, searchable browser backs
  Music and Playlists.
- **Two-pane drill flyout (all drill-ins).** Drilling into a Games sub-item (a platform,
  All Games, Favorites, a collection) **or** a Music section shows the PSP two-pane flyout:
  the memory-card "cross" on the left with a `◀` on the active card, and the drilled
  content pinned to that line on the right — previous card above, next below, uniform
  spacing. Game rows are labeled `[Title]` / `{Platform (Emulator)}`.
- **Authentic PSP wave background.** The XMB background is now the real PSP "wave" — soft,
  slow light folds over a theme-tinted gradient (AGSL shader on Android 13+, with a Canvas
  fallback). Colors follow the active color scheme.
- **Menu sound effects.** The XMB/ES-DE sound set (scroll, select, back, launch, favorite,
  category change) with a **Menu Sounds** toggle in *Settings → Display → Sound*.
- **Sort cycling (X / □).** Cycle sort per list — games (Title / Recently played / Date)
  and music (Title / Artist / Album / Date); the current mode shows in the status strip.
- **Settings item icons.** Every Settings row now shows a wrench badge, matching the rest
  of the XMB.

### Changed
- **PSP-authentic "Original" theme.** True XMB cross layout (the selected row seats just
  under the caticon, the previous row half-clips above the crossbar, icons locked on the
  caticon's vertical line); a single theme hue across the whole XMB; selection conveyed by
  scale + alpha with crisp white labels.
- **Reference-accurate blue gradient.** The default gradient anchors were sampled from the
  real XMB wave — saturated azure top easing to a brighter cyan-blue near the wave — and
  every scheme's gradient now stays a rich, saturated color instead of fading to navy/white.
- **Collections** render the physical-media memory-card art instead of the blank default
  icon, so they read as memory cards.
- **Android library** reworked to be app-picker driven, isolated from ROM libraries, and
  removable ("Remove from Library"); music-folder management moved to *Settings → Music*.

### Fixed
- Seamless XMB wave loop (no skip on repeat).
- Replacing a wallpaper now actually updates the image (unique filename defeats the
  path-keyed image cache).
- **Reset Hidden Apps** action added (hiding an app previously had no in-app way back).
- Settings background scrim lightened so the wallpaper/wave shows through.
- Tightened XMB item tap targets so stray touches in empty row areas no longer select/launch.
- Added breathing room between wide artwork tiles and their titles.
- Item list hard-stops on a row boundary — no partially-clipped rows at the top or bottom.
- Alpha release build fixes (signing, R8/manifest, app-drawer crash).

### Security
- **Captured-shortcut hardening.** Externally-supplied `INSTALL_SHORTCUT` intents are
  sanitized (all URI-permission grant flags and ClipData stripped, target pinned to a
  resolved installed component) at capture **and** at launch, closing a confused-deputy /
  arbitrary-file-read vector. Captured shortcuts now require an explicit **Add** confirmation
  (handled by a non-exported receiver the sender can't forge); silent library poisoning is
  prevented.
- **Network policy.** Cleartext (HTTP) denied app-wide; release builds trust only the system
  CA store (user-installed-CA MITM blocked); debug builds still trust user CAs for proxying.
- **Secrets encrypted at rest.** Artwork/metadata API keys are encrypted with an
  AndroidKeystore-backed AES-256/GCM key.
- **Backups disabled.** `allowBackup=false` plus data-extraction rules keep the library DB
  and API keys off cloud backup and device transfer.
- **Least privilege.** Removed dead exported receivers; All-Files access is requested only
  point-of-need (a prompt in *Settings → Library*), so SAF-only and app-picker users never
  grant it.

## [1.0.0-alpha.1] — 2026-06-28

- Initial alpha: XMB launcher shell, ROM library scanning, artwork scraping, emulator launch,
  gaming categories/collections, controller mapping, and touch controls.

[1.0.0-alpha.2]: https://github.com/JohnnyCollado/PlayFieldPortal/compare/v1.0.0-alpha.1...HEAD
[1.0.0-alpha.1]: https://github.com/JohnnyCollado/PlayFieldPortal/releases/tag/v1.0.0-alpha.1
