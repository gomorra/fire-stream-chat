package com.firestream.chat.domain.model

/**
 * Outcome of [com.firestream.chat.domain.repository.MessageRepository.reconcileFromPush].
 *
 * Distinguishes a message that was intentionally suppressed (blocked sender or
 * already-open chat) from a transient fetch failure, so the push handler knows
 * whether to suppress the notification or fall back to a generic preview.
 */
sealed interface PushReconcileResult {
    data class Reconciled(val message: Message) : PushReconcileResult
    data object Suppressed : PushReconcileResult
    data object Unavailable : PushReconcileResult
}
