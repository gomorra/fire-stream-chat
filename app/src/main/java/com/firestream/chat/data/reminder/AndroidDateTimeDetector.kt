package com.firestream.chat.data.reminder

import android.content.Context
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLinks
import com.firestream.chat.domain.reminder.DateTimeDetector
import com.firestream.chat.domain.reminder.DetectedTimeParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort [DateTimeDetector] backed by Android's on-device
 * [TextClassifier] (`android.view.textclassifier`, stable since API 28 — well
 * within this app's `minSdk = 29`).
 *
 * ### What TextClassifier actually gives us
 * [TextClassifier.generateLinks] reliably tells us *where* a date/time
 * reference sits in the text — as a character span plus an entity type
 * (`TYPE_DATE_TIME` / `TYPE_DATE`) — via [TextLinks.TextLink]. Locale handling
 * (e.g. recognizing "tomorrow at 5pm" vs. "am Freitag um 14 Uhr") happens
 * inside the classifier using the device's configured locales, which is
 * exactly the "device locale rules" behavior this feature wants.
 *
 * What it does **not** give us, on any API level, is a parsed epoch
 * timestamp through a stable public surface. The concrete value the OS's
 * classifier resolves internally is only ever surfaced via a "create
 * calendar event" [android.view.textclassifier.TextClassification] action —
 * a [android.app.RemoteAction] wrapping a [android.app.PendingIntent] whose
 * extras are not introspectable by the sending app (by design, for privacy).
 * There is no reflection-free, honest way to pull a raw timestamp back out
 * of that. So this class uses `generateLinks()` purely for locale-aware span
 * *detection*, then hands each detected span's text to the pure,
 * unit-tested [DetectedTimeParser] to resolve an actual instant from a small,
 * deterministic vocabulary. When a message contains multiple date/time
 * spans, the nearest strictly-future candidate wins ([DetectedTimeParser.nearestFuture]).
 *
 * This is a best-effort convenience, not a guarantee: unsupported phrasing,
 * an unavailable classifier service, or any other failure all resolve to
 * `null` — the snooze picker simply shows no "Detected" preset in that case.
 */
@Singleton
class AndroidDateTimeDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : DateTimeDetector {

    override suspend fun detect(text: String, nowMs: Long): Long? = withContext(Dispatchers.Default) {
        runCatching {
            val input = text.take(MAX_INPUT_LENGTH)
            if (input.isBlank()) return@runCatching null

            val classifier = context.getSystemService(TextClassificationManager::class.java)
                ?.textClassifier
                ?: return@runCatching null

            val entityConfig = TextClassifier.EntityConfig.createWithExplicitEntityList(
                listOf(TextClassifier.TYPE_DATE_TIME, TextClassifier.TYPE_DATE),
            )
            val request = TextLinks.Request.Builder(input)
                .setEntityConfig(entityConfig)
                .build()

            val zoneId = ZoneId.systemDefault()
            val candidates = classifier.generateLinks(request).links.map { link ->
                val spanText = input.substring(link.start, link.end)
                DetectedTimeParser.parse(spanText, nowMs, zoneId)
            }
            DetectedTimeParser.nearestFuture(candidates, nowMs)
        }.getOrNull()
    }

    private companion object {
        /** Caps classifier input — messages can be arbitrarily long; the picker only needs the gist. */
        const val MAX_INPUT_LENGTH = 500
    }
}
