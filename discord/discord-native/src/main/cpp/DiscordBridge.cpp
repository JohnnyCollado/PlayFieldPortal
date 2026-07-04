// JNI bridge to the Discord Social SDK (C++ API in discordpp.h).
//
// Threading model: the SDK is single-threaded. A dedicated "pump" thread owns the client, drains
// a task queue, and calls discordpp::RunCallbacks() in a loop — so every Client call and every SDK
// callback runs on that one thread. JNI methods marshal work onto the pump via post() and, where a
// result is needed, block on a std::future until the SDK's callback fulfils it.
//
// M1 scope: init + UpdateToken(->Connect) + status + disconnect/shutdown. No tokens are logged.
//
// DISCORDPP_IMPLEMENTATION must be defined in exactly ONE translation unit before the include.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"

#include <jni.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>

namespace {

std::shared_ptr<discordpp::Client> gClient;
std::thread gPumpThread;
std::atomic<bool> gRunning{false};
// Mirrors discordpp::Client::Status (0 = Disconnected). Read by nativeGetStatus without locking.
std::atomic<int> gStatus{0};

std::mutex gTaskMutex;
std::queue<std::function<void()>> gTasks;

void post(std::function<void()> task) {
    std::lock_guard<std::mutex> lock(gTaskMutex);
    gTasks.push(std::move(task));
}

void drainTasks() {
    std::queue<std::function<void()>> local;
    {
        std::lock_guard<std::mutex> lock(gTaskMutex);
        std::swap(local, gTasks);
    }
    while (!local.empty()) {
        local.front()();
        local.pop();
    }
}

// Minimal JSON string escaping for user-provided fields (display names can contain anything).
std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", static_cast<unsigned char>(c));
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// The single thread on which the client lives: run queued SDK calls, then pump SDK callbacks.
void pumpLoop() {
    while (gRunning.load()) {
        drainTasks();
        discordpp::RunCallbacks();
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

}  // namespace

extern "C" {

// Create the client, bind the app id + status callback, and start the pump thread. Idempotent.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeInit(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong applicationId) {
    if (gClient) return;
    gClient = std::make_shared<discordpp::Client>();
    gClient->SetApplicationId(static_cast<uint64_t>(applicationId));
    gClient->SetStatusChangedCallback(
        [](discordpp::Client::Status status, discordpp::Client::Error /*error*/, int32_t /*detail*/) {
            gStatus.store(static_cast<int>(status));
        });
    gRunning.store(true);
    gPumpThread = std::thread(pumpLoop);
}

// Hand an OAuth bearer token to the SDK, then Connect on success. Blocks (off the pump thread)
// until the token exchange callback fires, or 30s elapses. Returns whether the token was accepted.
JNIEXPORT jboolean JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeUpdateToken(
    JNIEnv* env, jobject /*thiz*/, jstring jToken) {
    if (!gClient) return JNI_FALSE;

    const char* chars = env->GetStringUTFChars(jToken, nullptr);
    std::string token(chars ? chars : "");
    env->ReleaseStringUTFChars(jToken, chars);

    auto promise = std::make_shared<std::promise<bool>>();
    auto future = promise->get_future();
    post([token, promise]() {
        gClient->UpdateToken(
            discordpp::AuthorizationTokenType::Bearer, token,
            [promise](discordpp::ClientResult result) {
                if (result.Successful()) {
                    gClient->Connect();  // safe to Connect once UpdateToken completes
                    promise->set_value(true);
                } else {
                    promise->set_value(false);
                }
            });
    });

    if (future.wait_for(std::chrono::seconds(30)) != std::future_status::ready) return JNI_FALSE;
    return future.get() ? JNI_TRUE : JNI_FALSE;
}

// Current connection status (discordpp::Client::Status ordinal).
JNIEXPORT jint JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetStatus(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return gStatus.load();
}

// Returns the current user as a JSON string, or "" if the session isn't Ready yet
// (GetCurrentUserV2 returns nullopt until the gateway connects). Read on the pump thread.
JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetCurrentUserJson(
    JNIEnv* env, jobject /*thiz*/) {
    if (!gClient) return env->NewStringUTF("");
    auto promise = std::make_shared<std::promise<std::string>>();
    auto future = promise->get_future();
    post([promise]() {
        auto user = gClient->GetCurrentUserV2();
        if (!user.has_value()) {
            promise->set_value("");
            return;
        }
        auto& u = user.value();
        const std::string avatarUrl = u.AvatarUrl(
            discordpp::UserHandle::AvatarType::Png, discordpp::UserHandle::AvatarType::Png);
        std::string json = "{";
        json += "\"id\":\"" + std::to_string(u.Id()) + "\",";
        json += "\"username\":\"" + jsonEscape(u.Username()) + "\",";
        json += "\"displayName\":\"" + jsonEscape(u.DisplayName()) + "\",";
        json += "\"avatarUrl\":\"" + jsonEscape(avatarUrl) + "\"";
        json += "}";
        promise->set_value(json);
    });
    if (future.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
        return env->NewStringUTF("");
    }
    const std::string result = future.get();
    return env->NewStringUTF(result.c_str());
}

// Returns the current user's friends as a JSON array (id/username/displayName/avatarUrl/status),
// or "[]" if not ready. Read on the pump thread.
JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetFriendsJson(
    JNIEnv* env, jobject /*thiz*/) {
    if (!gClient) return env->NewStringUTF("[]");
    auto promise = std::make_shared<std::promise<std::string>>();
    auto future = promise->get_future();
    post([promise]() {
        auto relationships = gClient->GetRelationships();
        std::string json = "[";
        bool first = true;
        for (auto& rel : relationships) {
            if (rel.DiscordRelationshipType() != discordpp::RelationshipType::Friend &&
                rel.GameRelationshipType() != discordpp::RelationshipType::Friend) {
                continue;
            }
            auto user = rel.User();
            if (!user.has_value()) continue;
            auto& u = user.value();
            const std::string avatarUrl = u.AvatarUrl(
                discordpp::UserHandle::AvatarType::Png, discordpp::UserHandle::AvatarType::Png);
            if (!first) json += ",";
            first = false;
            json += "{";
            json += "\"id\":\"" + std::to_string(u.Id()) + "\",";
            json += "\"username\":\"" + jsonEscape(u.Username()) + "\",";
            json += "\"displayName\":\"" + jsonEscape(u.DisplayName()) + "\",";
            json += "\"avatarUrl\":\"" + jsonEscape(avatarUrl) + "\",";
            // GameActivity() is the friend's rich presence FOR THIS APP only (SDK scopes it to our
            // application) — i.e. what they're doing inside Playfield Portal, if anything.
            auto activity = u.GameActivity();
            std::string actName, actDetails, actState;
            if (activity.has_value()) {
                actName = activity->Name();
                if (activity->Details().has_value()) actDetails = activity->Details().value();
                if (activity->State().has_value()) actState = activity->State().value();
            }
            json += "\"activityName\":\"" + jsonEscape(actName) + "\",";
            json += "\"activityDetails\":\"" + jsonEscape(actDetails) + "\",";
            json += "\"activityState\":\"" + jsonEscape(actState) + "\",";
            json += "\"status\":" + std::to_string(static_cast<int>(u.Status()));
            json += "}";
        }
        json += "]";
        promise->set_value(json);
    });
    if (future.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
        return env->NewStringUTF("[]");
    }
    const std::string result = future.get();
    return env->NewStringUTF(result.c_str());
}

// Broadcast this app-scoped rich presence — what friends see as "Playing …" under our application
// (the SDK scopes activity to our app id). Fire-and-forget on the pump thread; presence updates are
// best-effort and non-blocking. [details] is optional (empty = omitted).
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetActivity(
    JNIEnv* env, jobject /*thiz*/, jstring jName, jstring jDetails) {
    if (!gClient) return;
    auto readStr = [env](jstring s) -> std::string {
        if (!s) return "";
        const char* c = env->GetStringUTFChars(s, nullptr);
        std::string out(c ? c : "");
        env->ReleaseStringUTFChars(s, c);
        return out;
    };
    const std::string name = readStr(jName);
    const std::string details = readStr(jDetails);
    post([name, details]() {
        discordpp::Activity activity{};
        activity.SetType(discordpp::ActivityTypes::Playing);
        activity.SetName(name);
        if (!details.empty()) activity.SetDetails(details);
        gClient->UpdateRichPresence(std::move(activity),
                                    [](discordpp::ClientResult /*result*/) {});
    });
}

// Clear any broadcast presence (sharing turned off / signed out). Fire-and-forget on the pump.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeClearActivity(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gClient) return;
    post([]() { gClient->ClearRichPresence(); });
}

// Tear down the live session (logout), on the pump thread.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeDisconnect(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gClient) return;
    auto done = std::make_shared<std::promise<void>>();
    auto future = done->get_future();
    post([done]() {
        gClient->Disconnect();
        done->set_value();
    });
    future.wait_for(std::chrono::seconds(5));
}

// Stop the pump and destroy the client.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeShutdown(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    gRunning.store(false);
    if (gPumpThread.joinable()) gPumpThread.join();
    gClient.reset();
    gStatus.store(0);
}

}  // extern "C"
