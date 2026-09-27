package com.firestream.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import com.firestream.chat.domain.util.FilePreview
import com.firestream.chat.domain.util.FilePreviewSource
import com.firestream.chat.domain.util.TextPreview
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.formatFileSize
import com.firestream.chat.ui.theme.FileBadgeArchive
import com.firestream.chat.ui.theme.FileBadgeAudio
import com.firestream.chat.ui.theme.FileBadgeCode
import com.firestream.chat.ui.theme.FileBadgeImage
import com.firestream.chat.ui.theme.FileBadgeOther
import com.firestream.chat.ui.theme.FileBadgePdf
import com.firestream.chat.ui.theme.FileBadgeRisky
import com.firestream.chat.ui.theme.FileBadgeSheet
import com.firestream.chat.ui.theme.FileBadgeSlides
import com.firestream.chat.ui.theme.FileBadgeText
import com.firestream.chat.ui.theme.FileBadgeVideo
import com.firestream.chat.ui.theme.FileBadgeWord
import java.io.File

/** What a file bubble shows about the transfer beside its badge. */
internal enum class FileTransfer { NONE, UPLOADING, PREPARING }

/**
 * The DOCUMENT bubble: a colour-coded type badge, the file's name and a
 * `PDF · 1.4 MB` line, with a preview above once the file is on the device (the
 * first lines of a text file, from [previews]) and the caption below. Tapping
 * the card opens the file with another app ([onOpen]); a long press is the
 * message menu.
 *
 * Its own composable rather than a branch body in `MessageBubbleBody`, which is
 * already near ART's register ceiling (see its KDoc).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileMessageBubble(
    message: Message,
    textColor: Color,
    transfer: FileTransfer,
    uploadProgress: Float?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    previews: FilePreviewSource? = null,
) {
    val kind = remember(message.mimeType, message.fileName) { FileKind.of(message.mimeType, message.fileName) }
    val name = message.fileName ?: "Document"
    // Synchronous, not produceState: the card must not render one frame as
    // "Tap to download" for a file that is there (app-ui-design skill).
    val localPath = remember(message.localUri) {
        message.localUri?.takeIf { it.startsWith("/") }?.takeIf { File(it).let { f -> f.isFile && f.canRead() } }
    }
    val hasLocalCopy = localPath != null
    // The preview itself is IO, so it loads after the card; a cached one shows at once.
    val preview by produceState(
        initialValue = localPath?.let { previews?.cached(it) },
        localPath, kind, previews,
    ) {
        if (localPath != null && previews != null && (kind.hasTextPreview || kind == FileKind.PDF)) {
            value = previews.load(localPath, kind)
        }
    }
    val details = remember(kind, message.fileSize, hasLocalCopy, transfer) {
        fileDetailsLine(kind, message.fileSize, hasLocalCopy, transfer)
    }

    Column(modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)) {
        when (val shown = preview) {
            is FilePreview.Text -> {
                TextFilePreview(shown, textColor, monospace = kind == FileKind.CODE, messageId = message.id)
                Spacer(modifier = Modifier.height(6.dp))
            }
            is FilePreview.Pdf -> {
                PdfPreview(shown, textColor, messageId = message.id, onOpen = onOpen, onLongPress = onLongPress)
                Spacer(modifier = Modifier.height(6.dp))
            }
            FilePreview.None, null -> Unit
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Open with",
                    onClick = onOpen,
                    onLongClick = onLongPress,
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 2.dp)
                .semantics { contentDescription = "$name, $details" },
        ) {
            FileBadge(kind = kind, transfer = transfer, uploadProgress = uploadProgress)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = name,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = details,
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
        if (message.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = message.content, color = textColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A PDF: the top of its first page (tap opens the file, like the card) with the
 * page count over it, then the text of its first pages as an expandable excerpt.
 * Either half may be missing — a scan has no text, an encrypted file no page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPreview(
    preview: FilePreview.Pdf,
    textColor: Color,
    messageId: String,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        preview.thumbnailPath?.let { path ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The page's own shape, but never taller than a glance: the top of
                    // page one is what identifies a document.
                    .aspectRatio(preview.thumbnailAspect.coerceAtLeast(MIN_THUMBNAIL_ASPECT))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .combinedClickable(onClickLabel = "Open with", onClick = onOpen, onLongClick = onLongPress),
            ) {
                AsyncImage(
                    model = File(path),
                    contentDescription = "First page",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.matchParentSize(),
                )
                if (preview.pageCount > 0) {
                    Text(
                        text = if (preview.pageCount == 1) "1 page" else "${preview.pageCount} pages",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        preview.text?.let { TextFilePreview(it, textColor, monospace = false, messageId = messageId) }
    }
}

/** The widest-shaped box a page thumbnail gets: 280dp wide is then at most 200dp tall. */
private const val MIN_THUMBNAIL_ASPECT = 1.4f

