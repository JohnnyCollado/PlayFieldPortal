// JNI bridge to the Discord Social SDK (C++ API in discordpp.h).
//
// M0 scope: a single liveness stub. Its only job is to prove the toolchain end-to-end —
// that the prefab headers resolve, DISCORDPP_IMPLEMENTATION compiles, and the translation
// unit links against libdiscord_partner_sdk.so. The real Client wrapper, RunCallbacks() pump
// thread, and full JNI surface arrive in M1+.
//
// DISCORDPP_IMPLEMENTATION must be defined in exactly ONE translation unit before including
// the header — it emits the SDK's inline C++ implementation (which calls the C ABI in the .so).
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSdkVersion(
    JNIEnv* env, jobject /* thiz */) {
    // Reference an SDK symbol so the linker must resolve against the vendored .so, making this
    // a genuine link-time check rather than a bare string return.
    const std::string scopes = discordpp::Client::GetDefaultPresenceScopes();
    const std::string info = "Discord Social SDK linked · default presence scopes: " + scopes;
    return env->NewStringUTF(info.c_str());
}
