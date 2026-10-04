// region: AGENT-NOTE
// Responsibility: The online GIF and sticker catalogue behind OnlineMediaRepository.
//   Adds the customer id to each call and turns a failure into a Result.
// Owns: when the customer id is made — only when a request is about to go out.
// Collaborators: KlipyMediaSource (the HTTP calls), PreferencesDataStore (the id).
// Don't put here: sending a pick — MessageRepositoryImpl.sendOnlineMedia, per
//   "The repository decides who a send is for" (docs/PATTERNS.md). Request urls
//   and the key stay in KlipyMediaSource.
// endregion

package com.firestream.chat.data.repository

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.source.KlipyMediaSource
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaPage
import com.firestream.chat.domain.repository.OnlineMediaRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Klipy behind [OnlineMediaRepository]. It adds the one thing the source does
 * not know: the customer id, a random id per signed-in user of this device. It
 * is made only when a request is about to go out.
 */
@Singleton
class OnlineMediaRepositoryImpl @Inject constructor(
    private val source: KlipyMediaSource,
    private val preferences: PreferencesDataStore,
) : OnlineMediaRepository {

    override val isAvailable: Boolean get() = source.isAvailable

    override suspend fun search(kind: OnlineMediaKind, query: String, page: Int): Result<OnlineMediaPage> =
        request { customerId -> source.search(kind, query, page, customerId) }

    override suspend fun trending(kind: OnlineMediaKind, page: Int): Result<OnlineMediaPage> =
        request { customerId -> source.trending(kind, page, customerId) }

    override suspend fun reportShare(media: OnlineMedia): Result<Unit> =
        request { customerId -> source.reportShare(media.kind, media.slug, customerId) }

    /**
     * Runs [call] with the customer id. A build without a key fails before an id
     * is made. A cancelled call is cancelled, not a failure: a search that the
     * next keystroke replaced has nothing to show.
     */
    private suspend fun <T> request(call: suspend (customerId: String) -> T): Result<T> =
        resultOf {
            check(isAvailable) { "GIFs and online stickers are not part of this build" }
            call(preferences.klipyCustomerId())
        }.onFailure { it.rethrowIfCancellation() }
}
