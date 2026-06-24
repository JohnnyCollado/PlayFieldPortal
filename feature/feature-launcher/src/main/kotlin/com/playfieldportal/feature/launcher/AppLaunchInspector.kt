package com.playfieldportal.feature.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A launchable installed app the user can pick as the basis for a custom emulator profile. */
data class DetectableApp(
    val packageName: String,
    val label: String,
)

enum class DetectionConfidence {
    KNOWN,       // matched the curated catalog — fields are reliable
    BEST_GUESS,  // inferred from the app's declared ACTION_VIEW handler
    MINIMAL,     // nothing detectable — user must fill it in
}

/** A pre-filled draft profile plus how confident the detection is, for the wizard banner. */
data class EmulatorSuggestion(
    val profile: EmulatorProfile,
    val confidence: DetectionConfidence,
    val note: String,
)

/**
 * Assisted custom-emulator setup. Lists installed apps, and for a chosen package produces a
 * best-effort [EmulatorProfile] draft by (1) matching the curated [KnownEmulatorCatalog], else
 * (2) inspecting the app's declared ACTION_VIEW handlers via [PackageManager], else (3) a minimal
 * stub for manual completion. Android can't reveal custom extra keys for unknown apps, so this is
 * intentionally assisted — the user reviews, optionally test-launches, and edits before saving.
 */
@Singleton
class AppLaunchInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun listLaunchableApps(): List<DetectableApp> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(main, 0)
                .mapNotNull { ri ->
                    val pkg = ri.activityInfo?.applicationInfo?.packageName ?: return@mapNotNull null
                    if (pkg == context.packageName) return@mapNotNull null
                    DetectableApp(pkg, ri.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }.getOrElse {
            Timber.e(it, "Failed to list launchable apps")
            emptyList()
        }
    }

    fun suggestForPackage(packageName: String): EmulatorSuggestion {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)

        // 1) Curated catalog — exact, reliable fields (RetroArch cores come from auto-config, not here).
        KnownEmulatorCatalog.entries.firstOrNull { packageName in it.packageNames }?.let { known ->
            return EmulatorSuggestion(
                profile = EmulatorProfile(
                    id                   = DRAFT_ID,
                    name                 = known.suggestedName,
                    packageName          = packageName,
                    activityClass        = known.activityClass,
                    intentType           = known.intentType,
                    supportedPlatformIds = known.platformIds,
                    intentExtras         = known.intentExtras,
                    intentBoolExtras     = known.intentBoolExtras,
                    intentAction         = known.intentAction,
                    intentFlags          = known.intentFlags,
                    intentCategory       = known.intentCategory,
                    mimeType             = known.mimeType,
                    useSafUri            = known.useSafUri,
                    useFileUri           = !known.useSafUri,
                    isCustom             = true,
                ),
                confidence = DetectionConfidence.KNOWN,
                note = "Auto-filled from the built-in profile for ${known.suggestedName}. " +
                    "Confirm the platform(s) and save.",
            )
        }

        // 2) Inspect declared ACTION_VIEW handlers (the Eden/AzaharPlus pattern).
        val viewComponent = resolveViewComponent(packageName)
        if (viewComponent != null) {
            return EmulatorSuggestion(
                profile = EmulatorProfile(
                    id                   = DRAFT_ID,
                    name                 = label,
                    packageName          = packageName,
                    activityClass        = viewComponent,
                    intentType           = IntentType.ACTION_VIEW,
                    supportedPlatformIds = emptyList(),
                    mimeType             = OCTET_STREAM,
                    useSafUri            = true,
                    useFileUri           = false,
                    isCustom             = true,
                ),
                confidence = DetectionConfidence.BEST_GUESS,
                note = "Best-guess from this app's ACTION_VIEW handler. Set the platform(s), then " +
                    "test a ROM before saving.",
            )
        }

        // 3) Nothing detectable — minimal stub for manual completion.
        return EmulatorSuggestion(
            profile = EmulatorProfile(
                id                   = DRAFT_ID,
                name                 = label,
                packageName          = packageName,
                intentType           = IntentType.ACTION_VIEW,
                supportedPlatformIds = emptyList(),
                mimeType             = OCTET_STREAM,
                useSafUri            = true,
                useFileUri           = false,
                isCustom             = true,
            ),
            confidence = DetectionConfidence.MINIMAL,
            note = "Couldn't detect how this app accepts ROMs. Fill in the activity / intent " +
                "settings manually, then test a ROM.",
        )
    }

    // Returns the exported activity that handles ACTION_VIEW for a content:// octet-stream ROM,
    // or null if the app declares no such handler.
    private fun resolveViewComponent(packageName: String): String? {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://com.playfieldportal.probe/rom.bin"), OCTET_STREAM)
            .setPackage(packageName)
        return runCatching {
            pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()?.activityInfo?.name
        }.getOrNull()
    }

    private companion object {
        const val DRAFT_ID = "__draft__"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
