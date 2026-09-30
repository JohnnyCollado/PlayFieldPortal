# Run Me

The commands you'll use most for Play Field Portal. Run every one from the **repository root**
(the folder that holds `settings.gradle.kts`). `adb` is assumed to be on your `PATH`.

The app has two flavors, so app tasks always name one:

| Variant | Gradle task suffix | Application id |
|---|---|---|
| Full, debug | `FullDebug` | `com.playfieldportal.launcher.debug` |
| Lite, debug | `LiteDebug` | `com.playfieldportal.launcher.lite.debug` |
| Full, release | `FullRelease` | `com.playfieldportal.launcher` |
| Lite, release | `LiteRelease` | `com.playfieldportal.launcher.lite` |

**Full** includes the Discord Social SDK and only runs on ARM (`arm64-v8a` / `armeabi-v7a`).
**Lite** leaves the Discord SDK out, so use it on an x86_64 emulator.

---

## 1. Setup

You need JDK 17 (`JAVA_HOME` set) and Android SDK 37 with the NDK and CMake installed. See
[README.md](README.md) §7.2 for details.

Two gitignored files live at the root:

- **`local.properties`**: `sdk.dir`, plus `screenscraper.devId` and `screenscraper.devPassword`
  (and optionally `screenscraper.obfuscationSalt`). The build still succeeds without the
  ScreenScraper keys, but ScreenScraper artwork lookups quietly turn off. **Add to this file;
  never replace it.**
- **`keystore.properties`** + **`playfieldportal-release.jks`**: release signing. Without them,
  release builds still come out, but unsigned.

Check the Gradle and JDK versions:

```bash
./gradlew --version
```

List every module and task:

```bash
./gradlew tasks --all
```

Check that a device is connected and authorized:

```bash
adb devices -l
```

---

## 2. Build

Build the Lite debug APK:

```bash
./gradlew :app:assembleLiteDebug
```

Build the Full debug APK:

```bash
./gradlew :app:assembleFullDebug
```

Build the Full release APK (signed if `keystore.properties` is present):

```bash
./gradlew :app:assembleFullRelease
```

Build the Lite release APK:

```bash
./gradlew :app:assembleLiteRelease
```

Build everything that ships (both release APKs plus the Theme Studio installer):

```bash
./gradlew dist
```

Or use the interactive release builder (it asks which flavor and checks the output):

```bash
./build-release-apk.bat
```

Where the APKs end up:

- Debug: `debug/PlayFieldPortal-<version>-<flavor>-debug.apk`
- Release: `dist/PlayFieldPortal-<version>-<flavor>.apk`

Clean build outputs (the fix for a corrupted KSP cache or stale generated code):

```bash
./gradlew clean
```

---

## 3. Install & run

Build and install the Full debug build on the connected device:

```bash
./gradlew :app:installFullDebug
```

Build and install the Lite debug build:

```bash
./gradlew :app:installLiteDebug
```

Pick an APK from `dist/` or `debug/` and a device interactively (keeps app data):

```bash
./install-apk.bat
```

Install an APK you already built, keeping app data (replace `<version>` first):

```bash
adb install -r debug/PlayFieldPortal-<version>-full-debug.apk
```

Launch the Full debug build:

```bash
adb shell monkey -p com.playfieldportal.launcher.debug -c android.intent.category.LAUNCHER 1
```

Force-stop the Full debug build:

```bash
adb shell am force-stop com.playfieldportal.launcher.debug
```

Uninstall the Full debug build (**this deletes its data**; you need it after a signature mismatch):

```bash
adb uninstall com.playfieldportal.launcher.debug
```

Show the app's logs (run this while the app is open):

```bash
adb logcat --pid=$(adb shell pidof -s com.playfieldportal.launcher.debug)
```

For Lite, swap in `com.playfieldportal.launcher.lite.debug` in the `adb` commands above.

### Theme Studio (desktop)

Run the Studio from source:

```bash
./gradlew :studio:run
```

Package a Studio installer for the current OS:

```bash
./gradlew :studio:packageReleaseDistributionForCurrentOS
```

---

## 4. Tests

These are unit tests only (JUnit 4, MockK, Turbine, Robolectric), so no device is needed.
Android modules use `testDebugUnitTest`. The pure-JVM modules (`:core:theme-kit`,
`:core:core-archive`, `:studio`) use `test`.

Run every unit test in the project:

```bash
./gradlew test
```

Run one Android module's tests (swap in any `:core:*` or `:feature:*` module):

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest
```

Run one test class (wildcards work):

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*LaunchDispatcherTest"
```

Run a single test method:

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest --tests "*LaunchDispatcherTest.shortcut launch waits for GameBoot before starting the shortcut"
```

Rerun tests even when Gradle thinks they're up to date:

```bash
./gradlew :feature:feature-launcher:testDebugUnitTest --rerun-tasks
```

Run a pure-JVM module's tests:

```bash
./gradlew :core:theme-kit:test
```

Run the Theme Studio tests:

```bash
./gradlew :studio:test
```

Run the app module's tests for one flavor:

```bash
./gradlew :app:testFullDebugUnitTest
```

Run Studio tests and launch the Studio only if they pass:

```bash
./run-theme-studio.bat --tests
```

**Reports** are HTML files inside each module's build folder:

- Android modules: `<module>/build/reports/tests/testDebugUnitTest/index.html`
- JVM modules: `<module>/build/reports/tests/test/index.html`

For example, `feature/feature-launcher/build/reports/tests/testDebugUnitTest/index.html`.
