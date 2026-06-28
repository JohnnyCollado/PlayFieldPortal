package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.wave.WaveStyle
import com.playfieldportal.core.ui.wave.XMBWave
import kotlinx.coroutines.delay

// Boot sequence timing constants (ms)
private const val FADE_IN_MS   = 800
private const val HOLD_MS      = 1_200L
private const val WAVE_ENTER   = 600
private const val FADE_OUT_MS  = 500

@Composable
fun BootSequenceOverlay(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logoAlpha  = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(1f) }
    val waveAlpha  = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 1 — Logo fades in
        logoAlpha.animateTo(1f, animationSpec = tween(FADE_IN_MS))

        // 2 — Hold on logo
        delay(HOLD_MS)

        // 3 — Wave sweeps in simultaneously with logo fade
        waveAlpha.animateTo(1f, animationSpec = tween(WAVE_ENTER))

        // 4 — Brief hold with wave visible
        delay(400)

        // 5 — Full overlay fades out → XMB revealed beneath
        overlayAlpha.animateTo(0f, animationSpec = tween(FADE_OUT_MS))

        onComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(overlayAlpha.value)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // Wave — sweeps in during step 3
        Box(modifier = Modifier.fillMaxSize().alpha(waveAlpha.value)) {
            XMBWave(waveStyle = WaveStyle.ANIMATED)
        }

        // PFP logo
        Image(
            painter = painterResource(R.drawable.ic_pfp_logo),
            contentDescription = "Play Field Portal",
            modifier = Modifier
                .align(Alignment.Center)
                .size(176.dp)
                .alpha(logoAlpha.value),
        )

        // Subtitle
        Text(
            text = "Play Field Portal",
            color = Color.White.copy(alpha = 0.5f),
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
