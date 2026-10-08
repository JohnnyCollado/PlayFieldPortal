# Notification error codes

Error codes appear on **WARNING** and **ERROR** notifications only: under the title of a Notes
sheet, and in the reason line of individual items on a Results sheet. Success and info rows never
carry a code.

The codes are defined in `PfpErrorCode`
(`core/core-domain/src/main/kotlin/com/playfieldportal/core/domain/model/PfpErrorCode.kt`), which
also holds each code's title and "What you can do" help text. `PfpErrorCodeTest` checks that the
enum and the tables below list exactly the same codes, so update both in the same change.

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

**Item** codes appear on individual rows of a Results sheet rather than on a whole notification.

### LN: Launch

| Code | Meaning | Scope |
|---|---|---|
| LN-1001 | No emulator assigned to this platform | |
| LN-1002 | The selected RetroArch core isn't installed | |
| LN-2001 | The game file is missing or unreadable | |
| LN-4001 | Emulator not found ("Emulator not found. Is it installed?") | |
| LN-4002 | Android denied permission to open the emulator | |
| LN-4003 | The emulator never came to the front | |
| LN-9001 | Couldn't open the emulator (unexpected error) | |

### SC: Scan

| Code | Meaning | Scope |
|---|---|---|
| SC-1001 | No folder set for this Memory Card | |
| SC-1002 | A scan is already running | |
| SC-2001 | Folder access was lost (permission revoked) | |
| SC-2002 | Folder no longer exists (SD card removed?) | |
| SC-2003 | Unknown system folder under ROM Root | Item |
| SC-2004 | File not recognised | Item |
| SC-9001 | Scan failed unexpectedly | |

### AR: Artwork and Metadata

| Code | Meaning | Scope |
|---|---|---|
| AR-1001 | Artwork folder unavailable, so new artwork is paused | |
| AR-2001 | Couldn't save the artwork file | |
| AR-2002 | Export or migration copy failed | Item |
| AR-2003 | Import found an unknown system folder | Item |
| AR-3001 | Couldn't reach the artwork source | |
| AR-3002 | The source rejected the request (rate limit or key) | |
| AR-3003 | No match found for this game | Item |
| AR-9001 | Artwork or metadata fetch failed unexpectedly | |

### AC: Achievements

| Code | Meaning | Scope |
|---|---|---|
| AC-1001 | No achievement set matched this game | Item |
| AC-2001 | The local Windows achievements folder is unreadable | |
| AC-3001 | Offline | |
| AC-3002 | Sign-in expired (the provider is named in the details) | |
| AC-3003 | Steam Game Details are private | |
| AC-3004 | A game couldn't update; try again | Item |

### MD: Media

| Code | Meaning | Scope |
|---|---|---|
| MD-2001 | Media folder unreadable | |
| MD-9001 | Media scan failed unexpectedly | |

### BK: Backup and Restore

| Code | Meaning | Scope |
|---|---|---|
| BK-1001 | Restore skipped an item | Item |
| BK-2001 | Couldn't write the backup file | |
| BK-2002 | The backup file is unreadable or damaged | |

### SY: System

| Code | Meaning | Scope |
|---|---|---|
| SY-9001 | Unexpected error (fallback for any failure without a specific code) | |

## Rules

- **One registry.** Every code lives in `PfpErrorCode`, which also supplies each Notes sheet's
  "What you can do" text, so help copy is written once per code.
- **Codes are never reused or renumbered.** A retired code stays reserved.
- **Codes go in the log.** Each code is written to Timber next to its message, so a code in a
  screenshot can be matched to a log line.
- **New codes** take the next free number in their area and cause, and are added to the enum and
  to this file in the same change.