/**
 * The first lines of a text file, [TextPreview.COLLAPSED_LINES] of them until
 * *Show more* expands it in place to everything the preview holds — which is at
 * most [TextPreview.MAX_BYTES] of the file, and says so when the file goes on.
 */
@Composable
private fun TextFilePreview(preview: FilePreview.Text, textColor: Color, monospace: Boolean, messageId: String) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var overflows by remember(preview) { mutableStateOf(false) }
    val baseStyle = MaterialTheme.typography.bodySmall
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(textColor.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(start = 10.dp, end = 10.dp, top = 8.dp),
    ) {
        Text(
            text = preview.text,
            color = textColor,
            // Code reads by its columns; prose keeps the app's face.
            style = if (monospace) baseStyle.copy(fontFamily = FontFamily.Monospace) else baseStyle,
            maxLines = if (expanded) Int.MAX_VALUE else TextPreview.COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (expanded && preview.truncated) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Preview ends here — open the file to read the rest",
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (expanded || overflows || preview.truncated) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    text = if (expanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** The tile left of the name: the kind's colour and label, or a spinner over it while a transfer runs. */
@Composable
internal fun FileBadge(kind: FileKind, transfer: FileTransfer = FileTransfer.NONE, uploadProgress: Float? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 48.dp)
            .background(badgeColor(kind), RoundedCornerShape(8.dp)),
    ) {
        when {
            transfer == FileTransfer.UPLOADING && uploadProgress != null -> CircularProgressIndicator(
                progress = { uploadProgress },
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            transfer != FileTransfer.NONE -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            else -> Text(
                text = kind.label,
                color = Color.White,
                fontSize = if (kind.label.length > 4) 9.sp else 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** `PDF · 1.4 MB`, with what a tap will do when it is not simply "open". */
internal fun fileDetailsLine(kind: FileKind, size: Long?, hasLocalCopy: Boolean, transfer: FileTransfer): String {
    val parts = buildList {
        add(kind.label)
        size?.let { add(formatFileSize(it)) }
        when (transfer) {
            FileTransfer.UPLOADING -> add("Sending…")
            FileTransfer.PREPARING -> add("Downloading…")
            FileTransfer.NONE -> if (!hasLocalCopy) add("Tap to download")
        }
    }
    return parts.joinToString(" · ")
}

private fun badgeColor(kind: FileKind): Color = when (kind) {
    FileKind.PDF -> FileBadgePdf
    FileKind.WORD -> FileBadgeWord
    FileKind.SPREADSHEET -> FileBadgeSheet
    FileKind.PRESENTATION -> FileBadgeSlides
    FileKind.ARCHIVE -> FileBadgeArchive
    FileKind.AUDIO -> FileBadgeAudio
    FileKind.VIDEO -> FileBadgeVideo
    FileKind.IMAGE -> FileBadgeImage
    FileKind.TEXT -> FileBadgeText
    FileKind.CODE -> FileBadgeCode
    FileKind.APK, FileKind.EXECUTABLE -> FileBadgeRisky
    FileKind.OTHER -> FileBadgeOther
}

/** Confirm before opening a file that can install or run code (an APK, a script). */
@Composable
internal fun RiskyFileDialog(fileName: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open this file?") },
        text = {
            Text(
                "\"$fileName\" can install an app or run code on your device. " +
                    "Only open it if you trust the person who sent it."
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
