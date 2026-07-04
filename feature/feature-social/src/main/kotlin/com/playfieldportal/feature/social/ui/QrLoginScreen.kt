package com.playfieldportal.feature.social.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.discord.DeviceAuthChallenge
import com.playfieldportal.core.domain.discord.DeviceLoginState
import kotlinx.coroutines.delay

/**
 * QR login (the primary, couch-first sign-in). Shows a QR the user scans with their phone's Discord
 * app, then polls until approval. [onConnected] fires when the SDK session is live; [onCancel] backs
 * out (also cancels the poll).
 */
@Composable
fun QrLoginScreen(
    onConnected: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrLoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is DeviceLoginState.Success) onConnected()
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            DeviceLoginState.Requesting -> Status("Preparing sign-in…", showSpinner = true)

            is DeviceLoginState.AwaitingApproval -> QrPrompt(
                challenge = s.challenge,
                onCancel = { viewModel.cancel(); onCancel() },
            )

            is DeviceLoginState.Success -> Status("Connected", showSpinner = false)

            DeviceLoginState.Expired ->
                Retry("The code expired.", onRetry = viewModel::start, onCancel = onCancel)

            DeviceLoginState.Denied ->
                Retry("Sign-in was declined.", onRetry = viewModel::start, onCancel = onCancel)

            is DeviceLoginState.Error ->
                Retry("Couldn’t sign in. ${s.message}", onRetry = viewModel::start, onCancel = onCancel)
        }
    }
}

@Composable
private fun QrPrompt(challenge: DeviceAuthChallenge, onCancel: () -> Unit) {
    // White QR on transparent so it reads on the dark XMB background.
    val qr = remember(challenge.verificationUriComplete) {
        QrEncoder.encode(challenge.verificationUriComplete, sizePx = 512).asImageBitmap()
    }

    var remaining by remember(challenge) { mutableIntStateOf(challenge.expiresInSeconds) }
    LaunchedEffect(challenge) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    // Tap-to-copy: lets the user drop the code straight into a browser instead of typing it. The
    // "Copied!" hint shows briefly, then reverts.
    val clipboard = LocalClipboardManager.current
    var copied by remember(challenge) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val onCopy = {
        clipboard.setText(AnnotatedString(challenge.userCode))
        copied = true
    }

    // Landscape handhelds are short: a stacked column pushes the code + instructions off-screen
    // below the QR. Put the QR beside the details when wide, stack them when tall, and always allow
    // scrolling so nothing can be clipped on very small screens.
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        val qrSize = if (landscape) minOf(maxHeight * 0.82f, 260.dp) else minOf(maxWidth * 0.7f, 260.dp)
        val qrImage: @Composable () -> Unit = {
            Image(
                bitmap = qr,
                contentDescription = "Discord sign-in QR code",
                modifier = Modifier.size(qrSize),
            )
        }
        val details: @Composable () -> Unit = {
            QrDetails(
                userCode = challenge.userCode,
                remaining = remaining,
                copied = copied,
                onCopy = onCopy,
                onCancel = onCancel,
            )
        }

        if (landscape) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                qrImage()
                details()
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                qrImage()
                details()
            }
        }
    }
}

/** The text side of the sign-in prompt: title, tap-to-copy code, instructions, timer, and cancel. */
@Composable
private fun QrDetails(
    userCode: String,
    remaining: Int,
    copied: Boolean,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sign in with Discord", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            userCode,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCopy)
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            if (copied) "Copied!" else "Tap the code to copy",
            color = Color(0xCCFFFFFF),
            fontSize = 13.sp,
        )
        Text(
            "Scan with your phone’s camera, or go to discord.com/activate and paste the code.",
            color = Color(0xCCFFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            if (remaining > 0) "Expires in ${remaining}s" else "Expired",
            color = Color(0x99FFFFFF),
            fontSize = 12.sp,
        )
        Button(onClick = onCancel, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)) {
            Text("Cancel")
        }
    }
}

@Composable
private fun Status(message: String, showSpinner: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showSpinner) CircularProgressIndicator(color = Color.White)
        Text(message, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
private fun Retry(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text(message, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Try again") }
        Button(onClick = onCancel) { Text("Cancel") }
    }
}
