package com.firestream.chat.data.remote.pocketbase

/**
 * Internal callback the [PocketBaseClient] uses on a 401 to ask
 * [PocketBaseAuthSource] to re-mint a session — either via PB's
 * `/api/collections/users/auth-refresh` or by re-bridging a fresh Firebase
 * ID token.
 *
 * This indirection breaks the Hilt cycle (`PocketBaseClient` ←→
 * `PocketBaseAuthSource`); the auth source binds itself to this interface
 * so the client can call it via `Lazy<PbTokenBridge>` without holding a
 * direct reference at construction time.
 */
interface PbTokenBridge {
    /**
     * Attempt to refresh the session in-place. Returns `true` if a new token
     * was successfully persisted via [PocketBaseClient.setSession]; `false`
     * means the user must sign in again.
     */
    suspend fun refreshSession(): Boolean
}
