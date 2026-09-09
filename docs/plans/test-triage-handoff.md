# Handoff: two broken tests in `PlayFieldPortal`

Context gathered 2026-09-09 while verifying the `more-customization` branch
(text-legibility / font-colour work). **Neither issue is caused by that branch** — both were
confirmed against a clean baseline or traced to an earlier commit. They are unrelated to each
other; take them in either order.

Repo: `D:\NEXTJJEN\repos\PlayFieldPortal`, branch `more-customization`, baseline commit `2be0241`.

## Working constraints for this repo

- **Don't run `./gradlew --stop`.** It kills the daemon the user's own terminal is using.
- **Ask before running Gradle builds.** The user prefers to run them and paste results.
- Android SDK platform-tools: `C:\Users\johnn\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- JDK (for `jps`/`jstack`): `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\`
- Use `--continue` on multi-module test runs, or the first failing task aborts the rest.

---

# Issue 1 — `AudioSettingsViewModelTest` hangs indefinitely

**Impact:** the entire `:feature:feature-settings` unit-test suite is unrunnable. Running the whole
module stalls at ~48 tests completed and never returns.

**Reproduce:**

```bash
./gradlew :feature:feature-settings:testDebugUnitTest --tests '*AudioSettingsViewModelTest*'
```

Stalls after `2 tests completed`. Silent, indefinite, no CPU.

File: `feature/feature-settings/src/test/kotlin/com/playfieldportal/feature/settings/viewmodel/AudioSettingsViewModelTest.kt`

## What is already ruled out

| Ruled out | Evidence |
|---|---|
| Caused by the current branch | Reproduces on a clean detached worktree at `2be0241` with no working-tree changes |
| Cross-class interaction / shared test JVM | Hangs when the class is run alone via `--tests` |
| The test bodies themselves | Every wait goes through the class's private `eventually` helper (~line 87), which has a hard 5 s deadline and throws `AssertionError` on timeout. No assertion loop can spin forever. |
| Stale Gradle cache | A `--no-build-cache --rerun-tasks` run cleared a genuinely corrupt artifact earlier; the hang predates and survives that |
| Other modules | `:feature:feature-xmb`, `:core:core-ui`, `:core:theme-kit`, `:core:core-domain`, `:feature:feature-backup` all pass. `:core:core-data` is 208/209 — that one failure is Issue 2 below. |

The class was introduced/modified in `2be0241` ("Add controller shortcuts and a helper footer to
Sound settings"). Its predecessor commit is `52c3b94`.

## Leading hypothesis — UNVERIFIED, get a thread dump first

The hang is most likely in `@Before` (line 58):

```kotlin
runBlocking { context.pfpDataStore.edit { it.clear() } }
```

`@Before` runs per test *method*, which fits a stall on the third method rather than the first.

`pfpDataStore` is a `preferencesDataStore` property delegate
(`core/core-data/src/main/kotlin/com/playfieldportal/core/data/datastore/PfpDataStore.kt:9`) that
caches a single process-global DataStore on first access. Robolectric gives each test method a
fresh `Application` with a fresh temp `filesDir`, but the cached DataStore keeps pointing at the
first one. Meanwhile each test's `runTest` cancels its `backgroundScope` uiState collector while
DataStore's writer coroutine is still live. A `runBlocking` `edit` blocking on a mutex held by a
cancelled-but-unreleased writer would produce exactly this symptom.

DataStore version is `1.2.1` (`gradle/libs.versions.toml:14`). Note 1.1+ normally *throws*
"multiple DataStores active for the same file" rather than deadlocking, so if the dump contradicts
this hypothesis, that is useful information — do not force-fit it.

**Alternative candidate the dump also distinguishes:**
`feature/feature-settings/build.gradle.kts:32-38` points the test JVM at the Windows trust store
because Robolectric fetches its Android image over HTTPS through the user's Avast interception. A
stalled image download looks identical from the outside.

## Next step — the decisive evidence

Run the reproduce command; while it is stuck, from a **second** terminal:

```bash
"C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\jps.exe" -l
```

```bash
"C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\jstack.exe" <GradleWorkerMain-pid>
```

The dump names the blocked thread outright and ends the guessing.

## Gotcha

JUnit4's default method sorter is MD5-hash based, not source order — "the third test" is **not**
the third method in the file. Get the actual name from the dump or from
`feature/feature-settings/build/reports/tests/testDebugUnitTest/index.html`.

---

# Issue 2 — `UiMediaStoreTest` "oversized pick is rejected without keeping a copy" FAILS

**Impact:** one hard failure in `:core:core-data` (209 tests, 1 failed). Will fail CI on whatever
merges next.

**Reproduce:**

```bash
./gradlew :core:core-data:testDebugUnitTest --tests '*UiMediaStoreTest*'
```

```
UiMediaStoreTest > oversized pick is rejected without keeping a copy FAILED
    java.lang.AssertionError at UiMediaStoreTest.kt:177
