package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.firestream.chat.domain.model.StickerAutoNaming
import com.firestream.chat.ui.components.ConsentNotice

internal const val AUTO_NAMING_TITLE = "Name my stickers automatically"
internal const val AUTO_NAMING_SWITCH_TAG = "auto-naming-switch"
internal const val AUTO_NAMING_ACCEPT_LABEL = "Switch on"
internal const val AUTO_NAMING_REFUSED = "Naming is not available on this server right now."

/**
 * The switch of automatic sticker names, at the top of the pack list.
 *
 * Switching it on is consent, so it never goes on by the tap alone: the tap
 * opens [AutoNamingNotice], and only its button switches naming on. Switching
 * it off needs no question.
 *
 * A backend that refuses is said here and switches nothing off.
 */
@Composable
internal fun AutoNamingRow(naming: StickerAutoNaming, onSet: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(AUTO_NAMING_TITLE) },
            supportingContent = {
                when {
                    !naming.isEnabled -> Text("Off", style = MaterialTheme.typography.bodySmall)
                    naming.isRefused -> Text(
                        AUTO_NAMING_REFUSED,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text("On. New stickers are named in the background.", style = MaterialTheme.typography.bodySmall)
                }
            },
            trailingContent = {
                Switch(
                    checked = naming.isEnabled,
                    onCheckedChange = { on ->
                        asking = on
                        if (!on) onSet(false)
                    },
                    modifier = Modifier.testTag(AUTO_NAMING_SWITCH_TAG),
                )
            },
        )
        if (asking && !naming.isEnabled) {
            AutoNamingNotice(
                onAccept = {
                    asking = false
                    onSet(true)
                },
                onCancel = { asking = false },
            )
        }
    }
}

/**
 * Says what switching automatic names on does, before it is switched on. It
 * is drawn like `OnlineMediaNotice`, by the same [ConsentNotice].
 */
@Composable
internal fun AutoNamingNotice(onAccept: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ConsentNotice(
        title = "A computer reads the pictures of your stickers",
        body = "The pictures of your stickers are read by a computer that the person who runs this server keeps. " +
            "A model there describes each picture, so you can find the sticker by a name. Nothing else is sent. " +
            "Stickers you made in the app are left out, and you can leave out a pack or a single sticker.",
        modifier = modifier,
    ) {
        TextButton(onClick = onCancel) { Text("Not now") }
        Button(onClick = onAccept) { Text(AUTO_NAMING_ACCEPT_LABEL) }
    }
}
