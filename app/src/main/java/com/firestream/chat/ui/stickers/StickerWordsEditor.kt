package com.firestream.chat.ui.stickers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.util.StickerWords
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.components.LibraryStickerImage

/** What the chips of a sticker's emojis and names can ask for. */
@Immutable
internal data class StickerWordCallbacks(
    val onAddEmoji: (String) -> Unit = {},
    val onRemoveEmoji: (String) -> Unit = {},
    val onAddName: (String) -> Unit = {},
    val onRemoveName: (String) -> Unit = {},
    /** What is typed in the name field and not yet added. The caller adds it with a save. */
    val onNameDraft: (String) -> Unit = {},
)

internal const val STICKER_NAME_FIELD_TAG = "sticker-name-field"
internal const val ADD_EMOJI_LABEL = "Add emoji"
internal const val ADD_NAMES_LABEL = "Add names"

/**
 * A sticker's emojis and names as chips, each with a remove, and the two chips
 * that add one. *Add emoji* opens the emoji picker. *Add names* opens a field
 * that takes one name after the other.
 *
 * The lists are the caller's, and so are the limits it applies to an add. The
 * add chips step aside at [maxEmojis] emojis and at the limit of names.
 *
 * [nameDraft] is the caller's too, so a save can take a name that is still in
 * the field. Nobody should have to press the field's check button first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StickerWordChips(
    emojis: List<String>,
    names: List<String>,
    nameDraft: String,
    callbacks: StickerWordCallbacks,
    modifier: Modifier = Modifier,
    maxEmojis: Int = StickerWords.MAX_EMOJIS,
) {
    var pickingEmoji by remember { mutableStateOf(false) }
    // Open from the start when a name is still typed, so a save never takes a name nobody can see.
    var addingNames by remember { mutableStateOf(nameDraft.isNotEmpty()) }
    val canAddName = names.size < StickerWords.MAX_NAMES
    val submitName = {
        if (nameDraft.isNotBlank()) {
            callbacks.onAddName(nameDraft)
            callbacks.onNameDraft("")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            emojis.forEach { emoji ->
                WordChip(text = emoji, onRemove = { callbacks.onRemoveEmoji(emoji) })
            }
            names.forEach { name ->
                WordChip(text = name, onRemove = { callbacks.onRemoveName(name) })
            }
            if (emojis.size < maxEmojis) {
                AddChip(label = ADD_EMOJI_LABEL, onClick = { pickingEmoji = true })
            }
            if (canAddName && !addingNames) {
                AddChip(label = ADD_NAMES_LABEL, onClick = { addingNames = true })
            }
        }
        if (addingNames && canAddName) {
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { callbacks.onNameDraft(it.take(StickerWords.MAX_NAME_LENGTH)) },
                singleLine = true,
                label = { Text("Name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitName() }),
                trailingIcon = {
                    IconButton(onClick = submitName, enabled = nameDraft.isNotBlank()) {
                        Icon(Icons.Default.Check, contentDescription = "Add this name")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(STICKER_NAME_FIELD_TAG),
            )
        }
    }

    if (pickingEmoji) {
        EmojiPickSheet(
            onPick = { emoji ->
                pickingEmoji = false
                callbacks.onAddEmoji(emoji)
            },
            onDismiss = { pickingEmoji = false },
        )
    }
}

@Composable
private fun WordChip(text: String, onRemove: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(text) },
        trailingIcon = {
            Icon(Icons.Default.Close, contentDescription = "Remove $text", modifier = Modifier.size(16.dp))
        },
    )
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

/** The emoji picker in a sheet of its own, for one pick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EmojiTab(
            query = "",
            recentEmojis = emptyList(),
            onSelection = { onPick(it.emoji) },
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
    }
}

/** Saves a draft's list as a list the saved state can hold, whatever kind of list it is. */
private val WordsSaver = listSaver<List<String>, String>(save = { it.toList() }, restore = { it })

