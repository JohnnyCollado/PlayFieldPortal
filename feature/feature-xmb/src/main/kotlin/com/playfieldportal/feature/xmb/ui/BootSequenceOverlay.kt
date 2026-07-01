package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.playfieldportal.feature.xmb.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.wave.WaveStyle
import kotlinx.coroutines.delay

// Boot sequence timing constants (ms)
private const val FADE_IN_MS  = 800
private const val HOLD_MS     = 1_400L
private const val FADE_OUT_MS = 600

@Composable
fun BootSequenceOverlay(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Logo eases in over the wave, holds, then the whole overlay dissolves away. Because the overlay's
    // backdrop is the SAME XmbBackground the XMB draws beneath it, the fade-out only removes the logo —
    // the wave itself stays put, so the startup melts seamlessly into the menu.
    val logoAlpha    = remember { Animatable(0f) }
    val logoScale    = remember { Animatable(0.92f) }
    val overlayAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 1 — Logo eases in with a gentle settle.
        logoScale.animateTo(1f, animationSpec = tween(FADE_IN_MS))
        logoAlpha.animateTo(1f, animationSpec = tween(FADE_IN_MS))

        // 2 — Hold on the logo over the live wave.
        delay(HOLD_MS)

        // 3 — Dissolve the overlay → the identical XMB background is revealed beneath.
        overlayAlpha.animateTo(0f, animationSpec = tween(FADE_OUT_MS))

        onComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value),
        contentAlignment = Alignment.Center,
    ) {
        // Same background the XMB uses — the classic blue "Original" gradient with the soft wave folds,
        // tinted by whatever theme is active (LocalPFPColors), so boot and menu are visually identical.
        XmbBackground(waveStyle = WaveStyle.ANIMATED, modifier = Modifier.fillMaxSize())

        // PFP logo (transparent mark, sits on the wave).
        Image(
            painter = painterResource(R.drawable.pfp_boot_logo),
            contentDescription = "Play Field Portal",
            modifier = Modifier
                .align(Alignment.Center)
                .size(220.dp)
                .graphicsLayer {
                    alpha = logoAlpha.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                },
        )

        // Subtitle
        Text(
            text = "Play Field Portal",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 4.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(logoAlpha.value),
        )
    }
}
