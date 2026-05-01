package com.firestream.chat.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the system installer via FileProvider +
 * `ACTION_VIEW`. The user sees the standard Android installer UI and confirms
 * the upgrade. Requires `REQUEST_INSTALL_PACKAGES` and the user must have
 * granted "Install unknown apps" for FireStream Chat.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun install(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun canRequestInstall(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Intent that opens the per-app "Install unknown apps" settings screen so
     * the user can grant the permission. Caller is responsible for adding
     * `FLAG_ACTIVITY_NEW_TASK` if launching from a non-activity context.
     */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
