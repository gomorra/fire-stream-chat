package com.firestream.chat.ui.chat.imageedit

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.SizeEstimate
import com.firestream.chat.domain.util.SourceImage

/**
 * Everything the editor needs from the outside world, in one parameter.
 *
 * ### Why a bundle rather than six parameters
 *
 * The send preview is already an eleven-parameter composable, and this repo has
 * a **~15-parameter ceiling that ART enforces with a `VerifyError` on first
 * render, not at compile time** — it cost a chat-open crash once already
 * (`docs/GOTCHAS.md`, `MessageBubbleCallbacks`). Robolectric runs on the JVM and
 * would not reproduce it, so the tests that cover these screens would go on
 * passing while the app crashed the moment a user opened a photo. Collapsing the
 * editor's services into one `@Immutable` bundle is what keeps that headroom,
 * exactly as `MessageBubbleCallbacks` does.
 *
 * ### Why lambdas rather than the rasterizer
 *
 * No editor composable touches Hilt (`.claude/plans/image-editor.md` §2.2). The
 * hosting ViewModel owns `ImageEditRasterizer` — the editor's single
 * `UI_ALLOWED_DATA_IMPORTS` entry — and hands its capabilities down as plain
 * functions, so every screen here is constructible in a Robolectric test with a
 * fake and the allowlist stays one entry long however many editor screens land.
 *
 * The defaults make an editor screen render inertly rather than crash when a
 * test or a preview supplies nothing: sizes are unknown, every step is assumed
 * present, and a rasterize yields null, which the screens surface as a failure
 * to apply rather than as a silently lost edit.
 */
@Immutable
internal data class ImageEditServices(
    /** Approximate output size for the HD sheet's two rows (§2.5). */
    val estimateSendSize: suspend (Uri, Boolean) -> SizeEstimate? = { _, _ -> null },

    /**
     * Whether a rasterized edit step is still on disk. `cacheDir` can be
     * reclaimed at any moment and the byte budget evicts deliberately, so the
     * cursor is checked rather than trusted (§4).
     */
    val editStepExists: (Uri) -> Boolean = { true },

    /**
     * Deletes rasterized steps nothing can reach any more. Undo cannot free the
     * file it steps off, so this is the only path that ever collects one before
     * the next app start.
     */
    val discardEditSteps: (List<String>) -> Unit = {},

    /** The source's true dimensions and file size, header-only — resize labels. */
    val probeSource: suspend (Uri) -> SourceImage? = { null },

    /**
     * An op stack applied at screen resolution, for an editor to display while
     * the user is still deciding. Never written to disk.
     */
    val renderPreview: suspend (Uri, List<RasterOp>, Int) -> Bitmap? = { _, _, _ -> null },

    /**
     * Flattens an op stack into a new JPEG and returns its URI, or null when the
     * source could not be read.
     *
     * The third argument is **every batch item's current step**, which the byte
     * budget must not evict — a page the user is not looking at owns some of the
     * oldest files in the cache while still being the image its pager page
     * shows. It is computed at call time, not captured, so it is current.
     */
    val rasterize: suspend (Uri, List<RasterOp>, Set<Uri>) -> Uri? = { _, _, _ -> null },

)
