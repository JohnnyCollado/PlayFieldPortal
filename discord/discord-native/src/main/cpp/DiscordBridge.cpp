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
#include <optional>
#include <queue>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace {

std::shared_ptr<discordpp::Client> gClient;
std::thread gPumpThread;
std::atomic<bool> gRunning{false};
// Mirrors discordpp::Client::Status (0 = Disconnected). Read by nativeGetStatus without locking.
std::atomic<int> gStatus{0};

std::mutex gTaskMutex;
std::queue<std::function<void()>> gTasks;

// ── Voice state (all touched only on the pump thread, except the atomics) ──────────
// The active call is kept alive here so its registered callbacks keep firing. StartCall returns a
// Call by value; we move it in. Reset on leave.
std::optional<discordpp::Call> gCall;
std::atomic<uint64_t> gLobbyId{0};
// Mirrors discordpp::Call::Status (0 = Disconnected). Read by nativeGetVoice* without locking.
std::atomic<int> gCallStatus{0};
// User ids currently producing sound. Written by the SDK speaking callback and read by the
// participants query — both run on the pump thread, so no lock is needed.
std::set<uint64_t> gSpeaking;

// ── Presence + lobby state (pump thread only) ─────────────────────────────────────
// Rich presence is a single Activity, so the game name (from the opt-in presence controller) and
// the voice-lobby party/join-secret must be merged into one — updatePresence() below does that.
std::string gActivityName;      // game/app name to show; empty = no game presence
std::string gActivityDetails;   // optional second line
bool gInLobby = false;
std::string gLobbySecret;       // the SDK join secret for our lobby (== the secret we joined with)
std::string gLobbyPartyId;      // party id broadcast in presence (the lobby id as a string)

// Pending activity invites + join requests received from other users (Join=invite to their lobby,
// JoinRequest=someone asking to join ours). Kept so Kotlin can list them and accept/reply by index.
std::vector<discordpp::ActivityInvite> gInvites;
// Join secret captured when the user accepts a "Join" from the Discord UI — consumed by Kotlin.
std::string gPendingJoinSecret;

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

// Rebuild the one rich-presence Activity from the current game name + lobby party. Called on the
// pump whenever either changes. When there's nothing to show (no game, not in a lobby), clears it.
// While in a lobby we always broadcast the party + join secret (required for invites/joining), even
// if the game name is hidden — the party privacy is Private, so it's only joinable via invite/request.
void updatePresence() {
    if (!gClient) return;
    if (gActivityName.empty() && !gInLobby) {
        gClient->ClearRichPresence();
        return;
    }
    discordpp::Activity activity{};
    activity.SetType(discordpp::ActivityTypes::Playing);
    activity.SetName(gActivityName.empty() ? "Playfield Portal" : gActivityName);
    if (!gActivityDetails.empty()) activity.SetDetails(gActivityDetails);
    if (gInLobby) {
        discordpp::ActivityParty party{};
        party.SetId(gLobbyPartyId);
        party.SetCurrentSize(gCall ? static_cast<int32_t>(gCall->GetParticipants().size()) : 1);
        party.SetMaxSize(8);
        party.SetPrivacy(discordpp::ActivityPartyPrivacy::Private);
        activity.SetParty(party);
        discordpp::ActivitySecrets secrets{};
        secrets.SetJoin(gLobbySecret);
        activity.SetSecrets(secrets);
    }
    gClient->UpdateRichPresence(std::move(activity), [](discordpp::ClientResult /*r*/) {});
}

// Create or join the lobby for [secret], start the call, and broadcast the joinable party presence.
// Runs on the pump. [onDone] receives the lobby id (0 on failure) — used by the blocking join path;
// pass nullptr for fire-and-forget (invite/join-request accept). Shared so every entry point behaves
// identically.
void enterLobby(const std::string& secret, std::function<void(uint64_t)> onDone) {
    if (!gClient) { if (onDone) onDone(0); return; }
    gClient->CreateOrJoinLobby(secret, [secret, onDone](discordpp::ClientResult result, uint64_t lobbyId) {
        if (!result.Successful()) { if (onDone) onDone(0); return; }
        gLobbyId.store(lobbyId);
        gSpeaking.clear();
        // Broadcast a joinable party (Private → invite/request only) so friends can be invited to
        // or request to join this lobby. The join secret is the secret we joined with.
        gInLobby = true;
        gLobbySecret = secret;
        gLobbyPartyId = std::to_string(lobbyId);
        updatePresence();
        // Audio processing (Krisp/echo/AGC/volumes/VAD) is default-off in the SDK; the Kotlin
        // controller applies the saved Voice Settings right after entry.
        discordpp::Call call = gClient->StartCall(lobbyId);
        call.SetStatusChangedCallback(
            [](discordpp::Call::Status status, discordpp::Call::Error /*e*/, int32_t /*d*/) {
                gCallStatus.store(static_cast<int>(status));
            });
        call.SetSpeakingStatusChangedCallback([](uint64_t userId, bool isPlayingSound) {
            if (isPlayingSound) gSpeaking.insert(userId);
            else gSpeaking.erase(userId);
        });
        gCall = std::move(call);
        gCallStatus.store(static_cast<int>(gCall->GetStatus()));
        if (onDone) onDone(lobbyId);
    });
}

