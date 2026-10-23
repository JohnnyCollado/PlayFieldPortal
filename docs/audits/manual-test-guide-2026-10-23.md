# Manual test guide — survey fixes (Lite 1.3.2, built 2026-10-23)

Installed on the Odin 3 over adb. Work top to bottom; the first sections are the ones most worth
your time. For each step: tick the box, and write what you saw on the **Notes** line when it
differs from **Expect**.

Codes in brackets (L3, P1, U18…) point to rows in `codebase-survey-2026-10-22.md`.

**Before you start**
- [ ] Settings ▸ System ▸ About shows version 1.3.2.
- [ ] Have a controller handy. Several checks are "does this work with the pad, not just touch".
- Notes:

---

## 1. Custom memory cards for apps (your report) — L28, L29, L30

**1a. Create one from Settings**
1. Settings ▸ Library ▸ Custom Memory Cards ▸ New Custom Memory Card.
2. Type a name (e.g. "Work Apps") ▸ Next.
3. The "Add to Category" picker opens.
- [ ] Expect: the list includes Game **and** app columns (Network, App Store, any custom app category). Each row says "Game card" or "App card" under its name.
- [ ] Expect: you can move through the list and pick with the **pad** (D-pad + A), not only by tapping.
4. Pick Network ▸ Create.
- [ ] Expect: the card list shows "Work Apps" with "0 apps" (not "0 games").
5. Open the card.
- [ ] Expect: group reads "Apps (0)", empty row says "No apps on this card".
- Notes:

**1b. Create one from the category's own menu**
1. On the XMB bar, long-press (or Options on) the **Network** icon.
- [ ] Expect: the menu has **New Custom Card** above Manage Categories.
2. Pick it, name it, confirm.
- [ ] Expect: the new card appears in the Network column.
3. Long-press the **Music** or **Settings** icon.
- [ ] Expect: no New Custom Card row there.
- Notes:

**1c. From the Android column**
1. Go to the Android column, highlight an ordinary app (not one marked as a game).
2. Options ▸ Add to Card ▸ New Custom Card Here… ▸ name it.
- [ ] Expect: the card is an **app** card and appears under **App Store**, not under Game.
- [ ] Expect: Add to Card on that app lists app cards only.
- Notes:

**1d. Card icon**
1. Open any custom card ▸ Change Icon.
- [ ] Expect: a full settings page of icons (not a pop-up grid). The current icon says "In use".
- [ ] Expect: works with the pad and by touch. Back returns to the card.
- Notes:

---

## 2. Things that used to lose data — L1, L3, L5, L6

**2a. SD card mounting late (L1)** — only if your ROM roots include an SD card
1. With games on the SD card showing normally, reboot the Odin.
2. As soon as the launcher appears, open the Game column.
- [ ] Expect: SD games are **not** marked Missing, even for the first few seconds after boot.
3. Optional: eject the SD card, then Library Manager ▸ Re-Scan All (Mark Missing).
- [ ] Expect: a message that a ROM folder couldn't be read ("…check that the card is inserted"); nothing marked missing.
- Notes:

**2b. Music player skipping a broken track (L6)**
1. Make a playlist with a track you then delete from storage (or revoke the folder), followed by a good track.
2. Play from the broken track.
- [ ] Expect: it skips to the next track and that track plays.
- [ ] Expect: Pause stops the music. Closing the player stops it fully (nothing keeps playing behind the XMB).
- Notes:

**2c. Steam coins (L5)** — only if you use Steam achievements
1. Open a Steam game's Shiba Coins page and Update Achievements a couple of times.
- [ ] Expect: earned coins stay earned. If Steam is having trouble, you see a failure, never all coins flipping to unearned.
- Notes:

---

## 3. Controller can do everything touch can — P1–P7

**3a. Discord sign-in (P1, L7)** — Lite has no Social section; skip on Lite, test on Full later
- Notes:

**3b. Launch failure sheet (P2)**
1. Assign a game an emulator that isn't installed (or uninstall one temporarily), then launch it.
- [ ] Expect: "Couldn't launch …" appears in the PFP-style modal (not an Android dialog).
- [ ] Expect: with the **pad**, you can move between Retry, Change Emulator, Change per-system default, Copy diagnostics, and pick any of them. B dismisses.
- Notes:

**3c. Search Online error (P4)**
1. Turn Wi-Fi off. Open a game ▸ Search Online and search something.
2. Press Options (Triangle / Y).
- [ ] Expect: the menu has **Try again**. Turn Wi-Fi back on, pick it, and results load.
- Notes:

**3d. Rename a saved theme by touch (P5)**
1. Settings ▸ Interface ▸ Themes ▸ My Themes: long-press a saved theme card.
- [ ] Expect: the theme's menu opens (Share, Rename Theme, Delete Theme). Rename works.
- Notes:

**3e. Default player pickers (P6)**
1. Settings ▸ Media ▸ Music ▸ Default Music Player, then Video ▸ Default Video Player.
- [ ] Expect: PFP-style picker, opens on your **current** choice, fully usable with the pad. "No other … players found" shows when you have none.
- Notes:

**3f. Add a PC game by ID (P6)**
1. Library ▸ Windows Games (Import PC Games) ▸ a launcher showing "Add by ID…".
- [ ] Expect: a settings page with Game ID, Game name, Source (if the launcher has stores), Test Launch, Add Game. All reachable by pad. Add Game stays disabled until ID and name are set.
- Notes:

