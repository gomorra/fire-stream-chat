package com.firestream.chat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Executable architecture rules (Konsist). These are the mechanical fence around the
 * prose conventions in CLAUDE.md / docs/PATTERNS.md — a violation fails `./gradlew test`
 * instead of waiting for a human (or agent) to notice the gradient has degraded.
 *
 * Baselines: known, consciously-accepted violations are excluded per-file with a
 * `filterNot` and documented in TECH_DEBT.md. Fixing one means deleting its
 * `filterNot` line here, which tightens the rule automatically. Do NOT add a new
 * baseline without a matching TECH_DEBT.md entry recording the reason.
 *
 * Boundary changes: if a rule fails on code you intend to keep, the decision belongs
 * in TECH_DEBT.md (new baseline or allowlist entry), not in weakening the rule.
 */

// Parsed once per JVM (top-level val); all production source sets across modules,
// test source sets excluded. ~330 files, a few seconds.
private val production = Konsist.scopeFromProduction()

private fun KoFileDeclaration.inLayer(pathFragment: String): Boolean =
    path.contains("/com/firestream/chat/$pathFragment/")

private fun KoFileDeclaration.isFile(relativePath: String): Boolean =
    path.endsWith("/$relativePath")

/**
 * Domain may import only these. Everything else — android.*, androidx.*, Firebase,
 * Room, org.signal, data/, ui/, di/ — is an architecture violation ("Domain has no
 * Android dependencies", CLAUDE.md §Architecture).
 */
private val DOMAIN_ALLOWED_IMPORT_PREFIXES = listOf(
    "java.",
    "javax.inject.",
    "kotlin.",
    "kotlinx.coroutines.",
    "com.firestream.chat.domain.",
)

/**
 * The accepted UI→data system-boundary adapters (2026-06-09 review, LOW/accepted;
 * TECH_DEBT.md "UI imports data/ utility classes directly"). These are platform
 * adapters and preference enums, not repositories — wrapping each in a one-impl
 * domain interface would be ceremony without benefit. Additions require the same
 * conscious decision: extend this list AND the TECH_DEBT.md entry, or put the new
 * dependency behind a domain interface.
 */
private val UI_ALLOWED_DATA_IMPORTS = setOf(
    "com.firestream.chat.data.call.CallService",
    "com.firestream.chat.data.call.CallStateHolder",
    "com.firestream.chat.data.local.AppTheme",
    "com.firestream.chat.data.local.AutoDownloadOption",
    "com.firestream.chat.data.local.DictationLanguage",
    "com.firestream.chat.data.local.NotificationSound",
    "com.firestream.chat.data.local.PreferencesDataStore",
    "com.firestream.chat.data.local.ScrollPos",
    "com.firestream.chat.data.remote.LinkPreview",
    "com.firestream.chat.data.remote.LinkPreviewSource",
    "com.firestream.chat.data.remote.auth.FirebasePhoneAuth",
    "com.firestream.chat.data.remote.auth.OtpEvent",
    "com.firestream.chat.data.remote.fcm.ActiveChatTracker",
    "com.firestream.chat.data.share.ShareContentResolver",
    "com.firestream.chat.data.share.SharedContentHolder",
    "com.firestream.chat.data.timer.ScheduleResult",
    "com.firestream.chat.data.timer.TimerAlarmScheduler",
    "com.firestream.chat.data.util.ApkInstaller",
    "com.firestream.chat.data.util.ChangelogParser",
    "com.firestream.chat.data.util.ChangelogVersion",
    "com.firestream.chat.data.util.DictationEvent",
    "com.firestream.chat.data.util.MediaFileManager",
    "com.firestream.chat.data.util.SpeechRecognizerManager",
    "com.firestream.chat.data.worker.MediaBackfillWorker",
)

/**
 * The ChatUiState slice managers ("Chat*Manager slice-ownership",
 * docs/PATTERNS.md#chat-manager-slice-ownership). Scoped by exact name, not a
 * `*Manager` glob — `SpeechRecognizerManager`, `MediaFileManager`,
 * `CallNotificationManager` etc. are data-layer classes, not slice managers.
 * The roster-sync test below fails when a new Chat*Manager appears, forcing it
 * to be enrolled here.
 */
private val CHAT_MANAGERS = listOf(
    "ChatCommandsManager",
    "ChatDictationManager",
    "ChatInfoManager",
    "ChatPollManager",
    "ChatSearchManager",
)

class ArchitectureTest {

    @Test
    fun `domain layer imports only stdlib, coroutines, javax inject, and domain itself`() {
        production.files
            .filter { it.inLayer("domain") }
            // Baselined: @Composable icon slot in the .command palette model —
            // TECH_DEBT.md "ChatCommand.kt — @Composable import in the domain layer".
            .filterNot { it.isFile("domain/command/ChatCommand.kt") }
            .assertTrue { file ->
                file.imports.all { import ->
                    DOMAIN_ALLOWED_IMPORT_PREFIXES.any { import.name.startsWith(it) }
                }
            }
    }

    @Test
    fun `data layer does not import ui or navigation`() {
        production.files
            .filter { it.inLayer("data") }
            // Baselined: call-notification PendingIntent targets CallActivity directly —
            // TECH_DEBT.md "CallNotificationManager — data-to-ui import of CallActivity".
            .filterNot { it.isFile("data/call/CallNotificationManager.kt") }
            .assertFalse { file ->
                file.imports.any {
                    it.name.startsWith("com.firestream.chat.ui.") ||
                        it.name.startsWith("com.firestream.chat.navigation.")
                }
            }
    }

    @Test
    fun `ui imports from data are limited to the accepted system-boundary allowlist`() {
        production.files
            .filter { it.inLayer("ui") }
            .assertTrue { file ->
                file.imports
                    .filter { it.name.startsWith("com.firestream.chat.data.") }
                    .all { it.name in UI_ALLOWED_DATA_IMPORTS }
            }
    }

    @Test
    fun `chat managers do not reference other chat managers`() {
        // Text-level (not import-level) on purpose: the managers share a package with
        // each other and ChatViewModel, so a cross-manager reference needs no import.
        production.files
            .filter { file -> CHAT_MANAGERS.any { file.isFile("ui/chat/$it.kt") } }
            .assertFalse { file ->
                val self = file.path.substringAfterLast('/').removeSuffix(".kt")
                CHAT_MANAGERS.any { other -> other != self && file.text.contains(other) }
            }
    }

    @Test
    fun `chat manager roster stays in sync with the codebase`() {
        val discovered = production.files
            .filter { it.inLayer("ui/chat") }
            .map { it.path.substringAfterLast('/') }
            .filter { it.matches(Regex("""Chat\w+Manager\.kt""")) }
            .map { it.removeSuffix(".kt") }
            .sorted()
        assertEquals(
            "A Chat*Manager was added, renamed, or removed in ui/chat — update CHAT_MANAGERS " +
                "in ArchitectureTest so the isolation rule keeps covering every slice manager.",
            CHAT_MANAGERS.sorted(),
            discovered,
        )
    }
}
