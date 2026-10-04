package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.playfieldportal.core.ui.sound.AmbienceSuppressor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt reach-through for composables that have no constructor to inject into: the screens and
 * overlays that hold the launcher's background music down while they make their own sound.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AmbienceHoldEntryPoint {
    fun ambienceSuppressor(): AmbienceSuppressor
}

/**
 * Keeps ambience down for as long as this is composed and [active], under [owner] (one of
 * AmbienceController's OWNER_* names, so two holders never release each other). Scoped to
 * composition, so backing out of the screen gives the music back by construction.
 */
@Composable
fun HoldAmbience(owner: String, active: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(owner, active) {
        if (!active) return@DisposableEffect onDispose { }
        val ambience = EntryPointAccessors
            .fromApplication(context.applicationContext, AmbienceHoldEntryPoint::class.java)
            .ambienceSuppressor()
        ambience.setSuppressed(owner, true)
        onDispose { ambience.setSuppressed(owner, false) }
    }
}
