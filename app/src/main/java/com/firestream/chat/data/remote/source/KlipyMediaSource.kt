// region: AGENT-NOTE
// Responsibility: Klipy's HTTP API — search and trending for GIFs and stickers,
//   and the share report. Maps an answer to OnlineMedia, in Klipy's order, and
//   leaves out an item with no file on one of Klipy's media hosts.
// Owns: the request urls. The key is a path segment of every one, so a request
//   url is a secret: nothing here logs one, and every error is written here,
//   without the OkHttp exception as its cause.
// Collaborators: OnlineMediaRepositoryImpl (only caller), KlipyUrls (which urls
//   are Klipy's media), the shared OkHttpClient from NetworkModule.
// Don't put here: the customer id (PreferencesDataStore), sending a pick
//   (MessageRepositoryImpl.sendOnlineMedia), loading media. Klipy's rules forbid
//   a proxy and a copy of its media, so the app loads a file from the url as
//   returned and stores nothing (docs/plans/stickers-and-gifs.md, step 10).
// endregion

package com.firestream.chat.data.remote.source

import com.firestream.chat.BuildConfig
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaPage
import com.firestream.chat.domain.model.OnlineMediaRendition
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.util.GIF_MIME_TYPE
import com.firestream.chat.domain.util.KlipyUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Klipy answered, and not with what was asked for. Its message is fit to show and names no url. */
class KlipyException(message: String) : Exception(message)

@Singleton
class KlipyMediaSource(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: HttpUrl,
) {

    @Inject
    constructor(client: OkHttpClient) : this(client, BuildConfig.KLIPY_API_KEY, BASE_URL.toHttpUrl())

    /** False in a build without a key. Every call then fails before any request. */
    val isAvailable: Boolean get() = apiKey.isNotBlank()

    suspend fun search(kind: OnlineMediaKind, query: String, page: Int, customerId: String): OnlineMediaPage =
        fetchPage(kind, endpoint(kind, "search").addQueryParameter("q", query), page, customerId)

    suspend fun trending(kind: OnlineMediaKind, page: Int, customerId: String): OnlineMediaPage =
        fetchPage(kind, endpoint(kind, "trending"), page, customerId)

    /** Reports that the item [slug] was sent. A slug that is not a plain name is not put into a url. */
    suspend fun reportShare(kind: OnlineMediaKind, slug: String, customerId: String) {
        if (!SLUG.matches(slug)) throw KlipyException("KLIPY cannot be told about this pick")
        val body = JSONObject().put("customer_id", customerId).toString().toRequestBody(JSON)
        val url = endpoint(kind, "share").addPathSegment(slug).build()
        execute(Request.Builder().url(url).post(body).build()) { }
    }

    private suspend fun fetchPage(kind: OnlineMediaKind, url: HttpUrl.Builder, requested: Int, customerId: String): OnlineMediaPage {
        val page = requested.coerceAtLeast(1)
        val request = Request.Builder()
            .url(
                url.addQueryParameter("page", page.toString())
                    .addQueryParameter("per_page", PER_PAGE.toString())
                    .addQueryParameter("customer_id", customerId)
                    .build()
            )
            // The url holds the key, and the shared client has a disk cache.
            .cacheControl(CacheControl.Builder().noStore().build())
            .build()
        return execute(request) { body -> parsePage(kind, body, page) }
    }

    private fun endpoint(kind: OnlineMediaKind, action: String): HttpUrl.Builder {
        if (!isAvailable) throw KlipyException("GIFs and online stickers are not part of this build")
        return baseUrl.newBuilder()
            .addPathSegments("api/v1")
            .addPathSegment(apiKey)
            .addPathSegment(
                when (kind) {
                    OnlineMediaKind.GIF -> "gifs"
                    OnlineMediaKind.STICKER -> "stickers"
                }
            )
            .addPathSegment(action)
    }

    /**
     * Runs [request] and hands the body to [read]. Every failure leaves as an
     * error written here. An OkHttp exception is never the cause: its message, or
     * one further down its chain, may name the request url, which holds the key.
     */
    private suspend fun <T> execute(request: Request, read: (String) -> T): T {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw unreachable(e)
        }
        // `use` outside the dispatch: a cancel that lands before the block runs still closes the response.
        return response.use {
            withContext(Dispatchers.IO) {
                if (!it.isSuccessful) throw KlipyException("KLIPY answered with an error (${it.code})")
                try {
                    read(it.peekBody(MAX_BODY_BYTES).string())
                } catch (e: IOException) {
                    throw unreachable(e)
                } catch (e: JSONException) {
                    throw KlipyException("KLIPY sent an answer this app cannot read")
                }
            }
        }
    }

    /** An [IOException], so that `AppError.from` reads it as a lost connection. Only the class of [e] is kept. */
    private fun unreachable(e: IOException) = IOException("Could not reach KLIPY (${e.javaClass.simpleName})")

    private fun parsePage(kind: OnlineMediaKind, body: String, requestedPage: Int): OnlineMediaPage {
        val data = JSONObject(body).getJSONObject("data")
        val array = data.getJSONArray("data")
        val items = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toOnlineMedia(kind) }
        return OnlineMediaPage(items, data.optInt("current_page", requestedPage), data.optBoolean("has_next", false))
    }

    private fun JSONObject.toOnlineMedia(kind: OnlineMediaKind): OnlineMedia? {
        val files = optJSONObject("file") ?: return null
        return OnlineMedia(
            kind = kind,
            // Kept as it is: only reportShare puts it into a url, and it checks it there.
            slug = (opt("slug") as? String).orEmpty(),
            title = (opt("title") as? String).orEmpty(),
            preview = files.rendition(PREVIEW_SIZES) ?: return null,
            send = files.rendition(SEND_SIZES) ?: return null,
        )
    }

    /** The first of [sizes] that has a file on a Klipy media host, `webp` before `gif`. */
    private fun JSONObject.rendition(sizes: List<String>): OnlineMediaRendition? =
        sizes.firstNotNullOfOrNull { size ->
            val formats = optJSONObject(size) ?: return@firstNotNullOfOrNull null
            FORMATS.firstNotNullOfOrNull { (name, mimeType) -> formats.optJSONObject(name)?.toRendition(mimeType) }
        }

    private fun JSONObject.toRendition(mimeType: String): OnlineMediaRendition? {
        val url = (opt("url") as? String)?.takeIf(KlipyUrls::isMedia) ?: return null
        return OnlineMediaRendition(url, optInt("width"), optInt("height"), mimeType)
    }

    private companion object {
        const val BASE_URL = "https://api.klipy.com/"
        const val PER_PAGE = 24
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        val JSON = "application/json".toMediaType()

        /** What a slug may be made of. It becomes a path segment, where a dot segment would change the path. */
        val SLUG = Regex("[A-Za-z0-9_-]{1,200}")
        val PREVIEW_SIZES = listOf("sm", "xs", "md")
        /** Never `hd`: a message's file is loaded by every device that shows it. */
        val SEND_SIZES = listOf("md", "sm")
        val FORMATS = listOf("webp" to StickerFormat.WEBP.mimeType, "gif" to GIF_MIME_TYPE)
    }
}

/** Awaits the response headers. Cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

        override fun onResponse(call: Call, response: Response) =
            continuation.resume(response) { _, unused, _ -> unused.close() }
    })
}
