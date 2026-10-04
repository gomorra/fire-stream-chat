package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaPage

/**
 * The online catalogue of GIFs and stickers (Klipy). Every call goes from this
 * device to the provider, which sees its IP and the query.
 */
interface OnlineMediaRepository {

    /** False in a build without a key. Nothing is requested then, and the UI offers nothing online. */
    val isAvailable: Boolean

    /** Results for [query], in the provider's order. [page] counts from 1. */
    suspend fun search(kind: OnlineMediaKind, query: String, page: Int = 1): Result<OnlineMediaPage>

    /** What the provider shows while nothing is searched for. [page] counts from 1. */
    suspend fun trending(kind: OnlineMediaKind, page: Int = 1): Result<OnlineMediaPage>

    /** Tells the provider that [media] was sent. Best-effort: a caller ignores the failure. */
    suspend fun reportShare(media: OnlineMedia): Result<Unit>
}
