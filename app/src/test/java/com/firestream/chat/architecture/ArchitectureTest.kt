package com.firestream.chat.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
private val productionScope = Konsist.scopeFromProduction()

/**
 * The checkout this gate is running in — the directory Gradle started the test
 * from, with the module segment dropped.
 *
 * Absolute and normalised, because it is compared against Konsist's own absolute
 * paths below and one of the two carrying a `.` or a symlink would silently
 * match nothing.
 */
private val checkoutRoot: String =
    java.io.File("").absoluteFile.canonicalFile.let { it.parentFile ?: it }.path

/**
 * The production files these rules reason about — the files of *one* checkout.
 *
 * Konsist walks the project directory, which means a git worktree checked out
 * inside the repo — `.claude/worktrees/<branch>/`, where this harness puts one
 * when an agent works on a second branch — is scanned as a second copy of every
 * production file when the gate runs from the main tree. That breaks these rules
 * in two ways at once: the roster check below sees every `Chat*Manager` twice,
 * and an unrelated in-progress branch fails the main tree's gate with a
 * violation that is not in the main tree at all.
 *
 * The rule is therefore **"is this file part of the checkout I am running in"**,
 * not "does this path contain `/.claude/worktrees/`". Asking the second question
 * was a bug: that marker is in *every* path when the gate runs from **inside** a
 * worktree, which is the ordinary case for an agent working on a branch, so it
 * emptied the scope and turned every rule below into a vacuous pass — silently,
 * because a rule with nothing to check does not fail. Only the roster's
 * `assertEquals` noticed, and it read as a missing manager rather than as a
 * missing codebase.
 *
 * Anchoring on [checkoutRoot] answers it exactly in both directions: from the
 * main tree the nested worktrees are below the root but under the marker, and
 * from inside a worktree the root *is* the worktree and the marker is above it.
 * `the architecture rules have a codebase to check` is the tripwire for the next
 * time a predicate here goes wrong.
 */
private val production = productionScope.files.filter { file ->
    val relative = file.path.removePrefix(checkoutRoot)
    file.path.startsWith(checkoutRoot) && !relative.contains("/.claude/worktrees/")
}

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
    "com.firestream.chat.data.local.VideoQualityOption",
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
    "com.firestream.chat.data.util.ImageEditRasterizer",
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
        production
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
        production
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
        production
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
        production
            .filter { file -> CHAT_MANAGERS.any { file.isFile("ui/chat/$it.kt") } }
            .assertFalse { file ->
                val self = file.path.substringAfterLast('/').removeSuffix(".kt")
                CHAT_MANAGERS.any { other -> other != self && file.text.contains(other) }
            }
    }

    @Test
    fun `the architecture rules have a codebase to check`() {
        // Every Konsist rule in this file is an assertion over a collection, so
        // an empty [production] scope passes all of them without checking
        // anything — the rules are not satisfied, they are switched off. That is
        // exactly what happened when the worktree exclusion above matched every
        // path because the gate was running from inside a worktree, so this is
        // the tripwire for it rather than a tautology.
        assertTrue(
            "Konsist found ${production.size} production files — the rules below are checking nothing",
            production.size > 100,
        )
    }

    @Test
    fun `chat manager roster stays in sync with the codebase`() {
        val discovered = production
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