// Read a (possibly null) jstring into a std::string.
std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
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
    // Incoming activity invites (a friend invited us to their lobby = Join) and join requests
    // (someone asking to join ours = JoinRequest) both arrive here; we queue them for Kotlin.
    gClient->SetActivityInviteCreatedCallback([](discordpp::ActivityInvite invite) {
        gInvites.push_back(std::move(invite));
    });
    // Fired when the user accepts a "Join" from the Discord app UI — we get the join secret to join.
    gClient->SetActivityJoinCallback([](std::string joinSecret) {
        gPendingJoinSecret = joinSecret;
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
            bool inLobby = false;
            if (activity.has_value()) {
                actName = activity->Name();
                if (activity->Details().has_value()) actDetails = activity->Details().value();
                if (activity->State().has_value()) actState = activity->State().value();
                // In a PFP voice lobby → their activity carries a party we can ask to join.
                inLobby = activity->Party().has_value();
            }
            json += "\"activityName\":\"" + jsonEscape(actName) + "\",";
            json += "\"activityDetails\":\"" + jsonEscape(actDetails) + "\",";
            json += "\"activityState\":\"" + jsonEscape(actState) + "\",";
            json += "\"inLobby\":" + std::string(inLobby ? "true" : "false") + ",";
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
        gActivityName = name;
        gActivityDetails = details;
        updatePresence();  // merges with the lobby party if we're in one
    });
}

// Clear the game presence (sharing turned off / signed out). If still in a lobby, the party presence
// stays so it remains joinable. Fire-and-forget on the pump.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeClearActivity(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gClient) return;
    post([]() {
        gActivityName.clear();
        gActivityDetails.clear();
        updatePresence();
    });
}

// ── Voice ─────────────────────────────────────────────────────────────────────────
// Join (or create) the voice lobby identified by [secret] and start the call. Two users passing the
// same secret land in the same room. Blocks off the pump until the lobby callback fires (or 30s).
// Returns the lobby id, or 0 on failure.
JNIEXPORT jlong JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeJoinVoice(
    JNIEnv* env, jobject /*thiz*/, jstring jSecret) {
    if (!gClient) return 0;

    const char* chars = env->GetStringUTFChars(jSecret, nullptr);
    std::string secret(chars ? chars : "");
    env->ReleaseStringUTFChars(jSecret, chars);

    auto promise = std::make_shared<std::promise<uint64_t>>();
    auto future = promise->get_future();
    post([secret, promise]() {
        enterLobby(secret, [promise](uint64_t lobbyId) { promise->set_value(lobbyId); });
    });

    if (future.wait_for(std::chrono::seconds(30)) != std::future_status::ready) return 0;
    return static_cast<jlong>(future.get());
}

// Leave the active call + lobby. Fire-and-forget on the pump; safe to call when not in a call.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeLeaveVoice(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!gClient) return;
    post([]() {
        if (gCall) {
            gClient->EndCall(gCall->GetChannelId(), []() {});
            gCall.reset();
        }
        const uint64_t lobby = gLobbyId.load();
        if (lobby) gClient->LeaveLobby(lobby, [](discordpp::ClientResult /*r*/) {});
        gLobbyId.store(0);
        gCallStatus.store(0);
        gSpeaking.clear();
        // Drop the party from presence (keeps any game activity).
        gInLobby = false;
        gLobbySecret.clear();
        gLobbyPartyId.clear();
        updatePresence();
    });
}

// Mute/unmute the local mic for the whole call. Fire-and-forget on the pump.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetSelfMute(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean mute) {
    if (!gClient) return;
    const bool m = (mute == JNI_TRUE);
    post([m]() { if (gCall) gCall->SetSelfMute(m); });
}

// Voice-activity gate: automatic lets the SDK pick the threshold; otherwise [threshold] (dB, range
// -100..0, default -60) is used. Raising it toward 0 makes the mic less sensitive, so quiet noises
// like handheld button clicks no longer open the gate. Fire-and-forget on the pump.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetVadThreshold(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean automatic, jfloat threshold) {
    if (!gClient) return;
    const bool a = (automatic == JNI_TRUE);
    const float t = threshold;
    post([a, t]() { if (gCall) gCall->SetVADThreshold(a, t); });
}

