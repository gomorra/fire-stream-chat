package com.firestream.chat.ui.stickers

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

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
    val onCreate: () -> Unit = {},
    val onImportFromWhatsApp: () -> Unit = {},
    val onImportFromFiles: () -> Unit = {},
    val onOpenPack: (String) -> Unit = {},
    val onClosePack: () -> Unit = {},
    val onRenamePack: (packId: String, name: String) -> Unit = { _, _ -> },
    val onDeletePack: (String) -> Unit = {},
    val onSelectTab: (StickerManagerTab) -> Unit = {},
    val onSetShownInRow: (packId: String, shown: Boolean) -> Unit = { _, _ -> },
    /** Every pack id, in the order a drag left the list in. */
    val onReorderPacks: (List<String>) -> Unit = {},
    val onTogglePack: (String) -> Unit = {},
    val onClearPackSelection: () -> Unit = {},
    val onMergeSelected: (name: String) -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onToggleSticker: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRemoveSelected: () -> Unit = {},
    val onMoveSelectedTo: (String) -> Unit = {},
)

@Composable
fun StickerLibraryScreen(
    onBackClick: () -> Unit,
    // Opens the sticker maker.
    onCreateClick: () -> Unit = {},
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
    BackHandler(enabled = whatsApp != null || uiState.openPackId != null || uiState.selectedPackIds.isNotEmpty()) {
        when {
            whatsApp != null -> viewModel.closeWhatsApp()
            uiState.selectedStickerIds.isNotEmpty() -> viewModel.clearSelection()
            uiState.selectedPackIds.isNotEmpty() -> viewModel.clearPackSelection()
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
        val actions = remember(viewModel, onBackClick, onCreateClick) {
            StickerLibraryActions(
                onBack = onBackClick,
                onCreate = onCreateClick,
                onImportFromWhatsApp = { folderPicker.launch(WHATSAPP_STICKER_FOLDER) },
                // Every type: a `.wastickers` archive has no mime type of its own. The import checks the bytes.
                onImportFromFiles = { filePicker.launch(arrayOf("*/*")) },
                onOpenPack = viewModel::openPack,
                onClosePack = viewModel::closePack,
                onRenamePack = viewModel::renamePack,
                onDeletePack = viewModel::deletePack,
                onSelectTab = viewModel::selectTab,
                onSetShownInRow = viewModel::setPackShownInRow,
                onReorderPacks = viewModel::reorderPacks,
                onTogglePack = viewModel::togglePack,
                onClearPackSelection = viewModel::clearPackSelection,
                onMergeSelected = viewModel::mergeSelected,
                onDeleteSelected = viewModel::deleteSelected,
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
    val selectedPacks = uiState.selectedPacks
    var showMoveDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            StickerTopBar(
                title = when {
                    selectedCount > 0 -> "$selectedCount selected"
                    selectedPacks.isNotEmpty() -> "${selectedPacks.size} selected"
                    openPack != null -> openPack.label()
                    else -> "Stickers"
                },
                isImporting = uiState.isImporting,
                navigationIcon = {
                    if (selectedCount > 0 || selectedPacks.isNotEmpty()) {
                        IconButton(onClick = if (selectedCount > 0) actions.onClearSelection else actions.onClearPackSelection) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    } else {
                        IconButton(onClick = if (openPack != null) actions.onClosePack else actions.onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    when {
                        selectedCount > 0 -> {
                            IconButton(onClick = { showMoveDialog = true }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to another pack")
                            }
                            IconButton(onClick = actions.onRemoveSelected) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove from pack")
                            }
                        }
                        selectedPacks.isNotEmpty() -> {
                            IconButton(onClick = { showMergeDialog = true }, enabled = uiState.canMerge) {
                                Icon(Icons.AutoMirrored.Filled.CallMerge, contentDescription = "Merge")
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                        openPack == null -> AddStickersMenu(isImporting = uiState.isImporting, actions = actions)
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
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(
                    selectedTabIndex = uiState.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    StickerManagerTab.entries.forEach { tab ->
                        Tab(
                            selected = uiState.tab == tab,
                            onClick = { actions.onSelectTab(tab) },
                            text = { Text(tab.title) },
                        )
                    }
                }
                when (uiState.tab) {
                    StickerManagerTab.PACKS -> PackList(uiState = uiState, actions = actions)
                    // The grid of every sticker is not built yet.
                    StickerManagerTab.ALL_STICKERS -> Box(modifier = Modifier.fillMaxSize().testTag(ALL_STICKERS_TAB_TAG))
                }
            }
        }
    }

    if (showMergeDialog && uiState.canMerge) {
        PackNameDialog(
            title = "Merge ${selectedPacks.size} packs",
            confirmLabel = "Merge",
            currentName = selectedPacks.first().name,
            onConfirm = { name ->
                showMergeDialog = false
                actions.onMergeSelected(name)
            },
            onDismiss = { showMergeDialog = false },
        )
    }
    if (showDeleteDialog && selectedPacks.isNotEmpty()) {
        DeletePacksDialog(
            packs = selectedPacks,
            onConfirm = {
                showDeleteDialog = false
                actions.onDeleteSelected()
            },
            onDismiss = { showDeleteDialog = false },
        )
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

/** The **+** of the top bar: the sticker maker and the two import routes. */
@Composable
private fun AddStickersMenu(isImporting: Boolean, actions: StickerLibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, contentDescription = "Add stickers")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Create") },
                leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, contentDescription = null) },
                onClick = { open = false; actions.onCreate() },
            )
            DropdownMenuItem(
                text = { Text("From WhatsApp") },
                leadingIcon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                enabled = !isImporting,
                onClick = { open = false; actions.onImportFromWhatsApp() },
            )
            DropdownMenuItem(
                text = { Text("From files") },
                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                enabled = !isImporting,
                onClick = { open = false; actions.onImportFromFiles() },
            )
        }
    }
}

internal const val ROW_GROUP_TITLE = "In the picker row"
internal const val GROUPED_GROUP_TITLE = "Behind the WhatsApp thumbnail"
internal const val ALL_STICKERS_TAB_TAG = "all-stickers-tab"

/** The test tag of a pack's *Own thumbnail* switch. */
internal fun ownThumbnailSwitchTag(packId: String): String = "own-thumbnail:$packId"

/**
 * The list with the pack [fromKey] moved to the place of the pack [toKey].
 * `null` when a key is no pack, or when the two packs are in different groups:
 * a drag reorders inside a group, and only the switch moves a pack between them.
 */
internal fun List<StickerPack>.movedWithinGroup(fromKey: Any?, toKey: Any?): List<StickerPack>? {
    val from = indexOfFirst { it.id == fromKey }
    val to = indexOfFirst { it.id == toKey }
    if (from < 0 || to < 0 || this[from].hasOwnThumbnail != this[to].hasOwnThumbnail) return null
    return toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun PackList(uiState: StickerLibraryUiState, actions: StickerLibraryActions) {
    if (uiState.packs.isEmpty()) {
        if (!uiState.isLoading) {
            EmptyHint(
                title = "No stickers yet",
                body = "Tap + to make one from a photo, or to import some from WhatsApp or from files",
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
        }
        return
    }

    var renaming by remember { mutableStateOf<StickerPack?>(null) }
    var deleting by remember { mutableStateOf<StickerPack?>(null) }

    // The packs as the list shows them. A drag reorders this copy, and the drop saves its order.
    var shown by remember { mutableStateOf(uiState.packs) }
    var dragged by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        shown.movedWithinGroup(from.key, to.key)?.let {
            shown = it
            dragged = true
        }
    }
    val isDragging = reorderState.isAnyItemDragging
    LaunchedEffect(uiState.packs, isDragging) {
        // Packs that arrive during a drag are taken over when it ends.
        if (isDragging) return@LaunchedEffect
        val order = shown.map { it.id }
        if (dragged) {
            dragged = false
            if (order != uiState.packs.map { it.id }) {
                // The state answers with the packs in this order, which runs this effect again.
                actions.onReorderPacks(order)
                return@LaunchedEffect
            }
        }
        shown = uiState.packs
    }

    val selecting = uiState.selectedPackIds.isNotEmpty()
    val groups = listOf(
        ROW_GROUP_TITLE to shown.filter { it.hasOwnThumbnail },
        GROUPED_GROUP_TITLE to shown.filterNot { it.hasOwnThumbnail },
    )
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        groups.forEach { (title, packs) ->
            if (packs.isEmpty()) return@forEach
            item(key = "header:$title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            listItems(packs, key = { it.id }) { pack ->
                ReorderableItem(reorderState, key = pack.id) { isRowDragging ->
                    PackRow(
                        pack = pack,
                        isSelected = pack.id in uiState.selectedPackIds,
                        isSelecting = selecting,
                        isDragging = isRowDragging,
                        actions = actions,
                        dragHandleModifier = Modifier.draggableHandle(),
                        onRename = { renaming = pack },
                        onDelete = { deleting = pack },
                    )
                }
            }
        }
    }

    renaming?.let { pack ->
        PackNameDialog(
            title = "Rename pack",
            confirmLabel = "Rename",
            currentName = pack.name,
            onConfirm = { name ->
                renaming = null
                actions.onRenamePack(pack.id, name)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { pack ->
        DeletePacksDialog(
            packs = listOf(pack),
            onConfirm = {
                deleting = null
                actions.onDeletePack(pack.id)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** Asks once before one pack or a selection of packs is deleted, and says how many stickers go with them. */
@Composable
private fun DeletePacksDialog(packs: List<StickerPack>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val single = packs.singleOrNull()
    val count = packs.flatMap { it.stickers }.distinctBy { it.id }.size
    val what = single?.label() ?: "${packs.size} packs"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (single != null) "Delete pack" else "Delete packs") },
        text = {
            Text(
                "Delete $what and take ${if (single != null) "its" else "their"} $count ${stickers(count)} out of your library? " +
                    "A sticker that is in no other pack will not come back with a WhatsApp import."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One pack. While packs are selected ([isSelecting]) a tap picks, and the menu and the handle make way for the mark. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackRow(
    pack: StickerPack,
    isSelected: Boolean,
    isSelecting: Boolean,
    isDragging: Boolean,
    actions: StickerLibraryActions,
    dragHandleModifier: Modifier,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val count = "${pack.stickers.size} ${stickers(pack.stickers.size)}"
    ListItem(
        headlineContent = { Text(pack.label()) },
        supportingContent = {
            val countLine = pack.publisher?.let { "$count · $it" } ?: count
            // The repository refuses the switch for the two unnamed packs, which are always in the row.
            if (pack.kind.isNamed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(countLine, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(
                        "Own thumbnail",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = pack.shownInRow,
                        onCheckedChange = { actions.onSetShownInRow(pack.id, it) },
                        enabled = !isSelecting,
                        modifier = Modifier.testTag(ownThumbnailSwitchTag(pack.id)),
                    )
                }
            } else {
                Text(countLine, style = MaterialTheme.typography.bodySmall)
            }
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
            // One size in both modes, so a row does not change its height when a selection starts.
            Box(modifier = Modifier.size(width = 96.dp, height = 48.dp), contentAlignment = Alignment.CenterEnd) {
                if (isSelecting) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                } else {
                    Row {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Options for ${pack.label()}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (pack.canRename) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                                }
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                            }
                        }
                        Box(modifier = Modifier.size(48.dp).then(dragHandleModifier), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.DragHandle,
                                contentDescription = "Drag ${pack.label()} to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = when {
                isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                else -> ListItemDefaults.containerColor
            },
        ),
        modifier = Modifier.combinedClickable(
            // A tap picks once a long press has started a selection. Only a pack with a name can be merged.
            onClick = { if (isSelecting) actions.onTogglePack(pack.id) else actions.onOpenPack(pack.id) },
            onLongClick = if (pack.kind.isNamed) ({ actions.onTogglePack(pack.id) }) else null,
        ),
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

/** Asks for a pack's name, for a rename and for a merge. */
@Composable
private fun PackNameDialog(
    title: String,
    confirmLabel: String,
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
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
 * [hasStill] is false for an entry no image request can draw
 * (`WhatsAppStickerFile.hasStill`). Its cell shows a mark, and the sticker is
 * seen once it is imported.
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
    hasStill: Boolean = true,
) {
    StickerCellFrame(id, isSelected, onClick, modifier, enabled, onLongClick) {
        if (hasStill) {
            StickerThumbnail(model = model, modifier = Modifier.fillMaxSize().padding(6.dp))
        } else {
            Icon(
                Icons.Default.Animation,
                contentDescription = "Animated sticker",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }
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
    image: @Composable BoxScope.() -> Unit,
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
