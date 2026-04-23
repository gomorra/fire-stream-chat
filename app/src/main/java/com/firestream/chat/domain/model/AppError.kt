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

    data object Network : AppError {
        override val message: String = "Network unavailable"
    }

    data object Auth : AppError {
        override val message: String = "Authentication required"
    }

    data class Permission(val action: String) : AppError {
        override val message: String = "Not allowed to $action"
    }

    data class NotFound(val entity: String) : AppError {
        override val message: String = "$entity not found"
    }

    /** User-input validation failure — carries the display message verbatim. */
    data class Validation(override val message: String) : AppError

    /**
     * Fallback for anything we haven't explicitly classified. Carries the
     * original throwable so logs and bug reports keep their stack trace.
     */
    data class Unknown(val cause: Throwable) : AppError {
        override val message: String = cause.message ?: "Something went wrong"
    }

    companion object {
        fun from(throwable: Throwable): AppError = when (throwable) {
            is UnknownHostException,
            is SocketTimeoutException,
            is IOException -> Network
            else -> Unknown(throwable)
        }
    }
}