/** The manager's edit of one sticker's emojis and names, in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerWordsSheet(
    sticker: Sticker,
    onSave: (emojis: List<String>, names: List<String>, stopAutoNaming: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        StickerWordsEditor(sticker = sticker, onSave = onSave, onCancel = onDismiss)
    }
}

/**
 * The content of [StickerWordsSheet]: the sticker, its emojis and names as
 * chips, and under them what was found automatically, which cannot be changed
 * here. Nothing is written before *Save*.
 *
 * *Use as mine* puts the automatic names and search words among the names,
 * where they can be edited like typed ones. A save after it also tells
 * [onSave] to leave the sticker out of automatic naming from now on.
 */
@Composable
internal fun StickerWordsEditor(
    sticker: Sticker,
    onSave: (emojis: List<String>, names: List<String>, stopAutoNaming: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var usedAsMine by rememberSaveable(sticker.id) { mutableStateOf(false) }
    // Saved across a rotation: the draft is what the person typed, and nothing else holds it.
    var emojis by rememberSaveable(sticker.id, stateSaver = WordsSaver) { mutableStateOf(sticker.emojis) }
    var names by rememberSaveable(sticker.id, stateSaver = WordsSaver) { mutableStateOf(sticker.names) }
    var nameDraft by rememberSaveable(sticker.id) { mutableStateOf("") }
    val callbacks = remember(sticker.id) {
        StickerWordCallbacks(
            onAddEmoji = { emojis = StickerWords.withEmoji(emojis, it) },
            onRemoveEmoji = { emojis = emojis - it },
            onAddName = { names = StickerWords.withName(names, it) },
            onRemoveName = { names = names - it },
            onNameDraft = { nameDraft = it },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LibraryStickerImage(sticker = sticker, modifier = Modifier.size(96.dp))
        WordsTitle("Emojis and names")
        StickerWordChips(emojis = emojis, names = names, nameDraft = nameDraft, callbacks = callbacks)
        Text(
            text = "The sticker is found by these in a search.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        sticker.autoName?.let { found ->
            AutomaticWords(
                autoName = found,
                usedAsMine = usedAsMine,
                onUseAsMine = {
                    names = StickerWords.withAutoName(names, found)
                    usedAsMine = true
                },
            )
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            // A name still in the field is saved too.
            TextButton(onClick = { onSave(emojis, StickerWords.withName(names, nameDraft), usedAsMine) }) { Text("Save") }
        }
    }
}

internal const val USE_AS_MINE_LABEL = "Use as mine"

/** What the naming service found, read-only, with the button that takes it over. */
@Composable
private fun AutomaticWords(autoName: StickerAutoName, usedAsMine: Boolean, onUseAsMine: () -> Unit) {
    AutomaticWordLines(autoName)
    if (usedAsMine) {
        Text(
            text = "Added to your names. Saving also stops automatic naming for this sticker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    } else {
        TextButton(onClick = onUseAsMine, modifier = Modifier.fillMaxWidth()) { Text(USE_AS_MINE_LABEL) }
    }
}

internal const val AUTOMATIC_WORDS_TITLE = "Found automatically"

/**
 * What the naming service found, read-only, under its title: the German and the
 * English name, the search words, the text on the sticker and the emojis. The
 * edit sheet and the sticker sheet both draw the group with this.
 */
@Composable
internal fun AutomaticWordLines(autoName: StickerAutoName) {
    WordsTitle(AUTOMATIC_WORDS_TITLE)
    WordLines(
        listOfNotNull(
            listOf(autoName.nameDe, autoName.nameEn).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null },
            autoName.words.joinToString(", ").ifEmpty { null },
            autoName.text.ifEmpty { null }?.let { "Text: $it" },
            autoName.emojis.joinToString(" ").ifEmpty { null },
        )
    )
}

/** The lines of one group of a sticker's words, read-only. */
@Composable
internal fun WordLines(lines: List<String>) {
    lines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun WordsTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
    )
}
