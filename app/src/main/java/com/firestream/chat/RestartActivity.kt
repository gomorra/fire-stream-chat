// region: AGENT-NOTE
// Responsibility: Isolated `:restart` helper Activity that cleanly restarts the
//   main FireStream process after BackupManager closes and swaps
//   `fire_stream_chat.db`. Running in a separate process avoids the Android
//   12–15 ATMS race where killing the same process right after startActivity
//   drops the newly launched MainActivity before it resumes.
// endregion

package com.firestream.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import kotlin.system.exitProcess

class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val restartIntent = Intent.makeRestartActivityTask(launchIntent.component)
        startActivity(restartIntent)
        finish()
        exitProcess(0)
    }

    companion object {
        private const val EXTRA_MAIN_PID = "extra_main_pid"

        fun triggerRestart(context: Context) {
            val intent = Intent(context, RestartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_MAIN_PID, Process.myPid())
            }
            context.startActivity(intent)
        }
    }
}
