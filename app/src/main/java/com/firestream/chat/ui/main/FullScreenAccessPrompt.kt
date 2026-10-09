package com.firestream.chat.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val FULL_SCREEN_ACCESS_PROMPT_TAG = "fullScreenAccessPrompt"

/**
 * Asks for the access *Full screen notifications*. It is a card above the bottom bar, not a
 * dialog: the app stays usable under it, and only *Not now* sends it away for good.
 */
@Composable
internal fun FullScreenAccessPrompt(
    visible: Boolean,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        // The card gives its height back as it goes, so the tab above grows with it and does
        // not jump when the animation ends.
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            // The colours of ExactAlarmBanner, the chat's prompt of the same kind.
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(FULL_SCREEN_ACCESS_PROMPT_TAG),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 12.dp)) {
                Row {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = "Show calls on the lock screen",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Allow full-screen notifications so incoming calls and timer alarms " +
                                "can wake the display and show over the lock screen.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    TextButton(onClick = onNotNow) { Text("Not now") }
                    Spacer(Modifier.width(4.dp))
                    Button(onClick = onOpenSettings) { Text("Open settings") }
                }
            }
        }
    }
}
