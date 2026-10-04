package com.firestream.chat.ui.stickers.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.firestream.chat.domain.model.StickerCrop
import com.firestream.chat.domain.model.StickerDraftImage
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.stickers.EmptyHint
import com.firestream.chat.ui.stickers.StickerTopBar
import java.io.File

/** What the maker's controls can ask for. One bundle keeps the composables under the parameter ceiling. */
@Immutable
internal data class StickerCreateActions(
    val onBack: () -> Unit = {},
    val onPickPhoto: () -> Unit = {},
    val onUseCutout: (Boolean) -> Unit = {},
    val onOutline: (Boolean) -> Unit = {},
    val onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit = { _, _, _ -> },
    val onAddEmoji: (String) -> Unit = {},
    val onRemoveEmoji: (String) -> Unit = {},
    val onChoosePack: (String?) -> Unit = {},
    val onNewPackName: (String) -> Unit = {},
    val onSave: () -> Unit = {},
)

internal const val STICKER_PREVIEW_TAG = "sticker-preview"

/**
 * The sticker maker: pick a photo, keep its subject or the whole photo, crop
 * it with a pinch, and save it into a pack with its emojis.
 */
@Composable
fun StickerCreateScreen(
    onBackClick: () -> Unit,
    viewModel: StickerCreateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri.toString())
    }
    val pickPhoto = remember(photoPicker) {
        { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    // The screen is nothing without a photo, so the picker opens with it. Once: a
    // rotation or a return from the picker must not open it again.
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!pickerOpened) {
            pickerOpened = true
            pickPhoto()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) onBackClick()
    }

    val actions = remember(viewModel, onBackClick, pickPhoto) {
        StickerCreateActions(
            onBack = onBackClick,
            onPickPhoto = pickPhoto,
            onUseCutout = viewModel::setUseCutout,
            onOutline = viewModel::setOutline,
            onTransform = viewModel::transform,
            onAddEmoji = viewModel::addEmoji,
            onRemoveEmoji = viewModel::removeEmoji,
            onChoosePack = viewModel::choosePack,
            onNewPackName = viewModel::setNewPackName,
            onSave = viewModel::save,
        )
    }
    StickerCreateContent(uiState = uiState, actions = actions, snackbarHostState = snackbarHostState)
}

@Composable
internal fun StickerCreateContent(
    uiState: StickerCreateUiState,
    actions: StickerCreateActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            StickerTopBar(
                title = "New sticker",
                isImporting = uiState.isSaving,
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = actions.onSave, enabled = uiState.canSave) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val image = uiState.image
        when {
            uiState.isPreparing -> CenteredColumn(Modifier.padding(padding)) {
                CircularProgressIndicator()
                Text(
                    text = "Looking for the subject…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            image == null -> CenteredColumn(Modifier.padding(padding)) {
                EmptyHint(title = "Make a sticker", body = "Choose a photo. Its subject is cut out for you.")
                Button(onClick = actions.onPickPhoto, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Choose a photo")
                }
            }

            else -> StickerEditor(
                uiState = uiState,
                image = image,
                actions = actions,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerEditor(
    uiState: StickerCreateUiState,
    image: StickerDraftImage,
    actions: StickerCreateActions,
    modifier: Modifier = Modifier,
) {
    var pickingEmoji by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StickerPreview(image = image, crop = uiState.crop, onTransform = actions.onTransform)
        Text(
            text = if (uiState.hasCutout) {
                "Pinch and drag to crop"
            } else {
                "No subject was cut out, so the whole photo is used. Pinch and drag to crop."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            if (uiState.hasCutout) {
                FilterChip(
                    selected = uiState.useCutout,
                    onClick = { actions.onUseCutout(true) },
                    label = { Text("Cutout") },
                )
                FilterChip(
                    selected = !uiState.useCutout,
                    onClick = { actions.onUseCutout(false) },
                    label = { Text("Original") },
                )
                if (uiState.useCutout) {
                    FilterChip(
                        selected = uiState.outline,
                        onClick = { actions.onOutline(!uiState.outline) },
                        label = { Text("Outline") },
                    )
                }
            }
            AssistChip(onClick = actions.onPickPhoto, label = { Text("Another photo") })
        }

        SectionTitle("Emojis")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            uiState.emojis.forEach { emoji ->
                InputChip(
                    selected = false,
                    onClick = { actions.onRemoveEmoji(emoji) },
                    label = { Text(emoji) },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove $emoji", modifier = Modifier.size(16.dp))
                    },
                )
            }
            if (uiState.canAddEmoji) {
                AssistChip(
                    onClick = { pickingEmoji = true },
                    label = { Text("Add emoji") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        Text(
            text = "The sticker is found by these in a search, and the first one stands for it in a preview.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        SectionTitle("Pack")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            uiState.packs.forEach { pack ->
                FilterChip(
                    selected = uiState.packId == pack.id,
                    onClick = { actions.onChoosePack(pack.id) },
                    label = { Text(pack.name) },
                )
            }
            FilterChip(
                selected = uiState.packId == null,
                onClick = { actions.onChoosePack(null) },
                label = { Text("New pack") },
            )
        }
        if (uiState.packId == null) {
            OutlinedTextField(
                value = uiState.newPackName,
                onValueChange = actions.onNewPackName,
                singleLine = true,
                label = { Text("Pack name") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }

    if (pickingEmoji) {
        EmojiSheet(
            onPick = { emoji ->
                pickingEmoji = false
                actions.onAddEmoji(emoji)
            },
            onDismiss = { pickingEmoji = false },
        )
    }
}

/**
 * The sticker as it will be saved: a square, with the picture where
 * `StickerGeometry.placement` puts it. `ContentScale.Fit` is the placement at
 * scale 1, and the layer applies the rest, so no second arithmetic lives here.
 */
@Composable
private fun StickerPreview(
    image: StickerDraftImage,
    crop: StickerCrop,
    onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp)
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag(STICKER_PREVIEW_TAG)
            // The gesture's own steps go up, and the ViewModel applies them to the
            // crop it holds, so no step is worked out from a stale crop here.
            .pointerInput(onTransform) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan.x / size.width, pan.y / size.height)
                }
            },
    ) {
        AsyncImage(
            model = remember(image.path) { File(image.path) },
            contentDescription = "Sticker preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = crop.scale
                scaleY = crop.scale
                translationX = crop.offsetX * size.width
                translationY = crop.offsetY * size.height
            },
        )
    }
}

/** The two states that have no editor yet: a message, and what goes with it, in the middle of the screen. */
@Composable
private fun CenteredColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        EmojiTab(
            query = "",
            recentEmojis = emptyList(),
            onSelection = { onPick(it.emoji) },
            modifier = Modifier.fillMaxWidth().height(360.dp),
        )
    }
}
