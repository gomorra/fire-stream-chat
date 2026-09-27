package com.firestream.chat.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.util.FileKind
import com.firestream.chat.domain.util.MAX_DOCUMENT_BYTES
import com.firestream.chat.domain.util.formatFileSize

/** A file picked from the attachment sheet, waiting for the user to confirm the send. */
internal data class PendingFile(val uri: Uri, val mimeType: String, val name: String?, val size: Long?) {
    val kind: FileKind get() = FileKind.of(mimeType, name)
    val isTooLarge: Boolean get() = (size ?: 0L) > MAX_DOCUMENT_BYTES

    companion object {
        /** Survives rotation: the sheet stays open with the same file. */
        val Saver: Saver<PendingFile?, Any> = Saver(
            save = { file -> file?.let { arrayListOf(it.uri.toString(), it.mimeType, it.name ?: "", it.size ?: -1L) } },
            restore = { saved ->
                val parts = saved as List<*>
                PendingFile(
                    uri = Uri.parse(parts[0] as String),
                    mimeType = parts[1] as String,
                    name = (parts[2] as String).ifEmpty { null },
                    size = (parts[3] as Long).takeIf { it >= 0 },
                )
            },
        )

        /** What the picker's provider says about [uri]: its name and size, when it answers. */
        fun describe(context: Context, uri: Uri): PendingFile {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        }
                    }
            }
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            return PendingFile(uri, mimeType, name, size)
        }
    }
}

/**
 * Confirms a picked file before it is sent: its badge, name and size, an
 * optional caption, and Send. A file over [MAX_DOCUMENT_BYTES] says so and
 * cannot be sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendFileSheet(
    file: PendingFile,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit,
) {
    var caption by rememberSaveable(file.uri) { mutableStateOf("") }
    val kind = remember(file) { file.kind }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text = "Send file", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FileBadge(kind = kind)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name ?: "Document",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        text = listOfNotNull(kind.label, file.size?.let(::formatFileSize)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (file.isTooLarge) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Files over ${formatFileSize(MAX_DOCUMENT_BYTES)} can't be sent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("Add a caption") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(modifier = Modifier.padding(4.dp))
                Button(onClick = { onSend(caption.trim()) }, enabled = !file.isTooLarge) { Text("Send") }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
