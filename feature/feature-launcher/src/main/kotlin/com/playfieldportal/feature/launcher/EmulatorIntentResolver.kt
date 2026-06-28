package com.playfieldportal.feature.launcher

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.LaunchTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Android launch [android.content.Intent] for a game + chosen emulator profile.
 *
 * Supports `ACTION_VIEW` (ROM passed as a FileProvider content URI, with type/component fallbacks
 * for emulators whose intent filters omit a MIME type), `COMPONENT` (explicit activity + extras,
 * e.g. RetroArch's `ROM`/`LIBRETRO`), and `CUSTOM_COMMAND`. Validation (emulator installed, ROM
 * exists, core configured) happens up front; [resolve] never throws — it returns a [Result] with a
 * user-readable failure message instead.
 */
@Singleton
class EmulatorIntentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Resolves a launch [Intent] for the given [game] and selected [profile].
     *
     * Returns [Result.success] with the intent on success, or [Result.failure] with a
     * user-readable message explaining why launch cannot proceed. Never throws.
     */
    fun resolve(game: Game, profile: EmulatorProfile): Result<Intent> {
        return runCatching {
            validateBeforeLaunch(game, profile)
            val intent = when (profile.intentType) {
                IntentType.ACTION_VIEW    -> buildViewIntent(game, profile)
                IntentType.COMPONENT      -> buildComponentIntent(game, profile)
                IntentType.CUSTOM_COMMAND -> buildCustomCommandIntent(game, profile)
                IntentType.SHORTCUT       -> error("Shortcut launch not supported from this screen")
            }
            Timber.d(
                "Launch intent resolved: gameId=${game.id}, title=${game.title}, platform=${game.platformId}, emulatorId=${profile.id}, emulatorName=${profile.name}, package=${profile.packageName}, intentType=${profile.intentType}, core=${profile.corePathFor(game.platformId).orEmpty()}, rom=${game.romPath.orEmpty()}, summary=${intent.toUri(Intent.URI_INTENT_SCHEME)}"
            )
            intent
        }.onFailure { e ->
            Timber.e(
                e,
                "Failed to build launch intent: gameId=${game.id}, title=${game.title}, platform=${game.platformId}, emulatorId=${profile.id}, emulatorName=${profile.name}, rom=${game.romPath.orEmpty()}"
            )
        }
    }

    fun resolveNativeApp(game: Game): Result<Intent> {
        return runCatching {
            val packageName = game.packageName
                ?: error("No Android package recorded for ${game.title}")
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: error("Android app not installed or not launchable: $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Timber.d(
                "Native game intent resolved: gameId=${game.id}, title=${game.title}, package=$packageName, summary=${intent.toUri(Intent.URI_INTENT_SCHEME)}"
            )
            intent
        }.onFailure { e ->
            Timber.e(
                e,
                "Failed to build native game launch intent: gameId=${game.id}, title=${game.title}, package=${game.packageName.orEmpty()}"
            )
        }
    }

    private fun validateBeforeLaunch(game: Game, profile: EmulatorProfile) {
        if (profile.intentType != IntentType.CUSTOM_COMMAND) {
            try {
                context.packageManager.getPackageInfo(profile.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                error("Emulator not installed: ${profile.name} (${profile.packageName})")
            }
        }

        val romPath = game.romPath ?: error("ROM path is required to launch ${game.title}")
        val romFile = File(romPath)
        if (!romFile.exists()) error("ROM file not found: $romPath")

        if (profile.intentType == IntentType.COMPONENT && profile.coreMap.isNotEmpty()) {
            val corePath = profile.corePathFor(game.platformId)
            if (corePath.isNullOrBlank()) {
                error("No RetroArch core configured for platform '${game.platformId}' in profile '${profile.name}'. Open RetroArch → Core Downloader to install a core for this system.")
            }
            // We cannot read /data/data/<pkg>/cores/ — it's the emulator's private internal
            // storage. Skip the file-existence check and let RetroArch report a missing core.
        }
    }

    private fun buildViewIntent(game: Game, profile: EmulatorProfile): Intent {
        val romPath = game.romPath ?: error("ROM path is required to launch ${game.title}")
        val romFile = File(romPath)
        val uri = resolveRomUri(romFile, profile)
        val activityClass = profile.activityClass
        val mime = profile.mimeType ?: "application/octet-stream"

        fun build(withType: Boolean, withComponent: Boolean): Intent =
            Intent(Intent.ACTION_VIEW).apply {
                // Android matching rule: if the intent sets a MIME type, the target's intent
                // filter must ALSO declare a type. Some emulators (e.g. the AzaharPlus build on
                // the Lime3DS package) declare ACTION_VIEW with only a content scheme and NO type,
                // so a typed intent fails to resolve — hence the no-type fallback.
                if (withType) setDataAndType(uri, mime) else data = uri
                if (withComponent && activityClass != null) {
                    component = ComponentName(profile.packageName, activityClass)
                } else {
                    setPackage(profile.packageName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        // Most-specific first (preserves behaviour for emulators that already work), then relax:
        // drop the MIME type (scheme-only filters), then drop the pinned component (resolve by the
        // app's own declared ACTION_VIEW handler).
        val candidates = listOf(
            build(withType = true,  withComponent = true),
            build(withType = false, withComponent = true),
            build(withType = true,  withComponent = false),
            build(withType = false, withComponent = false),
        )
        val intent = candidates.firstOrNull {
            context.packageManager.queryIntentActivities(it, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        } ?: run {
            Timber.w(
                "Intent not resolvable: profile=${profile.name}, package=${profile.packageName}, uri=${uri.scheme}://, mime=$mime, activity=${activityClass ?: "(by package)"}"
            )
            error("${profile.name} cannot open this ROM type. Try reinstalling the emulator, or verify its app permissions in Android Settings.")
        }

        intent.grantReadPermissionIfNeeded(uri, game.title, profile.packageName)
        Timber.d("View intent resolved: package=${profile.packageName}, type=${intent.type ?: "(none)"}, component=${intent.component?.shortClassName ?: "(by package)"}")
        return intent
    }

    private fun buildComponentIntent(game: Game, profile: EmulatorProfile): Intent {
        val activity = profile.activityClass
            ?: error("Activity class required for COMPONENT intent - profile: ${profile.name}")

        val needsRomUri = profile.intentExtras.values.any { it.contains(LaunchTemplate.ROM_URI) }
        val romUri: Uri? = if (needsRomUri) {
            val romFile = File(game.romPath ?: error("ROM path required for ${profile.name}"))
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", romFile)
            }.getOrNull()
        } else null

        val action = profile.intentAction ?: Intent.ACTION_MAIN
        return Intent(action).apply {
            component = ComponentName(profile.packageName, activity)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            profile.intentFlags.forEach { flag ->
                when (flag) {
                    "CLEAR_TASK" -> addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    "CLEAR_TOP"  -> addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            profile.intentCategory?.let { addCategory(it) }

            profile.intentExtras.forEach { (key, valueTemplate) ->
                putExtra(key, resolveTemplate(valueTemplate, game, profile, romUri))
            }
            profile.intentBoolExtras.forEach { (key, value) ->
                putExtra(key, value)
            }

            if (romUri != null) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(context.contentResolver, game.title, romUri)
                context.grantUriPermission(profile.packageName, romUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun buildCustomCommandIntent(game: Game, profile: EmulatorProfile): Intent {
        val command = profile.customCommand
            ?: error("Custom command required for CUSTOM_COMMAND intent")
        val resolved = resolveTemplate(command, game, profile)
        Timber.d("Custom launch command resolved: $resolved")
        return parseAmCommand(resolved, profile.packageName)
    }

    private fun resolveRomUri(romFile: File, profile: EmulatorProfile): Uri {
        if (profile.useFileUri && !profile.useSafUri && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Uri.fromFile(romFile)
        }

        if (profile.useFileUri && !profile.useSafUri) {
            Timber.d(
                "Profile ${profile.id} requests file:// ROM launch; using granted content:// URI on API ${Build.VERSION.SDK_INT}"
            )
        }

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            romFile,
        )
    }

    private fun Intent.grantReadPermissionIfNeeded(uri: Uri, title: String, packageName: String) {
        if (uri.scheme != "content") return
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, title, uri)
        context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun resolveTemplate(
        template: String,
        game: Game,
        profile: EmulatorProfile,
        romUri: Uri? = null,
    ): String {
        val romFile = game.romPath?.let { File(it) }
        val corePath = profile.corePathFor(game.platformId) ?: ""

        return template
            .replace(LaunchTemplate.ROM_PATH, game.romPath ?: "")
            .replace(LaunchTemplate.ROM_URI, romUri?.toString() ?: "")
            .replace(LaunchTemplate.ROM_NAME, romFile?.nameWithoutExtension ?: "")
            .replace(LaunchTemplate.ROM_DIR, romFile?.parent ?: "")
            .replace(LaunchTemplate.CORE_PATH, corePath)
            .replace(LaunchTemplate.CONFIG_PATH, retroarchConfigPath(profile.packageName))
            .replace(LaunchTemplate.PACKAGE, profile.packageName)
            .replace(LaunchTemplate.PLATFORM, game.platformId)
    }

    private fun retroarchConfigPath(packageName: String): String =
        "/storage/emulated/0/Android/data/$packageName/files/retroarch.cfg"

    private fun parseAmCommand(command: String, packageName: String): Intent {
        // Minimal am-start parser: extracts -e/--es key value pairs as intent extras.
        // Full am-start syntax is not supported — use COMPONENT or ACTION_VIEW profiles instead.
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val tokens = command.trim().split("\\s+".toRegex())
        var i = 0
        while (i < tokens.size) {
            when (tokens[i]) {
                "-e", "--es" -> {
                    if (i + 2 < tokens.size) {
                        intent.putExtra(tokens[i + 1], tokens[i + 2])
                        i += 3
                    } else i++
                }
                "-n" -> {
                    if (i + 1 < tokens.size) {
                        val cn = tokens[i + 1].split("/")
                        if (cn.size == 2) intent.component = ComponentName(cn[0], cn[1])
                        i += 2
                    } else i++
                }
                else -> i++
            }
        }
        Timber.d("Parsed am command: package=$packageName, extras=${intent.extras?.keySet()?.joinToString()}, component=${intent.component}")
        return intent
    }

    private fun EmulatorProfile.corePathFor(platformId: String): String? {
        for (alias in platformAliases(platformId)) {
            coreMap[alias]?.let { return normalizeRetroArchCorePath(it) }
        }
        return null
    }

    private fun EmulatorProfile.normalizeRetroArchCorePath(corePath: String): String {
        if (!packageName.startsWith("com.retroarch")) return corePath
        return corePath
            .replace("/data/data/com.retroarch.aarch64/cores/", "/data/data/$packageName/cores/")
            .replace("/data/data/com.retroarch.ra64/cores/", "/data/data/$packageName/cores/")
            .replace("/data/data/com.retroarch.ra32/cores/", "/data/data/$packageName/cores/")
            .replace("/data/data/com.retroarch/cores/", "/data/data/$packageName/cores/")
    }

    private fun platformAliases(platformId: String): List<String> = when (platformId) {
        "psx"          -> listOf("psx", "ps1")
        "ps1"          -> listOf("ps1", "psx")
        "n3ds"         -> listOf("n3ds", "3ds")
        "3ds"          -> listOf("3ds", "n3ds")
        "gc"           -> listOf("gc", "gamecube")
        "gamecube"     -> listOf("gamecube", "gc")
        "nds"          -> listOf("nds", "ds")
        "ds"           -> listOf("ds", "nds")
        "pcengine"     -> listOf("pcengine", "pce", "tgfx16")
        "pce"          -> listOf("pce", "pcengine", "tgfx16")
        "tgfx16"       -> listOf("tgfx16", "pce", "pcengine")
        "mastersystem" -> listOf("mastersystem", "sms")
        "sms"          -> listOf("sms", "mastersystem")
        "genesis"      -> listOf("genesis", "megadrive", "md")
        "megadrive"    -> listOf("megadrive", "genesis", "md")
        "md"           -> listOf("md", "genesis", "megadrive")
        "dreamcast"    -> listOf("dreamcast", "dc")
        "dc"           -> listOf("dc", "dreamcast")
        "virtualboy"   -> listOf("virtualboy", "vb")
        "vb"           -> listOf("vb", "virtualboy")
        "atarilynx"    -> listOf("atarilynx", "lynx")
        "lynx"         -> listOf("lynx", "atarilynx")
        "wonderswan"   -> listOf("wonderswan", "ws")
        "ws"           -> listOf("ws", "wonderswan")
        "wonderswancolor" -> listOf("wonderswancolor", "wsc")
        "wsc"          -> listOf("wsc", "wonderswancolor")
        "ngp"          -> listOf("ngp", "ngpc")
        "ngpc"         -> listOf("ngpc", "ngp")
        else           -> listOf(platformId)
    }
}
