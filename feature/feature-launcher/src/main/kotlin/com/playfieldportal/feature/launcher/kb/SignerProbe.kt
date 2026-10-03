package com.playfieldportal.feature.launcher.kb

import android.content.Context
import android.content.pm.PackageManager
import com.playfieldportal.core.domain.model.emulatorkb.SignerState
import com.playfieldportal.feature.launcher.PackageManagerSignerCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compares a knowledge entry's pinned signers with the build installed on this device (AD-10), for the
 * import review. The same [PackageManagerSignerCheck] the launch gate uses, so the two cannot disagree.
 */
@Singleton
class SignerProbe @Inject constructor(@ApplicationContext private val context: Context) {
    private val check = PackageManagerSignerCheck(context)

    /** [SignerState.NotInstalled] when [packageName] is not installed (or not visible), else whether any pin matches. */
    fun probe(packageName: String, pins: List<String>): SignerState {
        try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return SignerState.NotInstalled
        }
        return if (pins.any { check.matches(packageName, it) }) SignerState.Matches else SignerState.Mismatch
    }
}
