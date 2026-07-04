cmake_minimum_required(VERSION 3.22.1)
project(discord_bridge LANGUAGES CXX)

# The Discord Social SDK is delivered as a prefab package inside discord_partner_sdk.aar.
# AGP (with android.buildFeatures.prefab = true) exposes it here for find_package.
find_package(discord_partner_sdk REQUIRED CONFIG)

# The JNI bridge library. M0 keeps it to a single liveness stub; the real client wrapper,
# callback pump, and JNI surface are added in later milestones.
add_library(discord_bridge SHARED
    DiscordBridge.cpp)

target_compile_features(discord_bridge PRIVATE cxx_std_17)

target_link_libraries(discord_bridge
    discord_partner_sdk::discord_partner_sdk
    android
    log)
