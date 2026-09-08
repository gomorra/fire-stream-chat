package com.firestream.chat.data.remote

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.firestream.chat.data.util.CurrentActivityHolder
import com.firestream.chat.data.util.MediaProcessingLimiter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
 *
 * Consent banners are suppressed in two passes, because a screenshot of a
 * cookie wall is worse than no preview at all:
 *  1. Known CMP script hosts are blocked at the request level, so the banner
 *     never renders (this also cuts seconds off the load).
 *  2. Whatever still gets through — self-hosted banners, newsletter modals,
 *     app interstitials — is stripped by [OVERLAY_STRIPPER_JS] after load.
 * Pages that are *themselves* a consent interstitial ([CONSENT_HOSTS]) are
 * abandoned outright.
 *
 * Two separate bounds apply, because they guard different resources:
 *  - [captureMutex] serialises the *WebView* work. Each capture attaches an
 *    offscreen Dialog to the current Activity and loads an arbitrary page in a
 *    hardware-accelerated WebView; several of those at once is what makes the
 *    chat list stutter while previews resolve.
 *  - [MediaProcessingLimiter] bounds the *bitmap* work, as it does for every
 *    other decode/compress path in the app. A capture holds a full bitmap and
 *    JPEG-encodes it, so it must not be a second, independent bound running
 *    alongside a multi-image send.
 *    → docs/PATTERNS.md#mediaprocessinglimiter-owns-the-concurrency-bound-callers-own-ordering
 *
 * The rendered JPEGs live in `cacheDir` and are never evicted by this class; the
 * platform reclaims them under storage pressure, which is also what retires files
 * written under any earlier cache-key scheme.
 */
