# Theme Studio Restructure and Theme Format v4: Overview

Written for a reviewer with basic coding knowledge. The detailed plan, with every task's tests
and acceptance criteria, is `docs/plans/PFP_Theme_Studio_Restructure_Implementation_Plan.md`.
The approved mockup is the "Theme Studio Restructure" artifact on claude.ai.

Status: all 33 planned tasks are implemented, test-first. Nothing is committed.

The final verification ran every unit test in every module plus both app versions, rebuilt from
scratch (`--rerun-tasks`):
- **3,473 tests, 0 failures**, 10 skipped (the skips were already there).
- 11 compiler warnings remain. All of them were there before this work and are left on purpose;
  see section 8.

Nobody has checked the result on the handheld or looked at the running desktop Studio yet; that
is your part (checklists below).

---

## 1. What changed, in one paragraph

A PlayField theme is a `.pfptheme` file, which is a zip with a small `manifest.json` and some
pictures. The file can now hold a theme's whole look, feel and sound: colours, background
(image, video or wave), text and icon readability, XMB geometry, **128** replaceable icons (up
from 52), five menu sounds plus a looping ambience track, and custom Boot and GameBoot videos.
The launcher applies and saves all of it. The desktop Theme Studio was rebuilt around it, with
a section list on the left, a live interactive XMB preview in the middle and an inspector on
the right. Old themes still open everywhere and can be upgraded. New themes still open on old
launchers, which simply skip what they don't understand.

---

## 2. The theme file (shared code: `core/theme-kit`)

| Area | What it means |
|---|---|
| Schema 4 | New optional manifest fields: `author`, `description`, `updated`, `textColorExact`, `legibility`, `motionCrop`, `waveStyleV4`. `created` is now kept when a theme is saved again. |
| Fallback values | When a value is new, older apps get the closest old value. "Reduced + Static" saves as `waveStyle: "static"` plus `waveStyleV4: "reduced_static"`, so an old launcher shows a frozen wave, not a full-speed one. |
| Nothing is lost | Fields and files an app doesn't recognise are kept and written back out unchanged when the theme is saved (`PassthroughEntry`, `manifestExtras`). |
| Repair, don't reject | Bad values are clamped or reset to defaults. Each repair is listed in an upgrade report with four groups: Kept, Added, Repaired, Can't recover (`ThemeUpgrade`). |
| Media | `sounds/<slot>.<ext>`, `ambience.<ext>`, `boot.mp4`, `gameboot.mp4`, with per-slot size and length limits (`ThemeMediaSlots`). A pure-Java probe reads clip lengths for MP3, WAV, OGG and MP4/M4A (`MediaDurationProbe`). |
| Icons | 81 regular slots plus 47 console slots, 128 in total. New groups: Shiba Coins, Media controls, Game Detail, Notifications, Menus, plus four new status-strip slots. The console group gains CPS1/2/3, Xbox, Favorites, Desktop and Default. |
| Video crop | `motionCrop` is a rectangle given as fractions of the video frame. The video is never re-encoded; the launcher crops it while it plays. |
| Docs | New `docs/theme-format.md` describes the format exactly as the code implements it. |

## 3. The launcher

- **Apply and save** (`PfpThemeStore`): applies and saves every new field. Fixes the bug
  where "Reduced + Static" was lost on export.
- **Readability settings**: they come from the theme only when the theme includes them.
  Resetting the theme leaves your own settings alone.
- **Media**:
  - Theme sounds and videos are checked before they're installed, then unpacked into a
    separate `theme-media/` folder.
  - **Sound you picked yourself always beats the theme's**, which beats the built-in sound.
    Your Settings screens still only show your own picks.
- **Motion wallpaper**:
  - The video plays cropped to the theme's rectangle.
  - Choosing your own wallpaper in Display settings or the photo viewer clears an old theme crop.
- **Size limit**: themes up to 256 MB can be imported. The old limit was 64 MB, and the Studio
  warns when a theme goes over it.
- **My Themes**: older themes show an "Older format" tag and an "Update theme file" option. The
  update rewrites the file safely, through a temp file and an atomic swap. Applying a theme
  never requires updating it.
