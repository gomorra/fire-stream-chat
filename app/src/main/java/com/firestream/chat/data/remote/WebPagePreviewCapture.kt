package com.firestream.chat.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a URL in an offscreen WebView and saves the top of the page as a PNG
 * in the cache directory. Used as a fallback for link previews when the page has
 * no `og:image` / `twitter:image` / `apple-touch-icon` — so shopping pages, SPAs,
 * and other image-less sites still get a visual preview of what the user shared.
 */
@Singleton
class WebPagePreviewCapture @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val WIDTH = 1080
        private const val HEIGHT = 1440
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val SETTLE_DELAY_MS = 1500L
        private const val JPEG_QUALITY = 85
    }

    suspend fun capture(url: String): String? {
        val cacheFile = File(context.cacheDir, "link_preview_${url.hashCode()}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return Uri.fromFile(cacheFile).toString()
        }
        return withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                val loaded = CompletableDeferred<Boolean>()
                webView = WebView(context).apply {
                    // Software layer is required for offscreen draw-to-Canvas.
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = false
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14; FireStream) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            if (!loaded.isCompleted) loaded.complete(true)
                        }

                        override fun onReceivedError(
                            view: WebView,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            if (!loaded.isCompleted) loaded.complete(false)
                        }
                    }
                    measure(
                        View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, WIDTH, HEIGHT)
                    loadUrl(url)
                }

                val ok = try {
                    withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
                } catch (_: TimeoutCancellationException) {
                    false
                }
                if (!ok) return@withContext null

                // Give JS-rendered content a moment to paint before capturing.
                delay(SETTLE_DELAY_MS)

                val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    webView.draw(canvas)

                    withContext(Dispatchers.IO) {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                Uri.fromFile(cacheFile).toString()
            } catch (_: Exception) {
                null
            } finally {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    destroy()
                }
            }
        }
    }
}
