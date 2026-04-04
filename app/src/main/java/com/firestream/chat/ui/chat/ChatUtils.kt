package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.TextUnit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Matches emoji characters on JVM. Supplementary-plane emoji (U+1Fxxx) must use
// \x{HHHH} syntax because Java's regex engine matches code points, not surrogate pairs.
private val EMOJI_REGEX = Regex(
    "[\\x{1F000}-\\x{1FFFF}]"      + // Supplementary-plane emoji (faces, flags, objects, etc.)
    "|[\\u2600-\\u27BF]"            + // Misc symbols (☀ ★ ✉ etc.)
    "|[\\u2300-\\u23FF]"            + // Misc technical (⏰ ⌚ ✂ etc.)
    "|[\\u2B00-\\u2BFF]"            + // Misc arrows/symbols (⬛ ⬜ ⬅ etc.)
    "|\\u200D|\\uFE0F|\\u20E3"       // ZWJ, variation-selector-16, combining keycap
)

/** Returns true when [text] contains only emoji characters and whitespace. */
internal fun isEmojiOnly(text: String): Boolean =
    text.isNotBlank() && EMOJI_REGEX.replace(text, "").isBlank()

/**
 * Returns a copy of [source] with [SpanStyle.fontSize] applied to every emoji sequence.
 * The base size is [emojiSize]; per-character overrides from [sizeMultipliers] (charIndex →
 * multiplier) are applied on top — the multiplier scales relative to the base body text size,
 * not [emojiSize] itself, so a 2× multiplier always means 2× body text regardless of baseline.
 *
 * Existing spans (mentions, links, etc.) are preserved.
 */
internal fun addEmojiSpans(
    source: String,
    emojiSize: TextUnit,
    sizeMultipliers: Map<Int, Float> = emptyMap()
): AnnotatedString = addEmojiSpans(AnnotatedString(source), emojiSize, sizeMultipliers)

internal fun addEmojiSpans(
    source: AnnotatedString,
    emojiSize: TextUnit,
    sizeMultipliers: Map<Int, Float> = emptyMap()
): AnnotatedString =
    buildAnnotatedString {
        append(source)
        EMOJI_REGEX.findAll(source.text).forEach { result ->
            val multiplier = sizeMultipliers[result.range.first]
            val fontSize = if (multiplier != null) emojiSize * multiplier else emojiSize
            addStyle(SpanStyle(fontSize = fontSize), result.range.first, result.range.last + 1)
        }
    }

internal fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

internal fun isSameDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

internal fun formatDateSeparator(timestamp: Long): String {
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()

    if (isSameDay(today.timeInMillis, msgCal.timeInMillis)) return "Today"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (isSameDay(yesterday.timeInMillis, msgCal.timeInMillis)) return "Yesterday"

    val startOfThisWeek = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val startOfLastWeek = (startOfThisWeek.clone() as Calendar).apply {
        add(Calendar.WEEK_OF_YEAR, -1)
    }

    return when {
        msgCal.timeInMillis >= startOfThisWeek.timeInMillis ->
            SimpleDateFormat("EEEE · MMM d", Locale.getDefault()).format(msgCal.time)
        msgCal.timeInMillis >= startOfLastWeek.timeInMillis ->
            "Last week · ${SimpleDateFormat("MMM d", Locale.getDefault()).format(msgCal.time)}"
        else ->
            SimpleDateFormat("MMM d · yyyy", Locale.getDefault()).format(msgCal.time)
    }
}

/** Resolves the "other participant" User object for each chat, keyed by userId. */
internal suspend fun List<Chat>.resolveChatParticipants(
    currentUserId: String,
    userRepository: UserRepository
): Map<String, User> = coroutineScope {
    mapNotNull { chat -> chat.participants.firstOrNull { it != currentUserId } }
        .distinct()
        .map { id -> async { userRepository.getUserById(id).getOrNull()?.let { id to it } } }
        .awaitAll()
        .filterNotNull()
        .toMap()
}

internal fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