@Singleton
class WebPagePreviewCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val currentActivityHolder: CurrentActivityHolder,
    private val mediaProcessingLimiter: MediaProcessingLimiter
) {

    private val captureMutex = Mutex()

    companion object {
        private const val TAG = "WebPagePreviewCapture"
        private const val WIDTH = 1080
        private const val HEIGHT = 1440
        /** Downscale target. The preview card is a full-width, 80 dp-tall strip — a
         *  1080 px JPEG costs decode time and cache space for detail nobody sees. */
        private const val OUTPUT_WIDTH = 540
        private const val LOAD_TIMEOUT_MS = 20_000L
        // Tuning knob: raise if JS-heavy pages capture a loading shell. This one
        // stays a blind wait — `onPageFinished` fires before an SPA has rendered
        // anything, and there is no signal for "the app has painted its content".
        private const val SETTLE_DELAY_MS = 1_200L
        private const val JS_TIMEOUT_MS = 2_000L
        private const val VISUAL_STATE_TIMEOUT_MS = 1_000L
        private const val VISUAL_STATE_REQUEST_ID = 1L
        private const val PIXELCOPY_TIMEOUT_MS = 5_000L
        private const val JPEG_QUALITY = 85
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Linux; Android 14; FireStream) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        /**
         * Hosts that serve consent-management scripts, plus the ad/analytics loaders
         * that most commonly inject one. Blocked as sub-resources so the banner is
         * never built in the first place — far more reliable than hiding it after the
         * fact, and it removes the biggest chunk of page-load latency.
         */
        private val BLOCKED_HOSTS = setOf(
            // Consent management platforms
            "cookielaw.org", "onetrust.com", "cookiebot.com", "cookiepro.com",
            "consensu.org", "quantcast.com", "quantserve.com",
            "privacy-center.org", "didomi.io", "usercentrics.eu",
            "trustarc.com", "truste.com", "osano.com", "iubenda.com",
            "cookieyes.com", "cookie-script.com", "cookiehub.net", "termly.io",
            "privacy-mgmt.com", "sourcepoint.mgr.consensu.org", "civiccomputing.com",
            "fundingchoicesmessages.google.com", "ensighten.com", "trustcommander.net",
            // Tag/ad/analytics loaders — these pull the CMPs above and add nothing
            // a thumbnail needs.
            "googletagmanager.com", "google-analytics.com", "googlesyndication.com",
            "doubleclick.net", "adservice.google.com", "connect.facebook.net",
            "hotjar.com", "segment.io", "segment.com", "scorecardresearch.com"
        )

        /**
         * Query parameters a gate uses to remember where to send you afterwards.
         * Landing on a *different* host that carries the URL you asked for in one of
         * these is the structural signature of a consent, login, or age wall — shared
         * by every such gate, and absent from an ordinary shortener redirect, which
         * has no reason to name its own source. Detecting the shape rather than
         * listing the hosts is what keeps this working next year.
         */
        private val REDIRECT_BACK_PARAMS = setOf(
            "continue", "return", "returnurl", "return_to", "next", "done",
            "redirect", "redirect_uri", "url"
        )

        /** Exact walls kept by name, for the ones that redirect without a back-param. */
        private val CONSENT_HOSTS = setOf(
            "consent.google.com", "consent.youtube.com",
            "consent.yahoo.com", "guce.yahoo.com"
        )

        /**
         * Removes what the request blocker could not: self-hosted cookie bars, GDPR
         * modals, newsletter pop-ups and "open in app" interstitials.
         *
         * The main rule is geometric, not a catalogue: sample a grid of points across
         * the viewport, walk what is painted at each one, and hide any `fixed`/`sticky`
         * ancestor big enough to dominate the shot. Asking the compositor what is
         * actually covering the page needs no vendor list and cannot rot — and unlike
         * enumerating the DOM, it finds banners appended at the very end of `<body>`,
         * which is exactly where CMPs put them.
         *
         * [BANNER_SELECTORS] is therefore *not* an open-ended vendor catalogue. Its
         * membership rule: only what the geometric pass provably cannot catch —
         * non-`fixed` backdrop layers, and small fixed widgets too little to trip the
         * size test. Anything a person could describe as "a big bar covering the page"
         * does not belong in it.
         *
         * Reads are separated from writes so hiding element *n* does not force a
         * synchronous reflow before element *n+1* is measured, and the script ends by
         * reading `offsetHeight` to flush layout once, so the caller's visual-state
         * callback is the only wait needed.
         */
        private const val OVERLAY_STRIPPER_JS = """
        (function () {
          try {
            var BANNER_SELECTORS = [
              '#onetrust-pc-dark-filter', '.onetrust-pc-dark-filter',
              '#CybotCookiebotDialogBodyUnderlay',
              '.didomi-popup-backdrop', '.qc-cmp-cleanslate',
              '.iubenda-cs-overlay', '#credential_picker_container',
              '.grecaptcha-badge'
            ];
            var doomed = [];
            try {
              var named = document.querySelectorAll(BANNER_SELECTORS.join(','));
              for (var n = 0; n < named.length; n++) doomed.push(named[n]);
            } catch (e) { /* one bad selector must not void the whole sweep */ }

            var vw = window.innerWidth || 1;
            var vh = window.innerHeight || 1;
            var xs = [0.15, 0.5, 0.85];
            var ys = [0.04, 0.2, 0.5, 0.8, 0.96];
            for (var xi = 0; xi < xs.length; xi++) {
              for (var yi = 0; yi < ys.length; yi++) {
                var stack = document.elementsFromPoint(vw * xs[xi], vh * ys[yi]);
                for (var si = 0; si < stack.length; si++) {
                  var el = stack[si];
                  if (el === document.body || el === document.documentElement) continue;
                  var cs = window.getComputedStyle(el);
                  if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
                  var r = el.getBoundingClientRect();
                  if (r.width <= 0 || r.height <= 0) continue;
                  var wide = r.width >= vw * 0.6;
                  var tall = r.height >= vh * 0.15;
                  if (r.width >= vw * 0.9 && r.height >= vh * 0.9) doomed.push(el);
                  else if (wide && tall) doomed.push(el);
                }
              }
            }

            for (var d = 0; d < doomed.length; d++) {
              doomed[d].style.setProperty('display', 'none', 'important');
            }

            var roots = [document.documentElement, document.body];
            for (var j = 0; j < roots.length; j++) {
              var root = roots[j];
              if (!root) continue;
              root.style.setProperty('overflow', 'visible', 'important');
              root.style.setProperty('position', 'static', 'important');
              root.style.removeProperty('padding-top');
            }
            window.scrollTo(0, 0);
            // Flush layout now, so the frame the caller waits for is the final one.
            return document.body ? document.body.offsetHeight : 0;
          } catch (e) { return 0; }
        })();
        """

        private const val HEX = "0123456789abcdef"

        /** 64 bits of SHA-256 — collision-resistant enough that two links never
         *  share a cached thumbnail, which `String.hashCode()` could not promise. */
        private fun cacheKey(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            val out = StringBuilder(16)
            for (i in 0 until 8) {
                val b = digest[i].toInt()
                out.append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
            }
            return out.toString()
        }

        /**
         * True when [host] is, or is a subdomain of, any blocked host. Walks the
         * host's dot-labels and does set lookups — a heavy page fires hundreds of
         * sub-resource requests through here, and testing every entry with a freshly
         * concatenated ".$suffix" made this the most allocation-hungry line in the
         * page-load path.
         */
        private fun isBlocked(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            val lower = host.lowercase()
            var i = 0
            while (i >= 0 && i < lower.length) {
                if (lower.substring(i) in BLOCKED_HOSTS) return true
                i = lower.indexOf('.', i).let { if (it < 0) -1 else it + 1 }
            }
            return false
        }

        /** True when [landed] looks like a consent/login/age gate that ate [requested]. */
        private fun isInterstitial(requested: String, landed: String?): Boolean {
            if (landed == null) return false
            val landedUri = runCatching { Uri.parse(landed) }.getOrNull() ?: return false
            val landedHost = landedUri.host?.lowercase() ?: return false
            if (CONSENT_HOSTS.any { landedHost == it }) return true
            val requestedHost = runCatching { Uri.parse(requested).host?.lowercase() }.getOrNull()
            if (requestedHost == null || landedHost == requestedHost) return false
            val names = runCatching { landedUri.queryParameterNames }.getOrNull().orEmpty()
            return names.any { name ->
                name.lowercase() in REDIRECT_BACK_PARAMS &&
                    runCatching { landedUri.getQueryParameter(name) }.getOrNull()
                        ?.contains(requestedHost) == true
            }
        }
    }

    /** The already-rendered thumbnail for [cacheFile], or null if there isn't one. */
    private fun cachedPreviewUri(cacheFile: File): String? =
        if (cacheFile.exists() && cacheFile.length() > 0) Uri.fromFile(cacheFile).toString() else null

    suspend fun capture(url: String): String? {
        val cacheFile = File(context.cacheDir, "link_preview_${cacheKey(url)}.jpg")
        // Callers reach this from the main thread, so the stat syscalls go to IO.
        withContext(Dispatchers.IO) { cachedPreviewUri(cacheFile) }?.let { return it }
        // One offscreen WebView at a time — see the class KDoc.
        return captureMutex.withLock {
            // A capture that finished while we waited for the lock is a cache hit now.
            withContext(Dispatchers.IO) { cachedPreviewUri(cacheFile) }
                ?: captureLocked(url, cacheFile)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureLocked(url: String, cacheFile: File): String? =
        withContext(Dispatchers.Main.immediate) {
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
                    // A thumbnail never needs popups or autoplaying media, and both
                    // only cost load time here.
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (request.isForMainFrame) return null
                            if (!isBlocked(request.url.host)) return null
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

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

                // A gate is what the whole page is — hiding elements can't reveal
                // content that was never served.
                if (isInterstitial(url, webView.url)) {
                    Log.w(TAG, "Landed on an interstitial (${webView.url}) — no preview for $url")
                    return@withContext null
                }

                delay(SETTLE_DELAY_MS)
                stripOverlays(webView)
                // The stripper flushed layout; wait for that state to reach the
                // surface, which is exactly PixelCopy's precondition.
                awaitVisualState(webView)

                val window = dialog.window ?: return@withContext null
                val decorView = window.decorView
                val w = decorView.width
                val h = decorView.height
                if (w <= 0 || h <= 0) {
                    Log.w(TAG, "Dialog decor has zero size ${w}x${h} for $url")
                    return@withContext null
                }

                // PixelCopy scales the source rect into the destination, so allocate
                // the bitmap at output size and let the copy downscale on the GPU. A
                // full-resolution 1080x1440 ARGB_8888 buffer would be ~6 MB read back
                // and then scaled again on the CPU, for detail an 80 dp-tall card
                // never shows.
                val outHeight = (h.toLong() * OUTPUT_WIDTH / w).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH, outHeight, Bitmap.Config.ARGB_8888)
                val copyResult = CompletableDeferred<Int>()
                PixelCopy.request(
                    window,
                    Rect(0, 0, w, h),
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
                val saved = withContext(Dispatchers.IO) {
                    try {
                        mediaProcessingLimiter.withPermit {
                            FileOutputStream(tmpFile).use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                            }
                        }
                        tmpFile.renameTo(cacheFile)
                    } finally {
                        bitmap.recycle()
                        if (tmpFile.exists()) tmpFile.delete()
                    }
                }
                if (!saved) return@withContext null
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

    /**
     * Runs [OVERLAY_STRIPPER_JS] and waits for it, so the hidden banners are gone
     * before PixelCopy reads the Surface. A page that never answers (JS disabled by
     * CSP, renderer wedged) simply gets captured as-is after [JS_TIMEOUT_MS].
     */
    private suspend fun stripOverlays(webView: WebView) {
        val done = CompletableDeferred<Unit>()
        runCatching {
            webView.evaluateJavascript(OVERLAY_STRIPPER_JS) { done.complete(Unit) }
        }.onFailure { return }
        try {
            withTimeout(JS_TIMEOUT_MS) { done.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Overlay stripper timed out")
        }
    }

    /**
     * Waits until the DOM as it stands right now has been drawn to the surface.
     * `postVisualStateCallback` states that precondition exactly, which is what
     * PixelCopy needs — a fixed delay can only guess at it.
     */
    private suspend fun awaitVisualState(webView: WebView) {
        val drawn = CompletableDeferred<Unit>()
        val posted = runCatching {
            webView.postVisualStateCallback(
                VISUAL_STATE_REQUEST_ID,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        drawn.complete(Unit)
                    }
                }
            )
        }.isSuccess
        if (!posted) return
        try {
            withTimeout(VISUAL_STATE_TIMEOUT_MS) { drawn.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "Visual state callback timed out")
        }
    }
}
