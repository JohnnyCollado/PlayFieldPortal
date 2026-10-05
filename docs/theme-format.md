# `.pfptheme` format reference (schema v5)

The authoritative description of the theme bundle. Code of record: `core/theme-kit`
(`PfpTheme.kt`, `ThemeManifestV4.kt`, `PfpThemeCodec.kt`, `PassthroughEntry.kt`, `ThemeUpgrade.kt`,
`ThemeMediaSlots.kt`, `IconSlots.kt`, `CustomizableIcons.kt`, `UiMediaLimits.kt`,
`MotionLimits.kt`, `MediaDurationProbe.kt`). Where this document and the code disagree, the code wins
and this document is the bug.

A `.pfptheme` is a plain zip: a `manifest.json` plus optional image, motion, audio and video entries.
The palette derives from one accent colour, so the manifest stays small.

## 1. Version history

| Schema | Added | Notes |
|---|---|---|
| v1 | manifest, `wallpaper.png`, `preview.png`, accent / icon colour, wave style | wave-only themes carry no wallpaper |
| v2 | `icons/<key>.png` custom icon slots, `layout` | |
| v3 | icons widen to `png`/`gif`; `sysicons/<id>.<png\|gif>` console art; `motion.<mp4\|webm\|gif>`; `textColor` | streamed motion, bounded zip reader |
| v4 | manifest: `author`, `description`, `updated`, `textColorExact`, `waveStyleV4`, `legibility`, `motionCrop`. Entries: `sounds/*`, `ambience.*`, `boot.*`, `gameboot.*`. 30 new `icons/` slots, 7 new `sysicons/` ids, `mediaicons/` physical-media art. Lossless passthrough of unknown manifest keys and unknown entries | all additive |
| v5 | `lockscreen.png`: a still for the device's lock screen | additive; set on the device only when the user opts in at apply time |

**No reader gates on `schemaVersion`.** A missing field takes its default, an unknown field or entry
is ignored by apply, and a newer bundle opens on an older build as the subset that build knows. The
version number is stamped on write and used only to label a file as "older" in the UI.

## 2. `manifest.json`

```jsonc
{
  "manifest": "pfptheme",                // required, must equal "pfptheme"
  "schemaVersion": 5,
  "name": "Night Drive",                 // required
  "accentColor": "#3A6FD8",              // required, #RRGGBB
  "author": "Jane",                      // v4, optional
  "description": "Neon over rain",       // v4, optional, clamped to 500 chars on read
  "iconColor": "auto",                   // "#RRGGBB" | "auto"
  "textColor": "auto",                   // "#RRGGBB" | "auto"
  "textColorExact": false,               // v4, optional; absent = the theme says nothing
  "waveStyle": "static",                 // LEGACY, always written: animated | reduced | static
  "waveStyleV4": "reduced_static",       // v4 exact: animated | reduced | static | reduced_static
  "legibility": {                        // v4, optional; every member optional
    "text": "auto",                      // auto | none | shadow | outline | plate
    "icon": "contour_auto",              // none | offset_shadow | contour_dark | contour_light | contour_auto
    "solidUnfocusedIcons": false
  },
  "motionCrop": { "x": 0.1, "y": 0.0, "w": 0.8, "h": 1.0 },  // v4, optional, normalized source frame
  "layout": { /* XmbLayoutSpec, sanitized by XmbLayoutSpecCodec on every read path */ },
  "source": { "type": "user-created" },  // or "ptf-import" (+ file, firmware)
  "created": "2026-07-06",               // preserved once set
  "updated": "2026-10-02",               // v4, set on every export / upgrade
  "someFutureField": { }                 // unknown keys: preserved verbatim
}
```

### Read rules

1. Never gate on `schemaVersion`. Missing fields take defaults.
2. Exact wave = `waveStyleV4` if recognized, else `waveStyle` if recognized, else `animated`
   (`WaveStyles.resolveExact`).
3. An unrecognized enum string inside `legibility` makes that member absent.
4. `motionCrop` is sanitized: non-finite is absent; clamped to [0,1]; `w`, `h` at least 0.05;
   `x+w` and `y+h` at most 1 by shrinking, never shifting. It applies only to MP4/WebM motion and is
   ignored for GIF motion.
5. `description` is cut to 500 characters.
6. Decoding falls back to field-by-field recovery: one wrongly-typed optional field costs that field
   (reported as an "undecodable field"), not the theme. `manifest`, `name` and `accentColor` must
   decode or the file is not a theme.
7. Keys no typed field claims are kept in `PfpThemeBundle.manifestExtras`.

### Write rules

1. `schemaVersion = 4`. `waveStyle` is the legacy fallback of the exact value
   (`reduced_static` becomes `static`).
2. `created` is the existing value if present, else today. `updated` is today
   (`ThemeUpgrade.upgrade`).
3. Unknown manifest keys are merged back; typed fields win on collision.
4. Future enum additions follow the same pattern: a fallback in the old field, the exact value in a
   new one.

## 3. Zip entries

