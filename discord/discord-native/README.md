# :discord:discord-native

NDK bridge that wraps the **Discord Social SDK** (C++ API in `discordpp.h`) and exposes a
Kotlin-friendly surface via JNI. All native/vendored concerns live here and nowhere else.

## What's vendored (Git LFS)

- `libs/discord_partner_sdk.aar` — Java glue + prebuilt `.so` per ABI + prefab C++ package.
- `libs/discord_partner_sdk_krisp.aar` — Krisp noise cancellation (voice).

These are tracked via Git LFS (see root `.gitattributes`). The **debug** aar (287 MB) and the
source zip are intentionally **not** committed. SDK version: **1.9.17379**.

## Build prerequisites

- **Android NDK `27.0.12077973`** and **CMake 3.22.1** (pinned in `build.gradle.kts`).
  Install: `sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"`.
- ABIs built: `arm64-v8a`, `armeabi-v7a`.

## Layout

- `src/main/cpp/CMakeLists.txt` — links our `discord_bridge` lib against the prefab package.
- `src/main/cpp/DiscordBridge.cpp` — JNI bridge. Defines `DISCORDPP_IMPLEMENTATION` (once).
- `src/main/kotlin/.../DiscordNativeBridge.kt` — Kotlin entry point (`System.loadLibrary`).

## Status

M0 = liveness only: proves the prefab headers resolve and the SDK links. The real client
wrapper, `RunCallbacks()` pump, auth, presence, relationships, and voice arrive in M1+.
