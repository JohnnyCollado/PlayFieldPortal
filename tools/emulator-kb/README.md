# Emulator knowledge signing

`KbSign.java` signs and verifies the official emulator knowledge file (`emulators.json`) with a
detached Ed25519 signature. It is a single-file JDK 17 program with no dependencies:
`java tools/emulator-kb/KbSign.java <subcommand> ...`.

| Subcommand | Purpose |
| --- | --- |
| `keygen --out <dir>` | Writes `<dir>/pfp-kb.private.pem` (PKCS#8) and prints the raw public key as base64 on stdout. Refuses a directory inside any git worktree and refuses to overwrite an existing key. |
| `sign --key <pem> --in <file> --out <sig>` | Writes the base64 signature (64 bytes) of the exact bytes of `<file>`. |
| `verify --pub <base64> --in <file> --sig <sig>` | Prints `OK` (exit 0) or `FAILED: ...` (exit 1). Usage or I/O errors exit 2. |

The private key must never enter the repo, a log, a chat or an upload. `.gitignore` excludes
`*.private.pem` as a backstop only.

## How the app uses these files

- The **built-in** knowledge ships inside the APK at
  `feature/feature-launcher/src/main/assets/emulator_kb/emulators.json`, with `"version": 1` and
  `"label": "built-in"`. It is the single source of the emulator entries.
- An **official** file is downloaded from the rolling release `emulator-kb` on the public repo
  `JohnnyCollado/PlayFieldPortal`:
  `https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/emulator-kb/emulators.json`
  and the same URL with `.sig` appended (`EmulatorKbDownloader`). The body is capped at 1 MiB and
  the signature at 1 KiB.
- The app (`EmulatorKbUpdater`) verifies the signature over the exact downloaded bytes **before**
  parsing, against any key in `KbSignatureVerifier.PINNED_KEYS`. It then refuses the file when its
  `schemaVersion` is newer than the app supports, when `minAppVersion` is greater than the app's
  `versionCode`, when any entry fails validation (official files are all or nothing), or when it
  fails the **anti-rollback** gate: `version` must be greater than the built-in version and not lower
  than the highest version this device has ever accepted.
- `PINNED_KEYS` is **empty** in the current build, so updates are disabled and Settings shows
  "Not available in this build" until a key is pinned (below).

## One-time key ceremony

Do this on your own machine, offline if possible. No one else, including any assistant, should see
the private key.

1. Generate the key pair:
   ```bash
   java tools/emulator-kb/KbSign.java keygen --out "D:/pfp-kb-keys"
   ```
2. Store `pfp-kb.private.pem` outside the repo, in an encrypted location, with one offline backup
   (for example a password manager attachment and an encrypted USB drive). Never commit it, paste it
   or upload it.
3. The printed **public** key (base64) is safe to share. Add its decoded 32 bytes to
   `KbSignatureVerifier.PINNED_KEYS` and ship that in an app release. The unit test
   `KbSignatureVerifierTest` › *the production key list ships empty, which disables updates* pins
   today's empty list and must be updated in the same change. Updates work only on app versions
   that carry the key. Until a key is pinned, official updates stay disabled.

## Each knowledge release

1. **Edit the entries** in `feature/feature-launcher/src/main/assets/emulator_kb/emulators.json`,
   the single source.
2. **Set the release version and label** on the file you will publish:
   - `version` must be **greater than the built-in version** (1, unless a later app release raised
     it) **and greater than every version previously published**, otherwise devices refuse it as a
     rollback. Use `YYYYMMDDnn` (for example `2026100201`), which always increases.
   - `label` is what the status line shows (*Official v2026.10.02 · verified*), so use the date in
     `YYYY.MM.DD` form, for example `"label": "2026.10.02"`.
   - Raise `minAppVersion` to the lowest app **`versionCode`** that can read the file when it uses
     anything an older app does not understand (the current `versionCode` is in
     `app/build.gradle.kts`).
   - An official file may also carry a `platforms` list (ROM extensions to add to consoles). The
     built-in asset must not: `BuiltInKnowledgeBaseTest` requires its `platforms` to be empty.

   If you bump `version`/`label` in the asset itself, the next app build ships that file as its
   built-in layer, and every later official file must then be newer than it.
3. **Validate** the asset with the tests that guard it (`:feature:feature-launcher`):
   ```bash
   ./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*BuiltInKnowledgeBaseTest*" --tests "*KnowledgeBaseInvariantsTest*"
   ```
   `BuiltInKnowledgeBaseTest` checks the asset decodes and validates with zero refusals, ids are
   unique, legacy ids are consistent, and the version is positive and labelled.
   `KnowledgeBaseInvariantsTest` checks per-entry structure: each package belongs to one entry,
   component launches name a fully qualified activity and deliver the ROM, overrides name the
   entry's own packages, and every platform id is seeded or an alias. A published file that differs
   from the asset (a bumped version, added `platforms`) is not covered by these tests; keep the
   difference to those fields.
4. **Sign and verify the exact bytes you will upload:**
   ```bash
   java tools/emulator-kb/KbSign.java sign --key "D:/pfp-kb-keys/pfp-kb.private.pem" --in emulators.json --out emulators.json.sig
   java tools/emulator-kb/KbSign.java verify --pub <PUBLIC_KEY_BASE64> --in emulators.json --sig emulators.json.sig
   ```
5. **Upload both files** to the release `emulator-kb`. Before the first publish, create it once:
   ```bash
   gh release create emulator-kb --title "Emulator knowledge" --notes "Signed emulator knowledge base"
   ```
   Then on each release:
   ```bash
   gh release upload emulator-kb emulators.json emulators.json.sig --clobber
   ```
   Between the two uploads, a client can fetch a mismatched pair. It fails verification, keeps its
   last good copy, and succeeds on the next check.
6. **Check on a device:** *Settings › Emulators › Emulator knowledge › Check for updates*. The status
   shows the new label, marked verified.

### Byte-exact files

The signature covers bytes, not JSON, so any change — a re-save, reformatting, or a line-ending
conversion — invalidates it. `.gitattributes` marks
`feature/feature-launcher/src/main/assets/emulator_kb/*.json`, the test fixtures under
`feature/feature-launcher/src/test/resources/kb_sign/` and `*.sig` as `-text`, so git never converts
their line endings on checkout. A release copy you create elsewhere is not covered by that rule:
sign the file, verify it, and upload that same file without opening it in an editor in between.

## Key rotation

Ship the new public key alongside the old one in an app release (`PINNED_KEYS` accepts any listed
key). Once enough users have that release, sign with the new key. Remove the old key in a later
release.
