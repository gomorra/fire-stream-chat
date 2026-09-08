package com.firestream.chat.ui.search

import com.firestream.chat.data.remote.LinkPreview
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.util.MessageUrls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Resolves the preview behind one link result, for whichever search surface
 * asks — in-chat or global.
 *
 * Demand-driven on purpose: a Links browse can return the whole search cap, and
 * the fetch behind a preview may end in [com.firestream.chat.data.remote.WebPagePreviewCapture]'s
 * offscreen WebView, which is serialised and measured in seconds. Resolving
 * every result up front would queue minutes of capture work for rows the user
 * never scrolls to, so [request] is called by the row as it composes and the
 * work follows the viewport.
 *
 * Retry *timing* is not decided here — the cache, the single-flight and the
 * failure cooldown all stay with [LinkPreviewSource]; this only remembers what
 * it has already asked for, so a re-composition or a re-emitted result list
 * doesn't re-run the URL regex over rows it has already handled.
 *
 * Not thread-safe, and does not need to be: [request] is called from
 * composition, i.e. the main thread, the same contract `ChatMessageLoader`'s
 * scan map has.
 */
internal class SearchLinkPreviewLoader(
    private val scope: CoroutineScope,
    private val linkPreviewSource: LinkPreviewSource,
    private val onPreview: (messageId: String, preview: LinkPreview) -> Unit,
) {
    /** Message id → the text last scanned, so an edited message is rescanned. */
    private val scannedContent = mutableMapOf<String, String>()

    fun request(message: Message) {
        if (message.type != MessageType.TEXT) return
        if (scannedContent.put(message.id, message.content) == message.content) return
        // Cheap prefilter — the regex is the expensive half of this check.
        val url = message.content.takeIf { it.contains("http") }
            ?.let { MessageUrls.extractUrl(it) }
            ?: return
        scope.launch {
            val preview = linkPreviewSource.fetchPreview(url) ?: return@launch
            onPreview(message.id, preview)
        }
    }
}
