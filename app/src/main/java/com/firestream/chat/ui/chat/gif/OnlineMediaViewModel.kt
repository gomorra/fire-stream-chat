package com.firestream.chat.ui.chat.gif

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.repository.OnlineMediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What one kind of online media shows: the results for [query], or what is
 * trending while it is empty. [page] is the last page loaded, and 0 before the
 * first one arrived.
 */
@Immutable
data class OnlineMediaFeed(
    val query: String = "",
    val items: List<OnlineMedia> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val isLoading: Boolean = false,
    val error: AppError? = null,
)

/**
 * The online catalogue as the composer's picker sees it.
 *
 * [noticeAccepted] is null until the stored answer is read, so a user who
 * accepted the first-use notice never sees it flash.
 */
@Immutable
data class OnlineMediaUiState(
    val isAvailable: Boolean = false,
    val noticeAccepted: Boolean? = null,
    val gifs: OnlineMediaFeed = OnlineMediaFeed(),
    val stickers: OnlineMediaFeed = OnlineMediaFeed(),
) {
    fun feed(kind: OnlineMediaKind): OnlineMediaFeed = when (kind) {
        OnlineMediaKind.GIF -> gifs
        OnlineMediaKind.STICKER -> stickers
    }
}

/**
 * The GIFs tab and the online stickers of the composer's picker.
 *
 * A tab says what it wants to show with [onQuery]. Nothing is requested before
 * the first-use notice is accepted: until then the query is only remembered,
 * and [acceptNotice] loads it. A build without a key requests nothing at all.
 *
 * Sending a pick is `ChatViewModel`'s. This class only tells the provider
 * about it afterwards ([onSent]).
 */
@HiltViewModel
class OnlineMediaViewModel @Inject constructor(
    private val repository: OnlineMediaRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnlineMediaUiState(isAvailable = repository.isAvailable))
    val uiState: StateFlow<OnlineMediaUiState> = _uiState.asStateFlow()

    /**
     * What the tab on screen asked for last. One entry, because one tab shows
     * at a time: accepting the notice loads that tab and no other. Main thread only.
     */
    private var wanted: Pair<OnlineMediaKind, String>? = null
    private val jobs = mutableMapOf<OnlineMediaKind, Job>()

    init {
        if (repository.isAvailable) {
            viewModelScope.launch {
                repository.noticeAccepted.collect { accepted ->
                    // An accept made here is ahead of the stored flag. An older "false" must not take it back.
                    _uiState.update { it.copy(noticeAccepted = it.noticeAccepted == true || accepted) }
                    if (accepted) loadWanted()
                }
            }
        }
    }

    /** A tab is on screen and shows [query], or what is trending when it is blank. */
    fun onQuery(kind: OnlineMediaKind, query: String) {
        val trimmed = query.trim()
        wanted = kind to trimmed
        if (!canRequest()) return
        val feed = _uiState.value.feed(kind)
        // The tab was left and opened again, or the notice's two triggers both fired.
        // A feed that failed waits for the user's retry, like a failed page.
        if (feed.query == trimmed && (feed.page > 0 || feed.isLoading || feed.error != null)) return
        jobs[kind]?.cancel()
        updateFeed(kind) { OnlineMediaFeed(query = trimmed, isLoading = true) }
        jobs[kind] = viewModelScope.launch {
            // Trending is one request per open. A search waits for the typing to pause.
            if (trimmed.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
            fetch(kind, trimmed, page = 1)
        }
    }

    /** The grid reached its end. */
    fun loadMore(kind: OnlineMediaKind) {
        val feed = _uiState.value.feed(kind)
        // A failed page is retried by the user, not by the grid, which would ask again on every frame.
        if (!canRequest() || feed.isLoading || !feed.hasNext || feed.error != null) return
        loadPage(kind, feed.query, feed.page + 1)
    }

    /** Asks again for the page that failed. */
    fun retry(kind: OnlineMediaKind) {
        val feed = _uiState.value.feed(kind)
        if (!canRequest() || feed.isLoading || feed.error == null) return
        loadPage(kind, feed.query, feed.page + 1)
    }

    /**
     * The user accepted the first-use notice. The answer is written on the
     * application scope, so it is kept when the chat is left at once.
     */
    fun acceptNotice() {
        if (!repository.isAvailable) return
        _uiState.update { it.copy(noticeAccepted = true) }
        appScope.launch { repository.acceptNotice() }
        loadWanted()
    }

    /** A pick was sent. The provider is told, and a failure to tell it is ignored. */
    fun onSent(media: OnlineMedia) {
        appScope.launch { repository.reportShare(media) }
    }

    private fun canRequest(): Boolean = _uiState.value.let { it.isAvailable && it.noticeAccepted == true }

    private fun loadWanted() {
        wanted?.let { (kind, query) -> onQuery(kind, query) }
    }

    private fun loadPage(kind: OnlineMediaKind, query: String, page: Int) {
        updateFeed(kind) { it.copy(isLoading = true, error = null) }
        jobs[kind] = viewModelScope.launch { fetch(kind, query, page) }
    }

    private suspend fun fetch(kind: OnlineMediaKind, query: String, page: Int) {
        val result = if (query.isEmpty()) repository.trending(kind, page) else repository.search(kind, query, page)
        result
            .onSuccess { got ->
                updateFeed(kind) { feed ->
                    // The provider's order is kept. An item a later page repeats is shown once,
                    // because a grid keys its cells by `OnlineMedia.key`.
                    val items = (feed.items + got.items).distinctBy { it.key }
                    feed.copy(
                        items = items,
                        page = got.page,
                        // A later page that brought nothing new ends the paging, whatever the
                        // provider says. The grid's end would otherwise ask for page after page.
                        hasNext = got.hasNext && (page == 1 || items.size > feed.items.size),
                        isLoading = false,
                    )
                }
            }
            .onFailure { e -> updateFeed(kind) { it.copy(isLoading = false, error = AppError.from(e)) } }
    }

    private fun updateFeed(kind: OnlineMediaKind, change: (OnlineMediaFeed) -> OnlineMediaFeed) = _uiState.update {
        when (kind) {
            OnlineMediaKind.GIF -> it.copy(gifs = change(it.gifs))
            OnlineMediaKind.STICKER -> it.copy(stickers = change(it.stickers))
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}