```
mytheme.pfptheme
├── manifest.json                          required; written first (readManifest stops after it)
├── wallpaper.png                          optional; absent = live wave background
├── preview.png                            optional on read
├── lockscreen.png                         optional (v5); a still for the device lock screen, opt-in at apply
├── icons/<key>.<png|gif>                  82 keys in v4 (v3 had 52)
├── sysicons/<id>.<png|gif>                47 ids in v4 (v3 had 40)
├── mediaicons/<id>.<png|gif>              42 ids: physical-media art (Physical Media mode)
├── motion.<mp4|webm|gif>                  streamed, never held in memory
├── sounds/<sound_scroll|sound_back|sound_confirm|sound_error|sound_notification>.<mp3|wav|ogg|m4a>
├── ambience.<mp3|wav|ogg|m4a>
├── boot.<mp4|webm>
├── gameboot.<mp4|webm>
└── <anything else>                        passthrough: kept verbatim on re-write, never applied
```

Entries are written in sorted key order, so identical themes produce byte-identical bundles.

### Media slots (`ThemeMediaSlots`)

Keys are identical to the launcher's `UiMediaSlot` keys (pinned by `UiMediaSlotTest`).

| Slot key | Entry stem | Containers | In-bundle byte cap | Duration cap |
|---|---|---|---|---|
| `sound_scroll` | `sounds/sound_scroll` | mp3 wav ogg m4a | 8 MB | 0.5 s |
| `sound_back`, `sound_confirm`, `sound_error` | `sounds/<key>` | mp3 wav ogg m4a | 8 MB | 1 s |
| `sound_notification` | `sounds/sound_notification` | mp3 wav ogg m4a | 8 MB | 2 s |
| `ambience_audio` | `ambience` | mp3 wav ogg m4a | 32 MB | 10 min |
| `boot_video` | `boot` | mp4 webm | 25 MB | 15 s |
| `gameboot_video` | `gameboot` | mp4 webm | 25 MB | 10 s |

Byte caps are enforced by the codec on read (`ThemeMediaSlots.Slot.maxBytes`). Duration caps come
from `UiMediaLimits` and are enforced by the importing side, using `MediaDurationProbe` (pure JVM:
MP3 Xing/CBR, PCM WAV, OGG Vorbis/Opus, MP4/M4A `mvhd`; null when headers can't be trusted, which
only ever narrows what is accepted). GIF is not accepted for boot or GameBoot. A retired key such as
`sounds/sound_launch.mp3` is not claimed by any slot and is treated as passthrough.

## 4. Icon slots

`IconSlots.ALL` = 81 slots (the `icons/` contract). `CustomizableIcons.ALL` = 128 = `IconSlots.ALL`
as a verbatim prefix plus 47 console slots. Keys are zip entry names: **never rename one, only add.**

| Group | Slots | Bundle dir | Keys (those added in v4 marked) |
|---|---|---|---|
| CATEGORY_BAR | 10 | `icons/` | `catbar_*` |
| ITEMS | 37 | `icons/` | `item_*` (includes `item_shiba_connect/track/untracked` and `item_umd`) |
| STATUS | 10 | `icons/` | 6 battery/bluetooth; v4: `status_notifications`, `status_controller`, `status_wifi`, `status_signal` |
| SHIBA | 4 | `icons/` | v4: `shiba_coin_bronze`, `_silver`, `_gold`, `_platinum` |
| MEDIA | 6 | `icons/` | v4: `media_play`, `media_pause`, `media_prev`, `media_next`, `media_back10`, `media_fwd10` |
| GAME_DETAIL | 5 | `icons/` | v4: `detail_play`, `detail_favorite`, `detail_artwork`, `detail_manual`, `detail_more` |
| NOTIFICATIONS | 8 | `icons/` | v4: `notif_album`, `notif_image`, `notif_tag`, `notif_coin`, `notif_blocked`, `notif_settings`, `notif_download`, `notif_feed` |
| MENUS | 2 | `icons/` | v4: `menu_check`, `menu_back` |
| **IconSlots total** | **82** | | 52 in v3, +30 in v4 |
| CONSOLE | 47 | `sysicons/` | key `sysicon_<id>`, entry `sysicons/<id>`; 40 platform ids plus v4 extras `cps1`, `cps2`, `cps3`, `xbox`, `favorites`, `desktop`, `default` |
| PHYSICAL_MEDIA | 42 | `mediaicons/` | key `physmedia_<id>`, entry `mediaicons/<id>`; every console id except `allgames`, `android`, `favorites`, `desktop`, `default` |
| **CustomizableIcons total** | **171** | | |

STATUS, SHIBA, MEDIA, GAME_DETAIL, NOTIFICATIONS and MENUS are theme-only groups: neither the
on-device icon editor nor the Theme Studio lists them. Both editors list the same slots in the same
order (`IconEditorLayout`): Crossbar, Items (grouped by XMB column, with `sysicon_allgames` and
`sysicon_favorites` in the Game column), Consoles, Physical Media. `catbar_favorites` is not listed;
a theme that carries it still applies it.

