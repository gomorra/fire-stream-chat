package com.firestream.chat.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import coil.compose.AsyncImage
import com.firestream.chat.ui.components.resolveAvatarModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.CallState
import kotlinx.coroutines.delay

@Composable
internal fun CallScreen(
    onFinish: () -> Unit,
    viewModel: CallViewModel = hiltViewModel()
) {
    val callState by viewModel.callState.collectAsState()
    val uiControls by viewModel.uiControls.collectAsState()

    // Auto-finish after call ends
    LaunchedEffect(callState) {
        if (callState is CallState.Ended) {
            delay(1500)
            onFinish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        when (val state = callState) {
            is CallState.IncomingRinging -> IncomingRingingContent(
                callerName = state.callerName,
                callerAvatarUrl = state.callerAvatarUrl,
                callerLocalAvatarPath = state.callerLocalAvatarPath,
                onAnswer = viewModel::answer,
                onDecline = viewModel::decline
            )
            is CallState.OutgoingRinging -> OutgoingRingingContent(
                calleeName = state.calleeName,
                calleeAvatarUrl = state.calleeAvatarUrl,
                calleeLocalAvatarPath = state.calleeLocalAvatarPath,
                onCancel = viewModel::hangup
            )
            is CallState.Connecting -> ConnectingContent(
                remoteName = state.remoteName,
                remoteAvatarUrl = state.remoteAvatarUrl,
                remoteLocalAvatarPath = state.remoteLocalAvatarPath,
                onHangup = viewModel::hangup
            )
            is CallState.Connected -> ConnectedContent(
                remoteName = state.remoteName,
                remoteAvatarUrl = state.remoteAvatarUrl,
                remoteLocalAvatarPath = state.remoteLocalAvatarPath,
                startTime = state.startTime,
                isMuted = uiControls.isMuted,
                isSpeakerOn = uiControls.isSpeakerOn,
                onHangup = viewModel::hangup,
                onToggleMute = viewModel::toggleMute,
                onToggleSpeaker = viewModel::toggleSpeaker
            )
            is CallState.Ended -> EndedContent()
            CallState.Idle -> {}
        }
    }
}

@Composable
private fun IncomingRingingContent(
    callerName: String,
    callerAvatarUrl: String?,
    callerLocalAvatarPath: String?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(modifier = Modifier.alpha(alpha)) {
            CallUserAvatar(callerName, callerAvatarUrl, callerLocalAvatarPath)
        }
        Spacer(Modifier.height(24.dp))
        Text(callerName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Incoming voice call", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            CallControlButton(
                icon = Icons.Default.CallEnd,
                contentDescription = "Decline",
                onClick = onDecline,
                backgroundColor = Color(0xFFE53935),
                iconTint = Color.White
            )
            CallControlButton(
                icon = Icons.Default.Call,
                contentDescription = "Answer",
                onClick = onAnswer,
                backgroundColor = Color(0xFF43A047),
                iconTint = Color.White
            )
        }
    }
}

@Composable
private fun OutgoingRingingContent(
    calleeName: String,
    calleeAvatarUrl: String?,
    calleeLocalAvatarPath: String?,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        CallUserAvatar(calleeName, calleeAvatarUrl, calleeLocalAvatarPath)
        Spacer(Modifier.height(24.dp))
        Text(calleeName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Calling...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        CallControlButton(
            icon = Icons.Default.CallEnd,
            contentDescription = "Cancel",
            onClick = onCancel,
            backgroundColor = Color(0xFFE53935),
            iconTint = Color.White
        )
    }
}

@Composable
private fun ConnectingContent(
    remoteName: String,
    remoteAvatarUrl: String?,
    remoteLocalAvatarPath: String?,
    onHangup: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        CallUserAvatar(remoteName, remoteAvatarUrl, remoteLocalAvatarPath)
        Spacer(Modifier.height(24.dp))
        Text(remoteName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text("Connecting...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(48.dp))
        CallControlButton(
            icon = Icons.Default.CallEnd,
            contentDescription = "Hang up",
            onClick = onHangup,
            backgroundColor = Color(0xFFE53935),
            iconTint = Color.White
        )
    }
}

@Composable
private fun ConnectedContent(
    remoteName: String,
    remoteAvatarUrl: String?,
    remoteLocalAvatarPath: String?,
    startTime: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startTime) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startTime) / 1000
            delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        CallUserAvatar(remoteName, remoteAvatarUrl, remoteLocalAvatarPath)
        Spacer(Modifier.height(24.dp))
        Text(remoteName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatElapsed(elapsed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CallControlButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMuted) "Unmute" else "Mute",
                onClick = onToggleMute,
                backgroundColor = if (isMuted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                iconTint = if (isMuted) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
            )
            CallControlButton(
                icon = Icons.Default.CallEnd,
                contentDescription = "Hang up",
                onClick = onHangup,
                backgroundColor = Color(0xFFE53935),
                iconTint = Color.White,
                size = 72.dp
            )
            CallControlButton(
                icon = Icons.Default.VolumeUp,
                contentDescription = if (isSpeakerOn) "Disable speaker" else "Enable speaker",
                onClick = onToggleSpeaker,
                backgroundColor = if (isSpeakerOn) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
                iconTint = if (isSpeakerOn) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun EndedContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Call Ended", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CallUserAvatar(name: String, avatarUrl: String?, localAvatarPath: String?) {
    val model = remember(localAvatarPath, avatarUrl) {
        resolveAvatarModel(localAvatarPath, avatarUrl)
    }
    Box(
        modifier = Modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = name,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
