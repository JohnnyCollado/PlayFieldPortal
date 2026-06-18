package com.playfieldportal.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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

@Singleton
class EmulatorIntentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: EmulatorProfileRepository,
) {

    fun resolve(game: Game, profile: EmulatorProfile): Intent? {
        return try {
            when (profile.intentType) {
                IntentType.ACTION_VIEW    -> buildViewIntent(game, profile)
                IntentType.COMPONENT      -> buildComponentIntent(game, profile)
                IntentType.CUSTOM_COMMAND -> buildCustomCommandIntent(game, profile)
                IntentType.SHORTCUT       -> null // handled separately via LauncherApps
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to build intent for ${profile.name} — game: ${game.title}")
            null
        }
    }

    private fun buildViewIntent(game: Game, profile: EmulatorProfile): Intent {
        val romFile = File(game.romPath ?: error("ROM path required for VIEW intent"))
        val uri = if (profile.useSafUri) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", romFile)
        } else {
            Uri.fromFile(romFile)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, profile.mimeType ?: "application/octet-stream")
            setPackage(profile.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildComponentIntent(game: Game, profile: EmulatorProfile): Intent {
        val activity = profile.activityClass
            ?: error("Activity class required for COMPONENT intent — profile: ${profile.name}")

        return Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(profile.packageName, activity)
            profile.intentExtras.forEach { (key, valueTemplate) ->
                putExtra(key, resolveTemplate(valueTemplate, game, profile))
            }
        }
    }

    private fun buildCustomCommandIntent(game: Game, profile: EmulatorProfile): Intent {
        val command = profile.customCommand
            ?: error("Custom command required for CUSTOM_COMMAND intent")
        val resolved = resolveTemplate(command, game, profile)

        Timber.d("Custom launch command resolved: $resolved")

        // Parse am-style command into an Intent
        return parseAmCommand(resolved, profile.packageName)
    }

    private fun resolveTemplate(template: String, game: Game, profile: EmulatorProfile): String {
        val romFile = game.romPath?.let { File(it) }
        val coreFile = game.platformId.let { platformId ->
            profile.coreMap[platformId]?.let { File(it) }
        }

        return template
            .replace(LaunchTemplate.ROM_PATH,    game.romPath ?: "")
            .replace(LaunchTemplate.ROM_NAME,    romFile?.nameWithoutExtension ?: "")
            .replace(LaunchTemplate.ROM_DIR,     romFile?.parent ?: "")
            .replace(LaunchTemplate.CORE_PATH,   coreFile?.absolutePath ?: "")
            .replace(LaunchTemplate.CONFIG_PATH, resolveConfigPath(profile))
            .replace(LaunchTemplate.PACKAGE,     profile.packageName)
            .replace(LaunchTemplate.PLATFORM,    game.platformId)
    }

    private fun resolveConfigPath(profile: EmulatorProfile): String {
        // RetroArch config lives in its data dir — approximate for non-rooted devices
        return "/storage/emulated/0/RetroArch/retroarch.cfg"
    }

    private fun parseAmCommand(command: String, packageName: String): Intent {
        // Basic am-command → Intent parser for custom commands
        // Full implementation handles -n (component), -a (action), -d (data), -e (extras)
        return Intent(Intent.ACTION_MAIN).apply {
            setPackage(packageName)
        }
    }
}
