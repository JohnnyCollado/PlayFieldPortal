# PSP system software 6.61 — structure and UI notes (2026-11-20)

Reference notes from unpacking Sony's official 6.61 update (`EBOOT.PBP`, 27 Aug 2016) to see how
the PSP's system software is organised and how it describes its screens. Measurements and
structure only: no Sony file is in this repository and none may be shipped in PFP.

Tools: [pspdecrypt](https://github.com/John-K/pspdecrypt) (built from source) to unpack;
[RCOMage](https://github.com/kakaroto/RCOMage) 1.1.1 source, patched locally to read the PSP's
"RLZ" compression, to convert the screen files to XML. The unpacked copy lives in a session temp
folder and is not kept.

## Version

`vsh/etc/version.txt`: release 6.61, build 5553, VSH build dated 2014-11-13, target "WorldWide".
The update carries boot code for seven hardware models (01g, 02g, 03g, 04g, 07g, 09g, 11g).

## Layout of the system drive (456 files)

| Folder | Files | What it is |
|---|---|---|
| `flash0/kd` | 195 | Kernel and driver modules |
| `flash0/vsh/module` | 100 | The menu shell and its plug-ins (`.prx`, compiled code) |
| `flash0/vsh/resource` | 67 | 61 screen-definition files (`.rco`), wallpapers, Game Boot video, theme data |
| `flash0/font` | 21 | Fonts: `ltn0`–`ltn15` (Latin), `jpn0`, `kr0`, a Chinese font, `arib` |
| `flash0/dic`, `codepage`, `data/cert` | 8 | Input dictionaries, text encodings, certificates |
| `flash1/…` | — | Empty folders for user data: `registry`, `net`, `vsh`, `dic`, `gps` |

## How the UI is organised

- **One feature = one code plug-in + one screen file with the same name**: `sysconf_plugin.prx` +
  `sysconf_plugin.rco` (Settings), `osk_plugin` (on-screen keyboard), `msgdialog_plugin`
  (message dialogs), `game_plugin`, `music_player_plugin`, `photo_browser_plugin`, and so on.
- **`vshmain.prx` is the shell; `paf.prx` is the shared UI framework** every plug-in draws with.
- **Shared chrome is its own files**: `system_plugin_bg` (background), `system_plugin_fg` (clock,
  battery, info bar, button hints), `system_plugin` (shared images and the eight system sounds),
  `topmenu_plugin` (the cross menu and its context menu), `topmenu_icon` (the menu's icons).
- **A screen file holds four trees**: images, text (one table per language), sounds, and objects
  (pages made of nested elements), plus named animations.
- **Text is separate from layout**: every file carries the same 12 language tables (Japanese,
  English, French, Spanish, German, Italian, Dutch, Portuguese, Russian, Korean, Simplified and
  Traditional Chinese); elements refer to a text entry by name.

## Building blocks (counts across all 61 files, 686 pages)

Plane 1,949 · Text 1,349 · Button 735 · MItem 402 (in MList 177: vertical menu lists) · Group 342 ·
IItem 183 (in IList 40: icon lists) · Scroll 68 · XList 47 · ItemSpin 29 / Spin 20 (value pickers) ·
Progress 17 · LItem 15 / LList 8 · Edit 7 · Clock 3 · XMenu 1 with 8 XMList (the cross).

Largest screen sets: network settings 113 pages, Settings 83, PSN sign-up 48.

## Measurements (screen is 480 × 272; positions are from the screen centre — my reading)

- **Cross menu:** a single `XMenu` element at x = −130 with 8 category slots. Its item spacing,
  icon sizes and slide animation are NOT in the screen file; that widget is drawn by compiled
  code in the framework.
- **Context menu:** a full-height panel 128 wide by 272 high at the right edge, holding one
  vertical list.
- **Status area:** clock text at (203, 123); battery 44 × 16 at (223, 124); busy icon 17 × 17.
- **Legibility over wallpaper:** every status element has a separate shadow image 2 px off
  (`battery` + `battery_shadow`, `mute`, `hold`, `busy`, `square`). A custom wallpaper is drawn
  under a tinting cover plane (0.6, 0.62, 0.7 at 0.85 opacity).
- **Info bar:** 564 × 46 with icon, and text 445 × 18.
- **Background:** the wave is a 3D model (`mdl_bg`) drawn at 8.5× scale, not an image.
- **Monthly colours:** `01-12.bmp` is the twelve monthly background colours; `13-27.bmp` more.
- **Game Boot** is a video file (`gameboot.pmf`, 200 KB), not an animation definition.

## Settings screens in detail (`sysconf_plugin`: 83 pages, 605 text labels)

- **No separate Settings screen.** Each settings category (Sound, Display, Power Save, Date &
  Time, Theme, Security, System … 11 in all) is a list page drawn with the same list widget as the
  cross menu (`XList`); the rows are filled in by code, not laid out in the file.
- **Every setting is "pick from a list".** Choosing a row opens a vertical option list (`MList`)
  at x = −182, y = −7: the same right-hand panel position the context menu uses. About 45 of the
  83 pages are such option lists, and 22 of them hold exactly two items.
- **No switches and no sliders.** On / Off is a two-item list; backlight brightness is a
  three-item list (Level 3 / 2 / 1); volume and speeds are lists too.
- **One help line per setting, in one place.** Settings carry a separate `…_info` description
  ("Reduces the difference in volume level between tracks."). The shared info bar (564 × 46, text
  445 wide) is the likely place it is shown; that link is my inference, not stated in the file.
- **Choice dialog:** a full-screen tint cover (0.6, 0.62, 0.7 at 0.85 opacity) with the choices
  centred between two hairlines 360 wide, 43 above and below centre.
- **Information pages** (battery, system information) are label-and-value lists with a "none"
  fallback text per row.
- **Date and time** use a dedicated spinner widget; text entry hands off to the on-screen keyboard.
- **First-run setup** is six pages inside this same file: language, time zone, date and time,
  nickname, birthday, summary.
- **Type scale:** four sizes in the ratio 1 : 0.92 : 0.85 : 0.77 (7.605, 7.02, 6.435, 5.85 in the
  file's own units). All text is white with a drop shadow offset (2, −2.5) in 45% grey.
- **Icons with shadows:** every icon is a pair, the image plus a shadow image offset by (2, −2).

## Animation timings (ms)

- Fades: 500 is the standard (39 uses), then 100 (13), 450 (6), 400 and 550 (4 each), 250 (3).
- Resizes: 500 standard (18), then 450, 400, 250.
- Moves: 100, 350, 500.
- Boot sequence (`opening_plugin`, 45 animations): sound starts at 51 ms; model animation runs
  3,500 ms; sound sequence ends at about 4,050 ms; a 3,000 ms colour pulse on the logo glow.

## System sounds (8)

cursor · decide · cancel · category_decide · option · error · system_ok · system_ng.
PFP has five menu sounds; the PSP additionally distinguishes confirming a category, opening the
options menu, and a system OK / not-OK pair.

## Not learned from this

The behaviour (input handling, the cross menu's motion, how plug-ins are loaded) is in compiled
code and was not examined.