**3g. Font colour warning (P6, L18)**
1. Settings ▸ Interface ▸ Display ▸ Font Colour: pick a very dark colour.
- [ ] Expect: "Font colour may be hard to read" with **OK** / **Don't warn me again**, usable by pad. The colour is shown exactly as picked (no claim it was adjusted).
- [ ] Expect: there is no "Use My Exact Colour" switch any more.
- Notes:

---

## 4. Text that used to say the wrong thing — U1–U34

Quick reads. Tick if the text matches; note anything odd.

**Settings ▸ Library ▸ Library Manager**
- [ ] "Re-Scan All (**Mark** Missing)" and its confirm say games are marked missing and kept (U1).
- [ ] With at least one ROM root, a **Scan ROM Roots** row appears under the roots and works (U13).
- [ ] Windows Games: "Import All" says it moves games into the Windows Games Memory Card (U3).
- [ ] Goldberg Installer: turning it **on** first shows "Back up your save files first" (U32). Turning it off needs no confirm.
- [ ] Any message mentioning settings paths says "Settings ▸ Achievements ▸ …" (not "Shiba Coins") or "Settings ▸ Library ▸ Library Manager" (not "Folder Access") (U18, U19).
- Notes:

**Settings ▸ Interface**
- [ ] Sound row subtitle: "Menu sounds, levels & ambience" (U26).
- [ ] Sound ▸ Reset Sound to Defaults: message says every menu sound and every level reset; ambience/boot/GameBoot untouched (U5). Confirm it does exactly that.
- [ ] Display ▸ Thermal Throttle Awareness: "Freeze the background … while the device reports it is running hot" (U6).
- [ ] Display ▸ Text Legibility says "Not applied yet — saved with your themes and backups…" (U2).
- [ ] Display with **no** wallpaper set: no Preview Wallpaper row. With one set: Preview and Reset both show (U31).
- [ ] Display ▸ GameBoot video row mentions "the GameBoot level in Sound ▸ Levels", not "Menu Sounds" (U20).
- [ ] With a motion wallpaper: Background Motion lists 4 states incl. "Reduced + Static", and cycling hits all 4 (U24).
- [ ] Pick a **still** .webp image as wallpaper: it's treated as a still wallpaper ("Wallpaper"), not "Motion wallpaper applied" (U17).
- [ ] Pick an unsupported video type: message lists "MP4, WebM, GIF or WebP" (U23).
- Notes:

**Settings ▸ System**
- [ ] Logs subtitle: "Open or share session logs" (U7).
- [ ] Setup Wizard ▸ first page (Welcome): the footer has **no** Skip; the "Skip Setup" row still leaves (U11). Its text mentions "Setup Wizard from Settings ▸ System" (U21).
- [ ] On the wizard's Finish page: tick Auto-fit, then leave with **Open Library Manager** (not Finish). Back on the XMB, the layout is auto-fitted (U12).
- Notes:

**Settings ▸ Emulators**
- [ ] Installed: a Maintenance group with **Reset Emulator Configuration** is at the bottom (U33).
- [ ] Emulator knowledge: "Export your **emulators**" (U8).
- Notes:

**XMB menus and status bar**
- [ ] Status bar clock follows your Android 12/24-hour setting; switch it in Android settings and the bar updates. Date order matches your locale (U16).
- [ ] Game column Options ▸ Scan All Cards: the result reads "N new games · M Memory Cards scanned" (U25).
- [ ] Notification panel with no history (Clear All first): the prompt bar shows Close but **no** Options (U30).
- [ ] Android column: Remove Card on the Android card says "The apps stay installed, and Find Games can add them back" — no ROM files (U27).
- [ ] An ordinary app's Remove from Library says "The app stays installed…", nothing about files or scans (U28).
- [ ] Windows game Options: Launch With appears only when another launcher can open it, and names the launcher it will really use (U15, U29).
- [ ] Windows game ▸ Install Goldberg Achievements with the installer **off**: message says installing is turned off and where to turn it on (U14).
- Notes:

**Game Detail and friends**
- [ ] Open a game's manual (PDF): footer shows **Page** (◀ ▶) and **Scroll** (▲ ▼); L1/R1 no longer advertised (U4). Left/Right turn pages.
- [ ] Artwork Studio on art **you** picked: no "Reset to Scraped Default". On art the scraper fetched: it's there (U9).
- [ ] Store Match panel title reads "Store Match" (U10).
- [ ] Open App A's detail, back out, quickly open App B: you always see App B (L13). Same for videos and photos.
- Notes:

---

## 5. Smaller fixes worth a glance

- [ ] App Drawer: uninstall the last app in the grid, return to the drawer — a tile is still highlighted (L14).
- [ ] Rename a game, then Shiba Coins hub lists (All Tracked, Recent, Rarest): they show your new name (L9).
- [ ] A music playlist from a folder you then remove: the playlist count drops instead of showing tracks it can't play (L10).
- [ ] Settings ▸ Display ▸ Lock Screen Image ▸ Reset: success message only when it actually reset (L25).
- [ ] Adding a FLAC/Opus folder from a provider that reports odd file types still imports the tracks (L20).
- [ ] A ROM inside `…/ps2/game/` is detected as PS2, not PSP (L24).
- Notes:

---

## 6. Not testable on Lite / right now

- Discord sign-in and voice (P1, L7, L19): Lite has no Social section — check on a Full build.
- Backup & Restore: still switched off by the feature flag.
- One-time startup migrations (L2, L3, L21): run only on an upgrade from an old data set; covered by unit tests.

## Overall

- [ ] Anything crashed? Where:
- [ ] Anything slower than before? Where:
- Notes:

Logs (Lite is a release build, so logcat shows little): Settings ▸ System ▸ Logs ▸ share the
session log if something goes wrong.
