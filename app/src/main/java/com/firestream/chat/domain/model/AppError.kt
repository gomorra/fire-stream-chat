package com.firestream.chat.domain.model

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Single source of truth for user-facing error states. Every UiState exposes
 * `error: AppError?`; every repository that surfaces errors maps through
 * [AppError.from].
 *
 * [message] is a short default string safe to show in a Snackbar. Screens
 * can branch on the subtype for richer presentation (e.g. "Retry" on
 * [Network] vs. navigate-away on [NotFound]).
 */
sealed interface AppError {
    val message: String

    /**
     * No network — emitted by [from] for `UnknownHostException`,
     * `SocketTimeoutException`, and `IOException`. Screens may offer Retry.
     * Don't construct directly; let [from] classify.
     */
    data object Network : AppError {
        override val message: String = "Network unavailable"
    }

    /** User session is missing or expired. UI should route back to LoginScreen. */
    data object Auth : AppError {
        override val message: String = "Authentication required"
    }

    /**
     * Group permission denied (e.g. announcement-mode send, non-admin edit).
     * Constructed at the permission check site — see `CheckGroupPermissionUseCase`.
     */
    data class Permission(val action: String) : AppError {
        override val message: String = "Not allowed to $action"
    }

    /** Referenced entity is gone (deleted chat / message / user). UI should navigate away. */
    data class NotFound(val entity: String) : AppError {
        override val message: String = "$entity not found"
    }

    /**
     * User-input validation failure — carries the display message verbatim.
     * Construct directly at the validation site (e.g. `ChatListViewModel.kt`'s
     * "You can pin up to 3 chats"); do not route through [from].
     */
    data class Validation(override val message: String) : AppError

    /**
     * Fallback for anything we haven't explicitly classified. Carries the
     * original throwable so logs and bug reports keep their stack trace.
     */
    data class Unknown(val cause: Throwable) : AppError {
        override val message: String = cause.message ?: "Something went wrong"
    }

    companion object {
        // Canonical wrap sites (search: `AppError.from(`):
        //   ui/auth/AuthViewModel.kt              — 3 catch blocks
        //   ui/chat/ChatMessageActions.kt         — 5 .onFailure handlers
        //   ui/chat/ChatMessageSender.kt          — 5 .onFailure handlers
        //   ui/chat/ChatMessageLoader.kt          — load failure
        //   ui/chat/ChatPollManager.kt            — 3 .onFailure handlers
        //   ui/chat/ChatInfoManager.kt            — info-load failure
        //   ui/chatlist/ChatListViewModel.kt      — load failure (+ Validation)
        //   ui/contacts/ContactsViewModel.kt      — 3 catch blocks
        //   ui/profile/ProfileViewModel.kt        — flow .catch
        // New ViewModels / Managers should follow the same shape — wrap any
        // Throwable bound for the UI through this factory; let it classify
        // network exceptions into [Network] so screens can branch on subtype.
        fun from(throwable: Throwable): AppError = when (throwable) {
            is UnknownHostException,
            is SocketTimeoutException,
            is IOException -> Network
            else -> Unknown(throwable)
        }
    }
}
