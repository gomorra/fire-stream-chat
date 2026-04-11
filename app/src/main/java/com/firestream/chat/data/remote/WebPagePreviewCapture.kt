package com.firestream.chat.data.remote

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a URL in a headless WebView on a VirtualDisplay and saves the top
 * of the page as a JPEG in the cache directory. Used as a fallback for link
 * previews when the page has no og:image / twitter:image / apple-touch-icon,
 * so shopping pages, SPAs, and other image-less sites still get a visual
 * preview of what the user shared.
 *
 * The WebView runs inside a Presentation on a VirtualDisplay backed by an
 * ImageReader surface. This gives it a real window with hardware
 * acceleration — the software-layer draw(Canvas) path is deprecated and
 * produces blank frames on modern Chromium WebView.
 */
@Singleton
class WebPagePreviewCapture @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WebPagePreviewCapture"
        private const val WIDTH = 1080
        private const val HEIGHT = 1440
        private const val DENSITY_DPI = 240
        private const val LOAD_TIMEOUT_MS = 20_000L
        // Tuning knob: raise if JS-heavy pages show a loading shell instead
        // of real content. The capture runs after this delay elapses.
        private const val SETTLE_DELAY_MS = 1_800L
        private const val FRAME_WAIT_TIMEOUT_MS = 3_000L
        private const val JPEG_QUALITY = 85
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 14; FireStream) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(url: String): String? {
        val cacheFile = File(context.cacheDir, "link_preview_${url.hashCode()}.jpg")
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return Uri.fromFile(cacheFile).toString()
        }
        return withContext(Dispatchers.Main.immediate) {
            var reader: ImageReader? = null
            var virtualDisplay: VirtualDisplay? = null
            var presentation: Presentation? = null
            var webView: WebView? = null
            try {
                reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)

                // CONFLATED: collapse multiple producer signals into one
                // consumer wake — we only ever want the latest frame.
                // null handler → callbacks delivered on the calling thread (main).
                val frameSignal = Channel<Unit>(Channel.CONFLATED)
                reader.setOnImageAvailableListener({ frameSignal.trySend(Unit) }, null)

                val displayManager = context.getSystemService(DisplayManager::class.java)
                virtualDisplay = displayManager.createVirtualDisplay(
                    "linkPreview-${url.hashCode()}",
                    WIDTH, HEIGHT, DENSITY_DPI,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                )
                if (virtualDisplay == null) {
                    Log.w(TAG, "createVirtualDisplay returned null for $url")
                    return@withContext null
                }

                // createDisplayContext makes Resources.Configuration resolve
                // against the virtual display's metrics so content renders at
                // DENSITY_DPI instead of the host display's density.
                val displayContext = context.createDisplayContext(virtualDisplay.display)
                val themedContext = ContextThemeWrapper(
                    displayContext,
                    android.R.style.Theme_Material_Light_NoActionBar
                )

                val loaded = CompletableDeferred<Boolean>()
                val capturedWebViewRef = arrayOfNulls<WebView>(1)
                presentation = object : Presentation(themedContext, virtualDisplay.display) {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        val container = FrameLayout(themedContext)
                        val w = WebView(themedContext).apply {
                            settings.javaScriptEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = DESKTOP_UA
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, finishedUrl: String) {
                                    if (!loaded.isCompleted) loaded.complete(true)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    // Only bail when the main-frame URL itself
                                    // fails; sub-resource errors are noise.
                                    if (request.isForMainFrame && !loaded.isCompleted) {
                                        loaded.complete(false)
                                    }
                                }
                            }
                        }
                        capturedWebViewRef[0] = w
                        container.addView(
                            w,
                            FrameLayout.LayoutParams(WIDTH, HEIGHT)
                        )
                        setContentView(
                            container,
                            ViewGroup.LayoutParams(WIDTH, HEIGHT)
                        )
                    }
                }
                presentation.show()
                webView = capturedWebViewRef[0]
                if (webView == null) {
                    Log.w(TAG, "Presentation did not expose a WebView for $url")
                    return@withContext null
                }
                webView.loadUrl(url)

                val ok = try {
                    withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "Timed out loading $url")
                    false
                }
                if (!ok) return@withContext null

                delay(SETTLE_DELAY_MS)

                // Drain any pre-settle frame + signal, force a repaint, then
                // wait for the next frame. This guarantees the captured frame
                // reflects post-settle content, not a mid-load flash.
                reader.acquireLatestImage()?.close()
                frameSignal.tryReceive()
                webView.invalidate()
                try {
                    withTimeout(FRAME_WAIT_TIMEOUT_MS) { frameSignal.receive() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "No frame produced within ${FRAME_WAIT_TIMEOUT_MS}ms for $url")
                    return@withContext null
                }

                val image = reader.acquireLatestImage() ?: return@withContext null
                val bitmap = image.use { imageToBitmap(it) }
                val tmpFile = File(context.cacheDir, "${cacheFile.name}.tmp")
                try {
                    withContext(Dispatchers.IO) {
                        FileOutputStream(tmpFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                        // Atomic rename — cacheFile is never partially written.
                        tmpFile.renameTo(cacheFile)
                    }
                } finally {
                    bitmap.recycle()
                    if (tmpFile.exists()) tmpFile.delete()
                }
                if (!cacheFile.exists()) return@withContext null
                Uri.fromFile(cacheFile).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture $url", e)
                null
            } finally {
                // Order matters: WebView before Presentation before
                // VirtualDisplay before ImageReader. Releasing the display
                // before the Presentation dismisses, or closing the reader
                // before the display releases, produces Surface-consumer
                // errors or deadlocks on some OEMs.
                runCatching {
                    webView?.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        destroy()
                    }
                }
                runCatching { presentation?.dismiss() }
                runCatching { virtualDisplay?.release() }
                runCatching { reader?.close() }
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * WIDTH
        val padded = Bitmap.createBitmap(
            WIDTH + rowPadding / pixelStride,
            HEIGHT,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        return if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, WIDTH, HEIGHT).also { padded.recycle() }
        }
    }
}
