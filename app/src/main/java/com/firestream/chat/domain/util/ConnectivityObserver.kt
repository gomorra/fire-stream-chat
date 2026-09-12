package com.firestream.chat.domain.util

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device currently holds a network that actually reaches the
 * internet.
 *
 * **Display only.** Nothing in the send path may read this: a send is a queued
 * row plus a WorkManager job, and `NetworkType.CONNECTED` is the one source of
 * "online" for deciding when that job runs (see
 * `docs/PATTERNS.md#sends-are-idempotent-by-client-id-and-drained-by-outboxworker`).
 * This flow exists so the UI can say *why* a message still shows the clock.
 */
interface ConnectivityObserver {

    /**
     * True while the default network is validated — i.e. the system has
     * confirmed it reaches the internet. A captive-portal Wi-Fi is therefore
     * **offline** here, which is what the user experiences: nothing sends.
     */
    val isOnline: StateFlow<Boolean>
}
