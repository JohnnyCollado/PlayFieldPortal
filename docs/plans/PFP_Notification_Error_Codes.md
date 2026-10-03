# PFP Notification Error Codes

Approved 2026-10-01 as part of the Detailed Notes / Results notification work.

Error codes appear on **WARNING** and **ERROR** notifications only: under the title of a Notes
sheet, and in the reason line of individual items on a Results sheet. Success and info rows never
carry a code.

## Format: `AA-BNNN`

Example: `LN-4003`.

- **`AA`** is the area the error comes from.
- **`B`** is the kind of cause, which tells the user how to fix it without looking anything up.
- **`NNN`** is a sequence number within that area and cause.

### Cause digit (`B`)

| B | Cause | Who fixes it |
|---|---|---|
| **1** | Setup or settings | The user, inside PFP |
| **2** | Files and storage | The user, on the device or SD card |
| **3** | Network and accounts | The user: connection or sign-in |
| **4** | Another app (emulator or Android) | The user, in that app |
| **9** | Something unexpected inside PFP (a bug) | Copy Details and report it |

### Areas (`AA`)

| AA | Area |
|---|---|
| `LN` | Launch |
| `SC` | Scan |
| `AR` | Artwork and Metadata |
| `AC` | Achievements |
| `MD` | Media (music, photos, videos) |
| `BK` | Backup and Restore |
| `SY` | System |

## Codes

**Detected** means the app already detects this failure and only needs the code attached.
**New** means new detection work is needed. **Item** codes appear on individual rows of a
Results sheet rather than on a whole notification.

### LN: Launch

| Code | Meaning | Source today | Status |
|---|---|---|---|
| LN-1001 | No emulator assigned to this platform | n/a | New |
| LN-1002 | The selected RetroArch core isn't installed | n/a | New |
| LN-2001 | The game file is missing or unreadable | n/a | New |
| LN-4001 | Emulator not found ("Emulator not found. Is it installed?") | `LaunchDispatcher` ActivityNotFound | Detected |
| LN-4002 | Android denied permission to open the emulator | `LaunchDispatcher` SecurityException | Detected |
| LN-4003 | The emulator never came to the front | `LaunchOutcomeStatus.NEVER_FOREGROUNDED` | Detected |
| LN-9001 | Couldn't open the emulator (unexpected error) | `LaunchDispatcher` generic catch | Detected |

### SC: Scan

| Code | Meaning | Source today | Status |
|---|---|---|---|
| SC-1001 | No folder set for this Memory Card | `ScanStatus.SKIPPED_NO_SOURCE` | Detected |
| SC-1002 | A scan is already running | `ScanStatus.SKIPPED_BUSY` | Detected |
| SC-2001 | Folder access was lost (permission revoked) | n/a | New |
| SC-2002 | Folder no longer exists (SD card removed?) | n/a | New |
| SC-2003 | Unknown system folder under ROM Root | n/a | New, Item |
| SC-2004 | File not recognised | n/a | New, Item |
| SC-9001 | Scan failed unexpectedly | `ScanStatus.FAILED` | Detected |

### AR: Artwork and Metadata

| Code | Meaning | Source today | Status |
|---|---|---|---|
| AR-2001 | Couldn't save the artwork file | n/a | New |
| AR-2002 | Export or migration copy failed | `ArtworkExportWorker`, `InternalArtworkMigrationWorker` failed counts | New, Item |
| AR-2003 | Import found an unknown system folder | `ImportSummary.unknownSystemFolders` | Detected, Item |
| AR-3001 | Couldn't reach the artwork source | n/a | New |
| AR-3002 | The source rejected the request (rate limit or key) | n/a | New |
| AR-3003 | No match found for this game | n/a | New, Item |
| AR-9001 | Artwork or metadata fetch failed unexpectedly | XMB scrape/metadata catch, `MetadataScrapeWorker` | Detected |

### AC: Achievements

| Code | Meaning | Source today | Status |
|---|---|---|---|
| AC-1001 | No achievement set matched this game | n/a | New, Item |
| AC-2001 | The local Windows achievements folder is unreadable | n/a | New |
| AC-3001 | Offline | `UpdatePause.Offline` | Detected |
| AC-3002 | Sign-in expired (the provider is named in the details) | `UpdatePause.Credentials` | Detected |
| AC-3003 | Steam Game Details are private | `UpdatePause.SteamPrivate` | Detected |
| AC-3004 | A game couldn't update; try again | `AchievementUpdateReporter` failed count | Detected, Item |

### MD: Media

| Code | Meaning | Source today | Status |
|---|---|---|---|
| MD-2001 | Media folder unreadable | n/a | New |
| MD-9001 | Media scan failed unexpectedly | `WizardMediaScanRunner` `tasks.fail` | Detected |

### BK: Backup and Restore

| Code | Meaning | Source today | Status |
|---|---|---|---|
| BK-1001 | Restore skipped an item | `BackupManager` `refusals` | Detected, Item |
| BK-2001 | Couldn't write the backup file | `BackupManager` backup failure | Detected |
| BK-2002 | The backup file is unreadable or damaged | n/a | New |

### SY: System

| Code | Meaning | Source today | Status |
|---|---|---|---|
| SY-9001 | Unexpected error (fallback for any failure without a specific code) | n/a | New |

## Rules

- **One registry.** Every code lives in a single enum in core-domain, e.g.
  `PfpErrorCode(area, number, title, whatYouCanDo)`. The enum also supplies each Notes sheet's
  "What you can do" text, so help copy is written once per code.
- **Codes are never reused or renumbered.** A retired code stays reserved.
- **Codes go in the log.** Each code is written to Timber next to its message, so a code in a
  screenshot can be matched to a log line.
- **New codes** take the next free number in their area and cause, and are added to this file in
  the same change.
