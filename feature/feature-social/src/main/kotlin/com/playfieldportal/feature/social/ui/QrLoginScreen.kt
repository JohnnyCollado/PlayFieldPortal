package com.playfieldportal.feature.social.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Text("Sign in with Discord", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Image(bitmap = qr, contentDescription = "Discord sign-in QR code", modifier = Modifier.size(260.dp))
        Text(
            challenge.userCode,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Scan with your phone’s camera, or go to discord.com/activate and enter the code.",
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
