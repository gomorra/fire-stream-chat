package com.firestream.chat.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.firestream.chat.R
import com.firestream.chat.domain.model.CallAudioRoute

internal const val CALL_ROUTE_ROW_TAG = "call_route_row"

/**
 * The third in-call control. With at most two routes it is the old speaker toggle; once a headset
 * adds a third it opens a sheet instead. [availableRoutes] can be empty for a moment after the
 * call connects, so the toggle branch never reads the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallAudioRouteButton(
    audioRoute: CallAudioRoute,
    availableRoutes: List<CallAudioRoute>,
    onSelectRoute: (CallAudioRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val highlighted = audioRoute != CallAudioRoute.EARPIECE

    CallControlButton(
        icon = buttonIcon(audioRoute),
        contentDescription = stringResource(R.string.call_route_button, stringResource(labelRes(audioRoute))),
        onClick = {
            if (availableRoutes.size <= 2) {
                onSelectRoute(
                    if (audioRoute == CallAudioRoute.SPEAKER) CallAudioRoute.EARPIECE else CallAudioRoute.SPEAKER
                )
            } else {
                showSheet = true
            }
        },
        backgroundColor = if (highlighted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant,
        iconTint = if (highlighted) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            CallAudioRouteList(
                audioRoute = audioRoute,
                availableRoutes = availableRoutes,
                onSelectRoute = { route ->
                    showSheet = false
                    onSelectRoute(route)
                }
            )
        }
    }
}

@Composable
internal fun CallAudioRouteList(
    audioRoute: CallAudioRoute,
    availableRoutes: List<CallAudioRoute>,
    onSelectRoute: (CallAudioRoute) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            text = stringResource(R.string.call_route_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
        availableRoutes.forEach { route ->
            val selected = route == audioRoute
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.RadioButton) { onSelectRoute(route) }
                    .testTag(CALL_ROUTE_ROW_TAG)
                    .padding(horizontal = 24.dp)
            ) {
                Icon(
                    imageVector = rowIcon(route),
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    text = stringResource(labelRes(route)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun labelRes(route: CallAudioRoute): Int = when (route) {
    CallAudioRoute.EARPIECE -> R.string.call_route_earpiece
    CallAudioRoute.SPEAKER -> R.string.call_route_speaker
    CallAudioRoute.BLUETOOTH -> R.string.call_route_bluetooth
    CallAudioRoute.WIRED_HEADSET -> R.string.call_route_wired
}

// The button keeps the speaker glyph for the earpiece (unhighlighted = speaker off, as before).
private fun buttonIcon(route: CallAudioRoute): ImageVector = when (route) {
    CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER -> Icons.Default.VolumeUp
    CallAudioRoute.BLUETOOTH -> Icons.Default.Bluetooth
    CallAudioRoute.WIRED_HEADSET -> Icons.Default.Headset
}

// In the sheet earpiece and speaker sit side by side, so they need different glyphs.
private fun rowIcon(route: CallAudioRoute): ImageVector =
    if (route == CallAudioRoute.EARPIECE) Icons.Default.PhoneInTalk else buttonIcon(route)
