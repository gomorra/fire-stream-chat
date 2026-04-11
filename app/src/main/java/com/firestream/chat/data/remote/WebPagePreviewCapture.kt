package com.firestream.chat.data.remote

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
 * Renders a URL in a WebView hosted by a transparent, off-screen-gravity
 * Dialog attached to the current Activity, then uses PixelCopy to read the
 * Dialog window's Surface. Used as a fallback for link previews when the
 * page has no og:image / twitter:image / apple-touch-icon — so shopping
 * pages, SPAs, and other image-less sites still get a visual preview.
 *
 * A Dialog owns its own Window with its own Surface, so PixelCopy reads the
 * WebView's rendered content directly — independent of whether the Dialog
 * is visually composited on top of the activity. Setting the window alpha
 * to 0 keeps the Dialog invisible to the user while still being drawn to
 * its Surface, which is what PixelCopy reads.
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
        // Tuning knob: raise if JS-heavy pages capture a loading shell.
        private const val SETTLE_DELAY_MS = 1_800L
        private const val PIXELCOPY_TIMEOUT_MS = 5_000L
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
            val activity = currentActivityHolder.current
            if (activity == null) {
                Log.w(TAG, "No resumed Activity — skipping capture for $url")
                return@withContext null
            }

            var dialog: Dialog? = null
            var webView: WebView? = null
            try {
                val loaded = CompletableDeferred<Boolean>()
                webView = WebView(activity).apply {
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
                            // Only abort on main-frame errors; sub-resource
                            // 404s (missing favicons, tracking pixels, etc.)
                            // must not kill the capture.
                            if (request.isForMainFrame && !loaded.isCompleted) {
                                Log.w(TAG, "Main-frame error for $url: ${error.description}")
                                loaded.complete(false)
                            }
                        }
                    }
                }

                dialog = Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar).apply {
                    setContentView(
                        webView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.WHITE))
                        setDimAmount(0f)
                        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        addFlags(
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        )
                        setLayout(WIDTH, HEIGHT)
                        val params = attributes
                        // Invisible to the user but still drawn to its Surface.
                        // PixelCopy reads the Surface pre-composite, so alpha=0
                        // does not affect the captured pixels.
                        params.alpha = 0f
                        params.gravity = Gravity.START or Gravity.TOP
                        params.x = 0
                        params.y = 0
                        attributes = params
                    }
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                }
                dialog.show()
                webView.loadUrl(url)

                val ok = try {
                    withTimeout(LOAD_TIMEOUT_MS) { loaded.await() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "Timed out loading $url")
                    false
                }
                if (!ok) return@withContext null

                delay(SETTLE_DELAY_MS)

                val window = dialog.window ?: return@withContext null
                val decorView = window.decorView
                val w = decorView.width
                val h = decorView.height
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "Dialog decor has zero size ${w}x${h} for $url")
                    return@withContext null
                }

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val copyResult = CompletableDeferred<Int>()
                PixelCopy.request(
                    window,
                    bitmap,
                    { result -> copyResult.complete(result) },
                    Handler(Looper.getMainLooper())
                )
                val pixelCopyStatus = try {
                    withTimeout(PIXELCOPY_TIMEOUT_MS) { copyResult.await() }
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "PixelCopy timed out for $url")
                    bitmap.recycle()
                    return@withContext null
                }
                if (pixelCopyStatus != PixelCopy.SUCCESS) {
                    Log.w(TAG, "PixelCopy failed with status=$pixelCopyStatus for $url")
                    bitmap.recycle()
                    return@withContext null
                }

                val tmpFile = File(context.cacheDir, "${cacheFile.name}.tmp")
                try {
                    withContext(Dispatchers.IO) {
                        FileOutputStream(tmpFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                        tmpFile.renameTo(cacheFile)
                    }
                } finally {
                    bitmap.recycle()
                    if (tmpFile.exists()) tmpFile.delete()
                }
                if (!cacheFile.exists()) return@withContext null
                Log.d(TAG, "Captured preview for $url -> ${cacheFile.absolutePath}")
                Uri.fromFile(cacheFile).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture $url", e)
                null
            } finally {
                runCatching {
                    webView?.apply {
                        stopLoading()
                        loadUrl("about:blank")
                        destroy()
                    }
                }
                runCatching { dialog?.dismiss() }
            }
        }
    }
}
