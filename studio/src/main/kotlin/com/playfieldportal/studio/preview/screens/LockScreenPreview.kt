package com.playfieldportal.studio.preview.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.studio.preview.XmbPreviewModel

/*
 * "The lock screen": the theme's lock screen image as the device shows it — center-cropped to the
 * screen, under a clock. Only an approximation of whatever the device's own lock screen draws on
 * top; the image and its crop are what the theme controls.
 */
@Composable
fun LockScreenPreview(model: XmbPreviewModel) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        val image = model.lockScreen
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "No lock screen image — the device keeps its own",
                color = Color(0xFF9AA3AF),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
        ) {
            Text("12:34", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Text("Sunday, October 4", color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        }
    }
}
