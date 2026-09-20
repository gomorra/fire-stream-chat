package com.firestream.chat.ui.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.R
import com.firestream.chat.domain.model.CallAudioRoute

/**
 * The in-call audio-route control: one [CallControlButton] that either toggles
 * earpiece⇄speaker or opens [CallAudioRouteSheet], depending on how many routes the OS
 * offers.
 *
 * Two properties this has to keep, both from the router's shape (see the plan's §2.3):
 *  - [availableRoutes] is whatever the OS reports and **may be empty**. Anything with two
 *    routes or fewer is the plain two-route phone and toggles directly; the list is never
 *    indexed.
 *  - [audioRoute] is the route the OS says is *playing*, which lags a Bluetooth tap by up
 *    to a second and can briefly sit outside [availableRoutes]. The button renders it as
 *    given and never latches the tapped route optimistically, so the check mark moves when
 *    the audio does.
 */
@Composable
internal fun CallAudioRouteButton(
    audioRoute: CallAudioRoute,
    availableRoutes: List<CallAudioRoute>,
    onSelectRoute: (CallAudioRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val highlighted = audioRoute != CallAudioRoute.EARPIECE

    // A headset unplugged while the sheet is open drops the call back to the two-route
    // phone; close rather than leave a two-row sheet sitting over the call.
    LaunchedEffect(availableRoutes.size) {
        if (availableRoutes.size <= 2) sheetOpen = false
    }

    CallControlButton(
        icon = routeIcon(audioRoute),
        contentDescription = stringResource(R.string.call_route_button, routeLabel(audioRoute)),
        onClick = {
            if (availableRoutes.size <= 2) {
                onSelectRoute(
                    if (audioRoute == CallAudioRoute.SPEAKER) CallAudioRoute.EARPIECE
                    else CallAudioRoute.SPEAKER
                )
            } else {
                sheetOpen = true
            }
        },
        backgroundColor = if (highlighted) {
            MaterialTheme.colorScheme.secondary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        iconTint = if (highlighted) {
            MaterialTheme.colorScheme.onSecondary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier,
    )

    if (sheetOpen) {
        CallAudioRouteSheet(
            current = audioRoute,
            available = availableRoutes,
            onSelect = {
                sheetOpen = false
                onSelectRoute(it)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Route picker shown when the call has more than the earpiece and the speaker to choose from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallAudioRouteSheet(
    current: CallAudioRoute,
    available: List<CallAudioRoute>,
    onSelect: (CallAudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.call_route_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            CallAudioRouteList(current = current, available = available, onSelect = onSelect)
        }
    }
}

/**
 * The sheet's rows, hoisted out of the [ModalBottomSheet] so they can be rendered — and
 * tested — without a dialog window.
 */
@Composable
internal fun CallAudioRouteList(
    current: CallAudioRoute,
    available: List<CallAudioRoute>,
    onSelect: (CallAudioRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        available.forEach { route ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = route == current, onClick = { onSelect(route) })
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = routeIcon(route),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = routeLabel(route),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (route == current) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Icon for a route. Earpiece and speaker deliberately share one icon (§2.5): on the button
 * the highlight tells them apart, in the sheet the label and the check do.
 */
internal fun routeIcon(route: CallAudioRoute): ImageVector = when (route) {
    CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER -> Icons.Default.VolumeUp
    CallAudioRoute.BLUETOOTH -> Icons.Default.Bluetooth
    CallAudioRoute.WIRED_HEADSET -> Icons.Default.Headset
}

@Composable
internal fun routeLabel(route: CallAudioRoute): String = stringResource(
    when (route) {
        CallAudioRoute.EARPIECE -> R.string.call_route_earpiece
        CallAudioRoute.SPEAKER -> R.string.call_route_speaker
        CallAudioRoute.BLUETOOTH -> R.string.call_route_bluetooth
        CallAudioRoute.WIRED_HEADSET -> R.string.call_route_wired
    }
)