// ── Voice audio settings (Client-level; persist across calls, applied by the controller) ──────────
// Krisp AI noise cancellation — strips button clicks + background noise. Enabling it auto-disables
// the SDK's weaker basic noise suppression.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetNoiseCancellation(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    if (!gClient) return;
    const bool o = (on == JNI_TRUE);
    post([o]() { gClient->SetNoiseCancellation(o); });
}

// Acoustic echo cancellation — stops the speaker output feeding back into the mic.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetEchoCancellation(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    if (!gClient) return;
    const bool o = (on == JNI_TRUE);
    post([o]() { gClient->SetEchoCancellation(o); });
}

// Automatic gain control — normalizes mic input level.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetAutomaticGainControl(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    if (!gClient) return;
    const bool o = (on == JNI_TRUE);
    post([o]() { gClient->SetAutomaticGainControl(o); });
}

// Mic (input) volume, percentage 0..100.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetInputVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat percent) {
    if (!gClient) return;
    const float v = percent;
    post([v]() { gClient->SetInputVolume(v); });
}

// Speaker (output) volume, percentage 0..200.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetOutputVolume(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat percent) {
    if (!gClient) return;
    const float v = percent;
    post([v]() { gClient->SetOutputVolume(v); });
}

// Voice input mode: 1 = MODE_VAD (open mic / voice activity), 2 = MODE_PTT (push-to-talk — mic is
// closed until SetPTTActive(true)). Fire-and-forget on the pump.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetAudioMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jint mode) {
    if (!gClient) return;
    const int m = mode;
    post([m]() { if (gCall) gCall->SetAudioMode(static_cast<discordpp::AudioModeType>(m)); });
}

// Open/close the mic in push-to-talk mode (hold = true, release = false). Fire-and-forget.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetPttActive(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean active) {
    if (!gClient) return;
    const bool a = (active == JNI_TRUE);
    post([a]() { if (gCall) gCall->SetPTTActive(a); });
}

// Grace period (ms) the mic stays open after release, so word-endings aren't clipped. Fire-and-forget.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSetPttReleaseDelay(
    JNIEnv* /*env*/, jobject /*thiz*/, jint ms) {
    if (!gClient) return;
    const uint32_t d = static_cast<uint32_t>(ms < 0 ? 0 : ms);
    post([d]() { if (gCall) gCall->SetPTTReleaseDelay(d); });
}

// Current call status ordinal (mirrors discordpp::Call::Status; 0 = Disconnected).
JNIEXPORT jint JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetCallStatus(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return gCallStatus.load();
}

// Snapshot of the active call as JSON: { lobbyId, status, selfMute, participants:[{id, displayName,
// mute, deaf, speaking}] }, or "{}" when not in a call. Read on the pump thread.
JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetVoiceJson(
    JNIEnv* env, jobject /*thiz*/) {
    if (!gClient) return env->NewStringUTF("{}");
    auto promise = std::make_shared<std::promise<std::string>>();
    auto future = promise->get_future();
    post([promise]() {
        if (!gCall) {
            promise->set_value("{}");
            return;
        }
        std::string json = "{";
        json += "\"lobbyId\":\"" + std::to_string(gLobbyId.load()) + "\",";
        json += "\"status\":" + std::to_string(static_cast<int>(gCall->GetStatus())) + ",";
        json += std::string("\"selfMute\":") + (gCall->GetSelfMute() ? "true" : "false") + ",";
        json += "\"participants\":[";
        bool first = true;
        for (uint64_t uid : gCall->GetParticipants()) {
            auto voice = gCall->GetVoiceStateHandle(uid);
            const bool muted = voice.has_value() && voice->SelfMute();
            const bool deaf = voice.has_value() && voice->SelfDeaf();
            const bool speaking = gSpeaking.count(uid) > 0;
            std::string name;
            auto user = gClient->GetUser(uid);
            if (user.has_value()) name = user->DisplayName();
            if (!first) json += ",";
            first = false;
            json += "{";
            json += "\"id\":\"" + std::to_string(uid) + "\",";
            json += "\"displayName\":\"" + jsonEscape(name) + "\",";
            json += std::string("\"mute\":") + (muted ? "true" : "false") + ",";
            json += std::string("\"deaf\":") + (deaf ? "true" : "false") + ",";
            json += std::string("\"speaking\":") + (speaking ? "true" : "false");
            json += "}";
        }
        json += "]}";
        promise->set_value(json);
    });
    if (future.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
        return env->NewStringUTF("{}");
    }
    const std::string result = future.get();
    return env->NewStringUTF(result.c_str());
}

