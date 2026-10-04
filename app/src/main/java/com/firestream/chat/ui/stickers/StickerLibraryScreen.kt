package com.firestream.chat.ui.stickers

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.firestream.chat.ui.components.LibraryStickerImage
import com.firestream.chat.ui.components.StickerImage
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind

/**
 * Where the system folder picker opens for the WhatsApp route: the folder
 * WhatsApp keeps a file in for every sticker seen in a chat.
 */
private val WHATSAPP_STICKER_FOLDER: Uri = DocumentsContract.buildDocumentUri(
    "com.android.externalstorage.documents",
    "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
)

/** What the library's list and grid can ask for. One bundle keeps the composables under the parameter ceiling. */
@Immutable
internal data class StickerLibraryActions(
    val onBack: () -> Unit = {},
    val onImportFromWhatsApp: () -> Unit = {},
    val onImportFromFiles: () -> Unit = {},
    val onOpenPack: (String) -> Unit = {},
    val onClosePack: () -> Unit = {},
    val onRenamePack: (packId: String, name: String) -> Unit = { _, _ -> },
    val onDeletePack: (String) -> Unit = {},
    val onMovePack: (packId: String, up: Boolean) -> Unit = { _, _ -> },
    val onToggleSticker: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRemoveSelected: () -> Unit = {},
    val onMoveSelectedTo: (String) -> Unit = {},
)