In code, `PfpThemeBundle.icons` holds every family in one map keyed by slot key; only the codec maps
a key to its folder (`sysicon_<id>` ↔ `sysicons/<id>`, `physmedia_<id>` ↔ `mediaicons/<id>`, the rest
under `icons/`). Which `physmedia_` slot and which bundled art file a platform alias resolves to is
one table, `PhysicalMediaIds`, read by the launcher and the Studio alike.

`mediaicons/` entries were written by the Theme Studio as passthrough before the launcher read them;
any build that knows the folder reads those bundles as typed media icons. Template sizes: catbar/items/new groups/console 256 px, status 128 px.

## 5. Limits

| What | Cap | Source |
|---|---|---|
| Entries per bundle | 256 | `PfpThemeCodec.BUNDLE_LIMITS` |
| Any single entry | 64 MB | `BUNDLE_LIMITS.maxEntryBytes` |
| Whole bundle | 256 MB | `BUNDLE_LIMITS.maxTotalBytes` |
| Icon / sysicon | 8 MB | `PfpThemeCodec.MAX_ICON_BYTES` |
| Motion | 1920x1080, 60 s, 60 MB | `MotionLimits` |
| Menu sound / ambience / boot / GameBoot | see section 3 | `UiMediaLimits`, `ThemeMediaSlots` |
| Description | 500 chars | read-side sanitizer |

A bundle that trips the entry, entry-size or total caps is "not a `.pfptheme`": `read` returns null
rather than throwing. An over-cap icon or media entry is dropped and reported
(`DropReason.OVER_CAP`) and the rest of the bundle still reads.

## 6. Path safety

- Registered entries are gated by their registry (`IconSlots`, `CustomizableIcons`,
  `ThemeMediaSlots`), so a key can never carry a path.
- **Passthrough** names (`PassthroughNames.isSafe`) must match
  `^[a-z0-9_]+(/[a-z0-9_]+)?\.[a-z0-9]{1,5}$` and be at most 96 characters: no `..`, no leading `/`,
  no `\`, at most one directory level. A passthrough name may not collide with a registered entry
  (including a media-slot stem with a refused extension), and a repeated name is dropped.
- Anything else is dropped and reported (`HOSTILE_NAME`, `BAD_EXTENSION`, `DUPLICATE`,
  `UNSUPPORTED_MEDIA`). This includes registered-looking `icons/`, `sysicons/` and `mediaicons/` names that are not
  registered slots: they are passthrough if safe, dropped otherwise.
- Passthrough is never extracted to disk by the launcher. It is only re-written into a bundle.
- Directory entries are skipped.

## 7. Reading, diagnostics and lossless re-write

- `PfpThemeCodec.read` / `readDetailed` accept a stream, bytes, or a `File`. `readDetailed` returns a
  `ReadResult` = bundle + `ReadDiagnostics` (`dropped`, `repaired`, `undecodableFields`).
- Motion, media and passthrough entries are **drained for cap accounting but never held**. They are
  recoverable only when the caller supplies a reopen strategy (the byte and `File` overloads do).
  Reading a plain `InputStream` leaves `motion`, `media` and `passthrough` empty.
- `readManifest(file)` reads only `manifest.json` (written first), so listing a theme library is
  O(first entry).
- `ThemeUpgrade.upgrade(bundle, today)` stamps the current version (v5), writes legacy + exact wave, repairs malformed
  colours (accent resets to `#0055AA`, icon/text colour to `auto`), backfills `created`, sets
  `updated`, and carries extras and passthrough untouched. It is idempotent for a given date.
  `ThemeUpgrade.report` lists what is kept, added, repaired, and "can't recover" (already lost on
  read) as UI strings.

## 8. Compatibility matrix

| Bundle \ Reader | v1 | v2 | v3 | v4 | v5 | Future (v6+) |
|---|---|---|---|---|---|---|
| **Pre-v4 launcher** | applies | applies | applies | applies the v3 subset: wallpaper, colours, legacy `waveStyle` (reduced+static falls back to static), layout, 52 icons, 40 sysicons, uncropped motion; ignores sounds, boot, legibility and new slots. Refuses files over 64 MB | as v4 | applies known subset |
| **v4 launcher** | applies; "older format" tag | same | same | full | applies all but the lock screen image, which it keeps as an unknown entry | applies known subset; unknown content kept on "Update theme file" |
| **v5 launcher** | applies; "older format" tag | same | same | same | full; the lock screen image is offered at apply | applies known subset |
| **Pre-v4 Studio** | opens | opens | opens (drops sysicons) | opens the v3 subset; re-export loses v4 data | same as v4 | opens subset |
| **v4 Studio** | opens + upgrade banner | same | same | full, lossless | opens losslessly (the lock screen image rides as an unknown entry); banner says made by a newer version | opens losslessly; banner says made by a newer version |
| **v5 Studio** | opens + upgrade banner | same | same | same | full, lossless | opens losslessly; banner says made by a newer version |

"Applies" never requires an upgrade. Upgrading writes a v5 file that every column above still opens.
Launcher apply of the v4 manifest fields and media entries lands with the Phase 2 tasks of
`docs/plans/PFP_Theme_Studio_Restructure_Implementation_Plan.md`; the format, codec and upgrade
semantics above are already in `core/theme-kit`.
