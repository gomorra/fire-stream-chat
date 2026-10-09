package com.firestream.chat.ui.calls

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.outlined.PhoneCallback
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.firestream.chat.ui.components.SkeletonCallItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.domain.model.CallLogEntry
import com.firestream.chat.domain.model.CallLogType
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.ui.call.CallActivity
import com.firestream.chat.ui.components.UserAvatar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    onMessageClick: (chatId: String, recipientId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CallsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedEntry by remember { mutableStateOf<CallLogEntry?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Calls",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Column { repeat(8) { SkeletonCallItem() } }
                }
                uiState.entries.isEmpty() -> {
                    EmptyCallsState(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.messageId }) { entry ->
                            CallLogRow(
                                entry = entry,
                                contact = uiState.contacts[entry.otherPartyId],
                                onCallClick = { startOutgoingCall(context, entry) },
                                onLongClick = { selectedEntry = entry }
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 80.dp))
                        }
                    }
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        CallDetailSheet(
            entry = entry,
            contact = uiState.contacts[entry.otherPartyId],
            onDismiss = { selectedEntry = null },
            onCallBack = {
                selectedEntry = null
                startOutgoingCall(context, entry)
            },
            onMessage = {
                selectedEntry = null
                onMessageClick(entry.chatId, entry.otherPartyId)
            }
        )
    }
}

@Composable
private fun EmptyCallsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.PhoneCallback,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No recent calls",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your call history will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogEntry,
    contact: Contact?,
    onCallClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val missedColor = MaterialTheme.colorScheme.error
    val isMissedOrDeclined = entry.type.isMissedOrDeclined

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(
            avatarUrl = contact?.avatarUrl ?: entry.avatarUrl,
            contentDescription = entry.displayName,
            icon = Icons.Default.Person,
            size = 52.dp,
            modifier = Modifier.size(52.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp),
                color = if (isMissedOrDeclined) missedColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = typeIcon(entry.type),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isMissedOrDeclined) missedColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = buildCallLabel(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMissedOrDeclined) missedColor else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatRelativeTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        IconButton(onClick = onCallClick) {
            Icon(
                imageVector = callBackIcon(entry),
                contentDescription = if (entry.video) "Call back with video" else "Call back",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatCallDuration(durationSeconds: Int): String {
    val m = durationSeconds / 60
    val s = durationSeconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun typeIcon(type: CallLogType): ImageVector = when (type) {
    CallLogType.OUTGOING, CallLogType.NO_ANSWER, CallLogType.OUTGOING_DECLINED ->
        Icons.AutoMirrored.Filled.CallMade
    CallLogType.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    CallLogType.MISSED, CallLogType.DECLINED -> Icons.AutoMirrored.Filled.CallMissed
}

/** A row calls back with the kind the call was, and its icon says which. */
private fun callBackIcon(entry: CallLogEntry): ImageVector =
    if (entry.video) Icons.Default.Videocam else Icons.Default.Call

internal fun typeLabel(type: CallLogType, video: Boolean): String {
    val call = if (video) "video call" else "call"
    return when (type) {
        CallLogType.OUTGOING, CallLogType.NO_ANSWER, CallLogType.OUTGOING_DECLINED -> "Outgoing $call"
        CallLogType.INCOMING -> "Incoming $call"
        CallLogType.MISSED -> "Missed $call"
        CallLogType.DECLINED -> "Declined $call"
    }
}

/** How a call that never connected ended. An OUTGOING or INCOMING call always connected. */
private fun unconnectedLabel(type: CallLogType): String = when (type) {
    CallLogType.OUTGOING, CallLogType.NO_ANSWER -> "No answer"
    CallLogType.OUTGOING_DECLINED, CallLogType.DECLINED -> "Declined"
    CallLogType.INCOMING, CallLogType.MISSED -> "Missed"
}

internal fun buildCallLabel(entry: CallLogEntry): String {
    val durationSeconds = entry.durationSeconds ?: 0
    val outcome = if (durationSeconds > 0) formatCallDuration(durationSeconds) else unconnectedLabel(entry.type)
    return if (entry.video) "Video call · $outcome" else outcome
}

private fun formatRelativeTimestamp(timestamp: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        sameYear && dayDiff == 1 -> "Yesterday"
        sameYear -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("d MMM yy", Locale.getDefault()).format(Date(timestamp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallDetailSheet(
    entry: CallLogEntry,
    contact: Contact?,
    onDismiss: () -> Unit,
    onCallBack: () -> Unit,
    onMessage: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar + name
            UserAvatar(
                avatarUrl = contact?.avatarUrl ?: entry.avatarUrl,
                contentDescription = entry.displayName,
                icon = Icons.Default.Person,
                size = 80.dp,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.titleLarge
            )
            contact?.phoneNumber?.takeIf { it.isNotBlank() }?.let { phone ->
                Text(
                    text = phone,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Info rows
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Type
                InfoRow(
                    icon = typeIcon(entry.type),
                    iconTint = if (entry.type.isMissedOrDeclined)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    text = typeLabel(entry.type, entry.video)
                )

                // Date/time
                InfoRow(text = formatFullDateTime(entry.timestamp))

                // Duration
                val durationSeconds = entry.durationSeconds ?: 0
                if (durationSeconds > 0) {
                    InfoRow(text = "Duration: ${formatCallDuration(durationSeconds)}")
                }

                // End reason
                InfoRow(text = if (durationSeconds > 0) "Ended" else unconnectedLabel(entry.type))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onMessage,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubble,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Message")
                }
                Button(
                    onClick = onCallBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = callBackIcon(entry),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Call back")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    text: String,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val fullDateTimeFormat by lazy {
    SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.getDefault())
}

private fun formatFullDateTime(timestamp: Long): String =
    fullDateTimeFormat.format(Date(timestamp))

private fun startOutgoingCall(context: Context, entry: CallLogEntry) {
    // A row calls back with the kind the call was.
    context.startActivity(
        CallActivity.outgoingIntent(
            context = context,
            calleeId = entry.otherPartyId,
            calleeName = entry.displayName,
            calleeAvatarUrl = entry.avatarUrl,
            chatId = entry.chatId,
            video = entry.video
        )
    )
}
