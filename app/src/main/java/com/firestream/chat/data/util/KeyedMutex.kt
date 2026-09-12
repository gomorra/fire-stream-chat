package com.firestream.chat.data.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One [Mutex] per key, created on first use and dropped once its last user has
 * left — so a process that sends thousands of messages does not keep a lock
 * object per id for the rest of its life, and two callers that race on a key
 * still always share one mutex.
 */
class KeyedMutex<K : Any> {

    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = HashMap<K, Entry>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }

    /** Whether a lock object currently exists for [key] — somebody holds or waits for it. */
    fun isTracked(key: K): Boolean = synchronized(entries) { key in entries }
}