// ── Lobby invites & join requests ──────────────────────────────────────────────────
// Invite a friend to our lobby. [content] is a short message shown with the invite.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeInviteFriend(
    JNIEnv* env, jobject /*thiz*/, jlong userId, jstring jContent) {
    if (!gClient) return;
    const uint64_t uid = static_cast<uint64_t>(userId);
    const std::string content = jstr(env, jContent);
    post([uid, content]() { gClient->SendActivityInvite(uid, content, [](discordpp::ClientResult /*r*/) {}); });
}

// Ask to join a friend's lobby (they must approve). The friend must be in a joinable PFP party.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeSendJoinRequest(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong userId) {
    if (!gClient) return;
    const uint64_t uid = static_cast<uint64_t>(userId);
    post([uid]() { gClient->SendActivityJoinRequest(uid, [](discordpp::ClientResult /*r*/) {}); });
}

// Pending invites + join requests as JSON: [{index, type(1=invite,5=joinRequest), senderId, senderName}].
JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeGetInvitesJson(
    JNIEnv* env, jobject /*thiz*/) {
    if (!gClient) return env->NewStringUTF("[]");
    auto promise = std::make_shared<std::promise<std::string>>();
    auto future = promise->get_future();
    post([promise]() {
        std::string json = "[";
        for (size_t i = 0; i < gInvites.size(); ++i) {
            const uint64_t sid = gInvites[i].SenderId();
            const int type = static_cast<int>(gInvites[i].Type());
            std::string name;
            auto user = gClient->GetUser(sid);
            if (user.has_value()) name = user->DisplayName();
            if (i) json += ",";
            json += "{\"index\":" + std::to_string(i) + ",\"type\":" + std::to_string(type) +
                    ",\"senderId\":\"" + std::to_string(sid) + "\",\"senderName\":\"" + jsonEscape(name) + "\"}";
        }
        json += "]";
        promise->set_value(json);
    });
    if (future.wait_for(std::chrono::seconds(5)) != std::future_status::ready) {
        return env->NewStringUTF("[]");
    }
    return env->NewStringUTF(future.get().c_str());
}

// Accept an invite (type Join) → join that friend's lobby via the returned join secret.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeAcceptInvite(
    JNIEnv* /*env*/, jobject /*thiz*/, jint index) {
    if (!gClient) return;
    post([index]() {
        if (index < 0 || static_cast<size_t>(index) >= gInvites.size()) return;
        discordpp::ActivityInvite invite = std::move(gInvites[static_cast<size_t>(index)]);
        gInvites.erase(gInvites.begin() + index);
        gClient->AcceptActivityInvite(std::move(invite),
            [](discordpp::ClientResult result, std::string joinSecret) {
                if (result.Successful() && !joinSecret.empty()) enterLobby(joinSecret, nullptr);
            });
    });
}

// Approve a join request (type JoinRequest) → the requester may now join our lobby.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeApproveJoinRequest(
    JNIEnv* /*env*/, jobject /*thiz*/, jint index) {
    if (!gClient) return;
    post([index]() {
        if (index < 0 || static_cast<size_t>(index) >= gInvites.size()) return;
        discordpp::ActivityInvite invite = std::move(gInvites[static_cast<size_t>(index)]);
        gInvites.erase(gInvites.begin() + index);
        gClient->SendActivityJoinRequestReply(std::move(invite), [](discordpp::ClientResult /*r*/) {});
    });
}

// Dismiss an invite / decline a join request without acting on it.
JNIEXPORT void JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeDismissInvite(
    JNIEnv* /*env*/, jobject /*thiz*/, jint index) {
    if (!gClient) return;
    post([index]() {
        if (index >= 0 && static_cast<size_t>(index) < gInvites.size()) {
            gInvites.erase(gInvites.begin() + index);
        }
    });
}

// The join secret captured when the user accepted a "Join" from the Discord app UI (or ""), and
// clears it. The caller joins with it so the Kotlin path applies the saved audio settings.
JNIEXPORT jstring JNICALL
Java_com_playfieldportal_discord_DiscordNativeBridge_nativeConsumePendingJoin(
    JNIEnv* env, jobject /*thiz*/) {
    if (!gClient) return env->NewStringUTF("");
    auto promise = std::make_shared<std::promise<std::string>>();
    auto future = promise->get_future();
    post([promise]() {
        std::string s = gPendingJoinSecret;
        gPendingJoinSecret.clear();
        promise->set_value(s);
    });
    if (future.wait_for(std::chrono::seconds(2)) != std::future_status::ready) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(future.get().c_str());
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
    gCall.reset();
    gLobbyId.store(0);
    gCallStatus.store(0);
    gSpeaking.clear();
    gInLobby = false;
    gLobbySecret.clear();
    gLobbyPartyId.clear();
    gActivityName.clear();
    gActivityDetails.clear();
    gInvites.clear();
    gPendingJoinSecret.clear();
    gClient.reset();
    gStatus.store(0);
}

}  // extern "C"