- **New icon slots are drawn on the device**: Shiba Coins (the old slots were never read; now
  fixed), coin medallions, status strip, notification panel, music and video player controls,
  Game Detail buttons, menu check mark and back arrow. With no theme, everything looks exactly
  as before.

## 4. The desktop Theme Studio

- **Layout**:
  - Left: sections (Info, Color, Background, Legibility, Layout, Icons, Sounds, Boot &
    GameBoot, Export check), each with a one-line status.
  - Centre: the live preview, with an "In this file" size bar under it.
  - Right: the selected section's settings.
  - The Studio's own colours stay neutral; they don't take on the theme's accent.
- **Toolbar**: New, Open, Upgrade folder…, an "Import PSP theme" menu (Convert, Batch
  convert, Unpack), Undo/Redo (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y) and Export.
- **Live preview** (copies the launcher's code and numbers):
  - Same canvas size as the launcher (832×468), same category bar and rows.
  - Drill-into-lists, the right-side options menu with submenus, and the Games filter (X key).
  - The game-logo fade, the readability styles and an animated wave in all four modes.
  - Looping motion wallpaper. If the computer can't decode the video fast enough, it shows the
    still image with a "plays on the device" label.
  - GIF icons animate only when focused, as on the device.
  - Boot and GameBoot playback.
  - Keys: arrows, Enter, Esc, Tab/Y, X. Click the preview to drive it.
- **Background**: one place for wave, image or video. Pick a fit (PSP, HD, Full HD, Original),
  then drag, resize or zoom a crop frame locked to that shape. Pick the video frame used as
  the still. Choose the motion style.
- **Layout**:
  - "Preview with my layout" copies the launcher's Adjust XMB Layout. It **is never saved in
    the theme** and is only there so you see the theme the way your device shows it. An ⓘ
    tooltip tells you where the setting lives on the device.
  - Theme geometry (Detect from wallpaper, plus 11 advanced values) **is** saved.
- **Icons**:
  - Search across all 128 slots, with group chips and filters (On screen, Customized, New).
  - Drag an image onto a slot, or import a whole folder or zip at once (files named by slot
    key).
  - A slot card shows the icon at its real sizes, its GIF frames and template export.
  - Console slots now show the launcher's real console artwork.
- **Sounds and Boot**: file pickers with checks on length, size and format. WAV previews play;
  other formats show "plays on the device".
- **Undo/Redo**: up to 100 steps. Slider drags merge into one step. Temporary copies of
  videos and sounds are kept for as long as an undo step still needs them.
- **Older themes**:
  - Opening one shows an upgrade banner.
  - Export check shows the Kept / Added / Repaired / Can't recover report.
  - "Upgrade folder…" upgrades a whole folder. It keeps `.bak` copies, never overwrites an
    existing backup, and writes a report.
- **Bug fixes**: opening a theme no longer drops its console icons, and saving no longer
  overwrites the created date.

---

## 5. Things fixed along the way (outside the plan)

- `ShibaCoinsFolderMatchTest` didn't pin which Android version Robolectric simulates, so it
  crashed. Pinning it exposed a **real bug**: after you linked a Windows game folder, the
  "Linked to … (appid …)" confirmation was wiped as soon as the sync succeeded. Fixed in
  `ShibaCoinsViewModel.sync(successMessage)`.
- Two fixes from the photo viewer and the icon store: a photo-viewer wallpaper now clears any
  old theme video crop, and `sysicon_default` is a real slot now, so a test that called it
  invalid was updated.
- A flaky test compared two sample zips byte for byte, but zips record the time each file was
  written. Sample themes now use a fixed timestamp.
- The worktree had Git LFS placeholder files for the Discord SDK, which broke the app build.
  They were pulled from the local LFS cache, and Git shows no change.
- Pre-existing compiler warnings were cleaned up. See section 8 for the ones left on purpose.
- Locked Shiba coins with themed or custom art now turn grey like the built-in art.
  `CustomIconSurface` takes an optional `colorFilter`, applied as one layer so still art, its
  matte and a playing GIF all get it. Covered by `CustomIconSurfaceFilterTest`, a pixel test.

## 6. Defaults I chose for you to confirm

1. Readability settings in a theme are optional. Apply only changes them if the theme sets
   them, and Reset leaves yours alone.
2. Theme media lives in `theme-media/`. Precedence is your pick, then the theme's, then the
   built-in.
3. The new icon groups are theme-only on the handheld. The on-device Customize XMB Icons editor
   is unchanged, except the console list grew from 40 to 47.
4. New slot key names, which are permanent once themes ship with them: `media_play`,
   `media_pause`, `media_prev`, `media_next`, `media_back10`, `media_fwd10`, `detail_play`,
   `detail_favorite`, `detail_artwork`, `detail_manual`, `detail_more`, `notif_album`,
   `notif_image`, `notif_tag`, `notif_coin`, `notif_blocked`, `notif_settings`,
   `notif_download`, `notif_feed`, `menu_check`, `menu_back`, `shiba_coin_bronze`, `_silver`,
   `_gold`, `_platinum`, `status_notifications`, `status_controller`, `status_wifi`,
   `status_signal`.
5. There is one Favorite icon slot. It is drawn dimmed when the game isn't a favourite.
6. No new audio library. The Studio previews WAV only, and Boot/GameBoot authoring is MP4 only
   (WebM still plays on the device).
7. "Use my exact color" is off by default and isn't written to the file unless you turn it on.
8. Themed Wi-Fi and Signal icons replace the live level meters, so no signal level is shown.
   That's the trade-off of using an image.
9. The motion preview falls back to the still when it runs below about 12 fps, or 80% of the
   clip's own frame rate.

## 7. Your checklists

**Run the Studio**

```bash
./gradlew :studio:run
```

Click the preview and drive it with the arrow keys, Enter, Esc, Tab and X. Then:

- Compare category switching and drill-in with the device.
- Try the crop frame and the Adjust on preview overlay.
- Search icons, drag a PNG onto a slot, and import an icon pack.
- Play a WAV sound, then Play Boot and Play GameBoot.
- Open an old theme to see the banner and the report.
- Leave a 1080p motion theme running for a few minutes to check stability.

**Install on the device**

```bash
./gradlew :app:installFullDebug
```

- **Sounds**: a theme with sounds plays them. Your own pick wins, and Reset keeps your pick.
  "Save as Theme" mixes your sounds with the theme's.
- **My Themes**: an old theme shows "Older format". Update removes the tag, and the theme looks
  the same.
- **Cropped motion theme**: the video lines up with its still, with no black edges. Your own
  videos still center-crop.
- **Shiba Coins**: hub rows and coins show theme art. A locked coin with theme art turns grey,
  like the built-in art.
- **Status strip and notification panel icons**: the bell's count and tap still work.
- **Music and video player controls**: play and pause swap. Check the video player in
  particular.
- **Game Detail buttons**: Favorite is dimmed when the game isn't a favourite.
- **Menu check mark and back arrow**.

## 8. Known gaps and follow-ups

- **Warnings left on purpose**:
  - The Compose test-rule `v2` migration, in 9 test files. It changes how tests schedule
    coroutines, so it needs its own careful pass.
  - Two Android platform APIs in `InstalledAppRepository` (`FLAG_IS_GAME`,
    `unsafeCheckOpNoThrow`).
  - One scoped suppression in the Studio's icon loader. Removing it needs the Compose
    resources library, which is a new dependency.
- **Flaky test**: `DisplaySettingsViewModelFontColorTest` ("an unknown persisted legibility
  style…") timed out once under the full parallel run and passes on its own. It was already
  there and this work didn't touch it, but it's a timing-sensitive test worth hardening.
- **New console art**: CPS1/2/3 and Xbox have slots but no art of their own. They use the
  default and Xbox 360 art, as the launcher does.
- **Not in this pass**: fonts, controller button prompts (~90) and physical-media cards (102).
- **Studio picker on the device**: the new icon groups aren't in the on-device picker yet.
