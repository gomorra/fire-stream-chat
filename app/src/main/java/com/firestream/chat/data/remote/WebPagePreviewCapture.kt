package com.firestream.chat.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.firestream.chat.data.util.CurrentActivityHolder
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
 * Renders a URL in a WebView invisibly attached to the current Activity's
 * content view, then saves the top of the page as a JPEG in the cache. Used
 * as a fallback for link previews when the page has no og:image /
 * twitter:image / apple-touch-icon — so shopping pages, SPAs, and other
 * image-less sites still get a visual preview of what the user shared.
 *
 * Attaching to the real window is required: a detached WebView reports
 * 0-size viewports to the web renderer and `draw(canvas)` gives a blank
 * frame. We attach it at 1×1 with alpha 0 so it is functionally invisible
 * while still being part of the view hierarchy.
 */
@Singleton
class WebPagePreviewCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currentActivityHolder: CurrentActivityHolder
) {

    companion object {
        private const val TAG = "WebPagePreviewCapture"
        private const val WIDTH = 1080
        private const val HEIGHT = 1440
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val SETTLE_DELAY_MS = 1800L
        private const val JPEG_QUALITY = 85
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(url: String): String? {
        val cacheFile = File(context.cacheDir, "link_preview_${url.hashCode()}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return Uri.fromFile(cacheFile).toString()
        }
        return withContext(Dispatchers.Main) {
            val activity = currentActivityHolder.current
            if (activity == null) {
                Log.w(TAG, "No resumed Activity; cannot render WebView for $url")
                return@withContext null
            }
            val root = activity.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            if (root == null) {
                Log.w(TAG, "Activity has no content root")
                return@withContext null
            }

            var webView: WebView? = null
            try {
                val loaded = CompletableDeferred<Boolean>()
                val w = WebView(activity).apply {
                    // Translate off-screen instead of using alpha / visibility —
                    // both of those affect what draw(canvas) produces, leaving
                    // a blank bitmap. translationX is applied by the parent at
                    // composite time only, so the view still draws fully in its
                    // own coordinate space.
                    translationX = -20_000f
                    // Software layer is required for draw-to-Canvas to produce
                    // pixel data (hardware layers won't flush offscreen).
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.domStorageEnabled = true
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
                            // Favicon / sub-resource errors fire here too; only
                            // bail if the main page itself failed.
                            if (failingUrl == url && !loaded.isCompleted) {
                                loaded.complete(false)
                            }
                        }
                    }
                }
                webView = w

                // Insert at index 0 so the WebView sits behind the existing
                // Compose content — even if the off-screen translation were
                // somehow ignored, the real UI would still be drawn on top.
                root.addView(w, 0, ViewGroup.LayoutParams(WIDTH, HEIGHT))
                w.loadUrl(url)

                val ok = try {
                    withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "Timed out loading $url")
                    false
                }
                if (!ok) return@withContext null

                // Give JS-rendered content a moment to paint before capturing.
                delay(SETTLE_DELAY_MS)

                val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    w.draw(canvas)

                    withContext(Dispatchers.IO) {
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                Uri.fromFile(cacheFile).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture $url", e)
                null
            } finally {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    (parent as? ViewGroup)?.removeView(this)
                    destroy()
                }
            }
        }
    }
}
