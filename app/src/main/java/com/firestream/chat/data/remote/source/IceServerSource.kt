package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.IceServerData

/** Where the relay's servers and their short-lived login come from. */
interface IceServerSource {
    /**
     * Ask the backend for a fresh set. Empty when the backend has no relay. Throws when the
     * request fails. `IceServerProvider` bounds the wait and keeps the answer.
     */
    suspend fun fetchIceServers(): List<IceServerData>
}