```

File: `core/core-data/src/test/kotlin/com/playfieldportal/core/data/repository/UiMediaStoreTest.kt:176-185`

```kotlin
@Test
fun `oversized pick is rejected without keeping a copy`() = runTest {
    probeReturns(100L)
    // 3 MB of bytes against the 2 MB sound cap: the capped read backstops the descriptor.
    val big = ByteArray(3 * 1024 * 1024)
    val result = store.import(UiMediaSlot.SOUND_SCROLL, register(big, name = "big.wav"))

    assertFalse(result.ok)                                  // line 183
    assertTrue(mediaDir().listFiles().isNullOrEmpty())      // line 184
}
```

(Line 177 in the stack trace is the `fun ... = runTest {` declaration — Kotlin attributes the
lambda frame there. The assertion that actually fires is 183 or 184; the HTML report says which.)

## Diagnosis — high confidence: the test is stale, not the product

**The 2 MB sound cap the test's comment describes no longer exists.**
`core/theme-kit/src/main/kotlin/com/playfieldportal/themekit/UiMediaLimits.kt` records an explicit
owner decision dated **2026-09-08**:

> AUDIO policy (owner decision, 2026-09-08): **no floor and no user-facing byte cap.**

Every sound spec now carries `AUDIO_STAGE_MAX_BYTES = 128L * 1024 * 1024` as its `maxBytes`
(`UiMediaLimits.kt:79, 84-87`), and that value is documented as a staging-copy ceiling that stops a
malicious picker — explicitly **not** a quality bar. Only `VIDEO_MAX_BYTES` (25 MB) is still a real
cap.

So a 3 MB WAV with a mocked 100 ms duration now passes both gates legitimately: `result.ok` is
`true`, and `assertFalse(result.ok)` fails.

This is the same commit family as Issue 1 — `2be0241` / `52c3b94` reworked the sound set and the
limits. The test was not updated with the policy change.

## Suggested fix — confirm before applying

Decide what the test is *for* now that the audio cap is 128 MB:

1. **Re-point it at the real ceiling.** Assert the staging cap still rejects and cleans up, using a
   size above `AUDIO_STAGE_MAX_BYTES`. Allocating 128 MB+ in a unit test is not acceptable — better
   to inject the limit or test the video path instead.
2. **Move it to the video slot,** where `VIDEO_MAX_BYTES` (25 MB) is still a genuine cap. Same
   problem of allocation size, but smaller.
3. **Retire the byte-cap assertion and keep the cleanup assertion,** rewriting the test around a
   rejection that still happens (e.g. the `unsupported mime is rejected before any copy` path
   immediately below it already covers "rejected without keeping a copy").

Option 3 is probably the honest one — the behaviour the test names ("without keeping a copy") is
still worth pinning, but "oversized" is no longer how you trigger a rejection for sounds.

**Verify the diagnosis first** by reading `UiMediaStore.import`'s gate order and confirming which
of lines 183/184 fires. If 184 fires rather than 183, the story is different — a copy is being left
behind — and that *would* be a real product bug worth taking seriously.

---

# Pre-existing warnings (low priority, not from the current branch)

Unnecessary `!!` on non-null receivers, all from commit `26a3e09` or earlier:

- `core/theme-kit/src/test/kotlin/com/playfieldportal/themekit/PfpThemeCodecV3Test.kt:279`
- `core/core-data/src/test/kotlin/com/playfieldportal/core/data/repository/PfpThemeStoreTest.kt:83`
- `core/core-data/src/test/kotlin/com/playfieldportal/core/data/repository/PfpThemeStoreV3Test.kt:70`
- `feature/feature-settings/src/test/kotlin/com/playfieldportal/feature/settings/viewmodel/DisplaySettingsViewModelWallpaperTest.kt:184`

Worth a single sweep, but deliberately left out of the legibility branch to keep its diff honest.
