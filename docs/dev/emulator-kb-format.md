# Emulator knowledge files (`pfp-emulator-kb`)

How Play Field Portal knows how to launch each emulator, and how to write your own file.

**The example is the real thing.** [`emulator-kb/pfp-default-emulators.json`](../emulator-kb/pfp-default-emulators.json)
is the app's built-in knowledge base written out as a user file: every emulator the app ships with,
exactly as the app reads it. Copy an entry from it as the starting point for your own. A test
(`ExampleKnowledgeBaseTest`) keeps it identical to the built-in default and checks that it imports
with zero refusals.

Code of record: `core/core-domain/.../model/emulatorkb/` (`EmulatorKbDocument.kt`,
`EmulatorKbDecoder.kt`, `EmulatorKbValidator.kt`) and `EmulatorProfile.kt` (`LaunchTemplate`). Where
this document and the code disagree, the code wins and this document is the bug.

## 1. Using a file

Settings ▸ Emulators ▸ Emulator knowledge:

- **Import a knowledge file** picks a `.json` on the device and shows what it would add or change
  before anything is applied. You choose which entries to take.
- **Export your files** shares your imported entries (and edited ones) as one `.json`.
- **Reset to built-in** drops downloaded updates and your files.

Files layer on top of each other: **built-in**, then **official** updates, then **your files** in
import order. A later layer replaces an entry with the same `id`, and takes over any package another
entry claimed. Your own edits to an emulator in the app still win over every file.

## 2. The file

```jsonc
{
  "format": "pfp-emulator-kb",   // required, exactly this
  "schemaVersion": 1,            // required
  "version": 0,                  // your files and exports use 0 (only the built-in and official ones count up)
  "label": "My emulators",       // shown in the list of imported files
  "minAppVersion": 0,
  "emulators": [ /* entries, §3 */ ],
  "platforms": [ /* optional, §5 */ ]
}
```

Each entry stands alone: a broken one is refused with a reason and the rest of the file still
imports.

## 3. An emulator entry

```jsonc
{
  "id": "xendroid",                       // a-z 0-9 _, 2-48 chars; replaces a built-in entry with the same id
  "name": "XenDroid",                     // up to 64 chars
  "packageNames": ["xendroid.compose", "xendroid.compose.debug"],   // up to 8; the first installed wins
  "platformIds": ["x360"],                // consoles it runs; ids the app does not know are dropped
  "launch": { /* §4 */ },
  "launchByPackage": { },                 // optional: a complete launch for ONE of the packages above
  "signerSha256": []                      // optional: 64-hex-digit signing-certificate pins
}
```

`legacyIds` (retiring an old id) is honoured only from the built-in and official files.

## 4. `launch`

| Field | Meaning |
|---|---|
| `intentType` | `ACTION_VIEW` (default): open the game file with the app. `COMPONENT`: start a named activity. |
| `activityClass` | Fully qualified activity, e.g. `xendroid.compose.EmulatorHostActivity`. Required for `COMPONENT`. |
| `action` | Intent action for `COMPONENT` (default `android.intent.action.MAIN`). |
| `category` | Optional intent category. |
| `extras` | String extras, `key → value`. Each value is ONE placeholder (§4.1) or a plain literal. |
| `boolExtras` | Boolean extras, `key → true/false`. |
| `arrayExtras` | String-array extras, `key → [values]`, each item a placeholder or literal. |
| `flags` | Any of `NEW_TASK`, `CLEAR_TOP`, `CLEAR_TASK`. |
| `attachRomData` | `COMPONENT` only: also pass the game's `content://` URI as the intent data. |
| `mimeType` | `ACTION_VIEW` MIME type (default `application/octet-stream`). |
| `useSafUri` | `ACTION_VIEW`: hand over the game's storage-picker URI rather than a file path. |
| `dataUri` | `COMPONENT` only: a deep link as the intent data, holding exactly one game placeholder (§4.2). |

Every `COMPONENT` launch has to deliver the game somehow: a `{rom_…}` extra, `attachRomData`, a
`dataUri`, or `{title_id}` for emulators that boot an installed title.

### 4.1 Placeholders

| Placeholder | Becomes |
|---|---|
| `{rom_path}` | The game's file path, e.g. `/storage/emulated/0/Roms/x360/Halo 3.iso` |
| `{rom_uri}` | The game's `content://` URI (read access is granted to the emulator) |
| `{rom_name}` | The file name without its extension |
| `{rom_dir}` | The folder the file is in |
| `{rom_file_uri}` | `file://` URI of the path, escaped for use in a URI |
| `{rom_file_uri_encoded}` | The same, encoded again for use as one query-parameter value |
| `{title_id}` | The game's installed title id (Vita3K) |
| `{core_path}`, `{config_path}` | RetroArch core and config paths |
| `{package}`, `{platform}` | The emulator's package and the game's console id |

### 4.2 Examples from the built-in file

Open the file in the emulator (most emulators):

```json
"launch": { "activityClass": "org.ppsspp.ppsspp.PpssppActivity", "mimeType": "application/octet-stream", "useSafUri": true }
```

A named activity with the game in an extra:

```json
"launch": {
  "intentType": "COMPONENT",
  "activityClass": "xendroid.compose.EmulatorHostActivity",
  "action": "xendroid.intent.action.xendroid",
  "extras": { "game_uri": "{rom_path}" }
}
```

A deep link (X360 Mobile):

```json
"launch": {
  "intentType": "COMPONENT",
  "activityClass": "emu.x360mobile.com.MainActivity",
  "action": "android.intent.action.VIEW",
  "boolExtras": { "x360mobile_frontend": true },
  "flags": ["CLEAR_TASK"],
  "dataUri": "x360mobile://launch?uri={rom_file_uri_encoded}"
}
```

## 5. `platforms` (optional)

```json
"platforms": [ { "id": "psp", "romExtensions": ["chd"] } ]
```

Adds file types to a console the app already knows (1-10 of `a-z 0-9`, no dot, up to 32). It can
only **add**: a console list you never edited takes every extension; one you edited takes only the
extensions that are new since the last update, so anything you removed stays removed. New file types
show up on the next scan.

## 6. What a file can never do

A knowledge file is data, never commands. These are refused:

- `CUSTOM_COMMAND` and `SHORTCUT` launches;
- packages under `android.`, `com.android.`, `com.google.` and other system prefixes, or Play Field
  Portal's own package;
- invalid identifiers, unsupported flags or MIME types;
- an extra value that is neither one placeholder nor a plain literal (no paths, no URIs, no `:`);
- a `dataUri` that is not a `COMPONENT` launch, uses `file`, `content`, `http(s)`, `intent`,
  `javascript` or `data`, or does not carry exactly one game placeholder;
- more than the limits above (8 packages, 16 extras of each kind, 8 array items, 8 per-package
  launches).
