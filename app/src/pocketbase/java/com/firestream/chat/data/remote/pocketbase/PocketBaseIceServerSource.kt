package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.IceServerSource
import com.firestream.chat.domain.model.IceServerData
import javax.inject.Inject
import javax.inject.Singleton

/** The pocketbase backend has no relay. Calls are out of scope for v0. */
@Singleton
class PocketBaseIceServerSource @Inject constructor() : IceServerSource {
    override suspend fun fetchIceServers(): List<IceServerData> = emptyList()
}
