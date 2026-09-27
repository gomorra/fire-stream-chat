package com.firestream.chat.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * A chat file on this device, ready to hand to another app. [mimeType] is what
 * the chooser filters apps by; a wildcard type when nothing better is known.
 */
data class ReadyFile(val path: String, val mimeType: String, val displayName: String)

/**
 * Hands chat files to other apps through the app's `FileProvider` — a read grant
 * on one `content://` uri, never a raw path, so no storage permission is needed
 * on either side and the grant ends with the receiving task.
 */
object FileIntents {

    /** The system *Open with* chooser; `false` when it could not even be shown. */
    fun open(context: Context, file: ReadyFile): Boolean {
        val uri = uriFor(context, file) ?: return false
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, Intent.createChooser(view, "Open with"), view)
    }

    /** The system share sheet for the file itself (not a link to it); `false` when it could not be shown. */
    fun share(context: Context, file: ReadyFile): Boolean {
        val uri = uriFor(context, file) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            // The share sheet previews and grants through ClipData, not the extra.
            clipData = ClipData.newUri(context.contentResolver, file.displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, Intent.createChooser(send, "Share ${file.displayName}"), send)
    }

    /**
     * Starts [chooser]. No resolve pre-check: on Android 11+ that needs a
     * `<queries>` entry for every type, and the system chooser already tells the
     * user when no app can handle the file. [target] is only for the log line.
     */
    private fun launch(context: Context, chooser: Intent, target: Intent): Boolean = try {
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("FileIntents", "No activity for ${target.action} ${target.type}", e)
        false
    }

    /**
     * The shareable uri of [file], carrying its display name so a receiving app
     * that titles the document after the provider's `DISPLAY_NAME` shows the
     * original name rather than the message id. `null` for a file outside the
     * provider's roots (`res/xml/file_paths.xml`), which it refuses to grant.
     */
    private fun uriFor(context: Context, file: ReadyFile): Uri? = try {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(file.path), file.displayName)
    } catch (e: IllegalArgumentException) {
        Log.w("FileIntents", "Not a shareable file: ${file.path}", e)
        null
    }
}
