package com.firestream.chat.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * `PDF · 1.4 MB` line, with an optional [preview] above (the first lines of a
 * text file, a PDF's first page) and the caption below. Tapping the card opens
 * the file with another app ([onOpen]); a long press is the message menu.
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
    preview: (@Composable () -> Unit)? = null,
) {
    val kind = remember(message.mimeType, message.fileName) { FileKind.of(message.mimeType, message.fileName) }
    val name = message.fileName ?: "Document"
    val hasLocalCopy = remember(message.localUri) {
        message.localUri?.let(::File)?.let { it.isFile && it.canRead() } == true
    }
    val details = remember(kind, message.fileSize, hasLocalCopy, transfer) {
        fileDetailsLine(kind, message.fileSize, hasLocalCopy, transfer)
    }

    Column(modifier = Modifier.widthIn(min = 200.dp, max = 280.dp)) {
        if (preview != null) {
            preview()
            Spacer(modifier = Modifier.height(6.dp))
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

/** The tile left of the name: the kind's colour and label, or a spinner over it while a transfer runs. */
@Composable
private fun FileBadge(kind: FileKind, transfer: FileTransfer, uploadProgress: Float?) {
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