@Composable
fun StickerLibraryScreen(
    onBackClick: () -> Unit,
    viewModel: StickerLibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keeps the folder readable if the process is restarted while the grid is open.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.openWhatsAppFolder(uri.toString())
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importFiles(uris.map { it.toString() })
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.notice) {
        uiState.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    val whatsApp = uiState.whatsApp
    BackHandler(enabled = whatsApp != null || uiState.openPackId != null) {
        when {
            whatsApp != null -> viewModel.closeWhatsApp()
            uiState.selectedStickerIds.isNotEmpty() -> viewModel.clearSelection()
            else -> viewModel.closePack()
        }
    }

    if (whatsApp != null) {
        WhatsAppImportScreen(
            state = whatsApp,
            isImporting = uiState.isImporting,
            snackbarHostState = snackbarHostState,
            onClose = viewModel::closeWhatsApp,
            onToggleFile = viewModel::toggleWhatsAppFile,
            onToggleSelectAll = viewModel::toggleSelectAllWhatsApp,
            onImport = viewModel::importSelectedWhatsApp,
        )
    } else {
        val actions = remember(viewModel, onBackClick) {
            StickerLibraryActions(
                onBack = onBackClick,
                onImportFromWhatsApp = { folderPicker.launch(WHATSAPP_STICKER_FOLDER) },
                // Every type: a `.wastickers` archive has no mime type of its own. The import checks the bytes.
                onImportFromFiles = { filePicker.launch(arrayOf("*/*")) },
                onOpenPack = viewModel::openPack,
                onClosePack = viewModel::closePack,
                onRenamePack = viewModel::renamePack,
                onDeletePack = viewModel::deletePack,
                onMovePack = viewModel::movePack,
                onToggleSticker = viewModel::toggleSticker,
                onClearSelection = viewModel::clearSelection,
                onRemoveSelected = viewModel::removeSelected,
                onMoveSelectedTo = viewModel::moveSelectedTo,
            )
        }
        StickerLibraryContent(uiState = uiState, actions = actions, snackbarHostState = snackbarHostState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerLibraryContent(
    uiState: StickerLibraryUiState,
    actions: StickerLibraryActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val openPack = uiState.openPack
    val selectedCount = uiState.selectedStickerIds.size
    var showMoveDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            StickerTopBar(
                title = when {
                    selectedCount > 0 -> "$selectedCount selected"
                    openPack != null -> openPack.label()
                    else -> "Stickers"
                },
                isImporting = uiState.isImporting,
                navigationIcon = {
                    if (selectedCount > 0) {
                        IconButton(onClick = actions.onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = if (openPack != null) actions.onClosePack else actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (selectedCount > 0) {
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to another pack")
                        }
                        IconButton(onClick = actions.onRemoveSelected) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove from pack")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (openPack != null) {
            PackGrid(
                pack = openPack,
                selected = uiState.selectedStickerIds,
                onToggleSticker = actions.onToggleSticker,
                contentPadding = padding,
            )
        } else {
            PackList(uiState = uiState, actions = actions, contentPadding = padding)
        }
    }

    if (showMoveDialog && openPack != null) {
        MoveToPackDialog(
            targets = uiState.packs.filter { it.id != openPack.id && it.kind != StickerPackKind.FAVOURITES },
            onPick = { packId ->
                showMoveDialog = false
                actions.onMoveSelectedTo(packId)
            },
            onDismiss = { showMoveDialog = false },
        )
    }
}

@Composable
private fun PackList(
    uiState: StickerLibraryUiState,
    actions: StickerLibraryActions,
    contentPadding: PaddingValues,
) {
    var renaming by remember { mutableStateOf<StickerPack?>(null) }
    var deleting by remember { mutableStateOf<StickerPack?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "import-whatsapp") {
            ListItem(
                headlineContent = { Text("From WhatsApp") },
                supportingContent = {
                    Text("Choose from the stickers you have sent or received there", style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !uiState.isImporting, onClick = actions.onImportFromWhatsApp),
            )
        }
        item(key = "import-files") {
            ListItem(
                headlineContent = { Text("From files") },
                supportingContent = {
                    Text("WebP stickers and .wastickers packs", style = MaterialTheme.typography.bodySmall)
                },
                leadingContent = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                modifier = Modifier.clickable(enabled = !uiState.isImporting, onClick = actions.onImportFromFiles),
            )
        }
        if (uiState.packs.isEmpty()) {
            if (!uiState.isLoading) {
                item(key = "empty") {
                    EmptyHint(
                        title = "No stickers yet",
                        body = "Import some from WhatsApp or from files to start your library",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    )
                }
            }
        } else {
            item(key = "packs-header") {
                Text(
                    text = "Packs",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            itemsIndexed(uiState.packs, key = { _, pack -> pack.id }) { index, pack ->
                PackRow(
                    pack = pack,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.packs.lastIndex,
                    actions = actions,
                    onRename = { renaming = pack },
                    onDelete = { deleting = pack },
                )
            }
        }
    }

    renaming?.let { pack ->
        RenamePackDialog(
            currentName = pack.name,
            onConfirm = { name ->
                renaming = null
                actions.onRenamePack(pack.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete pack") },
            text = { Text("Delete ${pack.label()} and take its ${pack.stickers.size} ${stickers(pack.stickers.size)} out of your library?") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    actions.onDeletePack(pack.id)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackRow(
    pack: StickerPack,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: StickerLibraryActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = "${pack.stickers.size} ${stickers(pack.stickers.size)}"
    ListItem(
        headlineContent = { Text(pack.label()) },
        supportingContent = {
            Text(
                text = pack.publisher?.let { "$count · $it" } ?: count,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = {
            val first = pack.stickers.firstOrNull()
            if (first != null) {
                LibraryStickerImage(sticker = first, modifier = Modifier.size(48.dp))
            } else {
                Icon(Icons.Default.EmojiEmotions, contentDescription = null, modifier = Modifier.size(48.dp).padding(8.dp))
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options for ${pack.label()}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (pack.canRename) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    }
                    if (canMoveUp) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            onClick = { menuOpen = false; actions.onMovePack(pack.id, true) },
                        )
                    }
                    if (canMoveDown) {
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            onClick = { menuOpen = false; actions.onMovePack(pack.id, false) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
        modifier = Modifier.clickable { actions.onOpenPack(pack.id) },
    )
}

@Composable
private fun PackGrid(
    pack: StickerPack,
    selected: Set<String>,
    onToggleSticker: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    if (pack.stickers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            Text("This pack is empty", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(STICKER_CELL),
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(pack.stickers, key = { it.id }) { sticker ->
            StickerCell(
                sticker = sticker,
                isSelected = sticker.id in selected,
                // A tap picks only once a long press has started a selection.
                onClick = if (selected.isNotEmpty()) onToggleSticker else NO_TAP,
                onLongClick = onToggleSticker,
            )
        }
    }
}

private val NO_TAP: (String) -> Unit = {}

@Composable
private fun RenamePackDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename pack") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveToPackDialog(targets: List<StickerPack>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to") },
        text = {
            if (targets.isEmpty()) {
                Text("There is no other pack to move these to.")
            } else {
                LazyColumn {
                    listItems(targets, key = { it.id }) { pack ->
                        TextButton(onClick = { onPick(pack.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(pack.label(), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal val STICKER_CELL = 88.dp

/** The test tag of one [StickerCell]. A sticker has no text to find it by. */
internal fun stickerCellTag(stickerId: String): String = "sticker:$stickerId"

/**
 * One sticker in a grid, with the mark of a selection drawn over it. The clicks
 * hand [id] back, so a grid passes the same two functions to every cell and a
 * toggle recomposes only the cell it changed.
 *
 * [model] is anything that is not a library sticker: a folder entry's uri.
 */
@Composable
internal fun StickerCell(
    id: String,
    model: String,
    isSelected: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: ((String) -> Unit)? = null,
) {
    StickerCellFrame(id, isSelected, onClick, modifier, enabled, onLongClick) {
        StickerThumbnail(model = model, modifier = Modifier.fillMaxSize().padding(6.dp))
    }
}

/** A library sticker in a grid. Its file is fetched when the cell is first shown without one. */
@Composable
internal fun StickerCell(
    sticker: Sticker,
    isSelected: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: ((String) -> Unit)? = null,
) {
    StickerCellFrame(sticker.id, isSelected, onClick, modifier, enabled, onLongClick) {
        LibraryStickerImage(sticker = sticker, modifier = Modifier.fillMaxSize().padding(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerCellFrame(
    id: String,
    isSelected: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    onLongClick: ((String) -> Unit)?,
    image: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .testTag(stickerCellTag(id))
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                enabled = enabled,
                onClick = { onClick(id) },
                onLongClick = onLongClick?.let { { it(id) } },
            ),
    ) {
        image()
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
            )
        }
    }
}

/** A sticker's first frame. [model] is a folder entry's uri. A library sticker is drawn by `LibraryStickerImage`. */
@Composable
internal fun StickerThumbnail(model: String, modifier: Modifier = Modifier) {
    StickerImage(model = model, modifier = modifier, animated = false)
}

/** The top bar of both sticker screens, with the import's progress line under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerTopBar(
    title: String,
    isImporting: Boolean,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        if (isImporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** A title and one line under it, for a list or a grid with nothing in it. */
@Composable
internal fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
