package com.firestream.chat.ui.chat.imageedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.data.util.ImageEditRasterizer

/**
 * Standard-vs-HD for the image currently on screen.
 *
 * Per-image, not global: picking a row sets `PendingMedia.isHd` for this item
 * only, and the global Settings preference stays the fallback for every item
 * the user never touches (`.claude/plans/image-editor.md` §2.5).
 *
 * The sizes are **estimates and say so**. Measuring them exactly would mean a
 * second full decode-and-encode of every image in the batch, which is precisely
 * what `MediaProcessingLimiter` exists to prevent, so [estimate] reads this one
 * image's header when the sheet opens and does arithmetic against the numbers
 * `ImageCompressor` will actually apply. When the URI cannot be read at all the
 * rows render without their size line rather than with a placeholder number the
 * user might trust.
 *
 * [estimate] arrives as a lambda rather than as an injected rasterizer: the
 * hosting ViewModel owns the dependency, which keeps this sheet — like every
 * editor composable — constructible in a Robolectric test with a fake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HdQualitySheet(
    isHd: Boolean,
    estimate: suspend (hd: Boolean) -> ImageEditRasterizer.SizeEstimate?,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var standardEstimate by remember(estimate) { mutableStateOf<ImageEditRasterizer.SizeEstimate?>(null) }
    var hdEstimate by remember(estimate) { mutableStateOf<ImageEditRasterizer.SizeEstimate?>(null) }

    LaunchedEffect(estimate) {
        standardEstimate = estimate(false)
        hdEstimate = estimate(true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Photo quality",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Applies to this photo only. Sizes are approximate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            QualityRow(
                title = "Standard",
                subtitle = "Faster to send, smaller upload",
                detail = standardEstimate?.let(::detailLine),
                selected = !isHd,
                onClick = { onSelect(false) },
            )
            QualityRow(
                title = "HD",
                subtitle = "Full resolution, best detail",
                detail = hdEstimate?.let(::detailLine),
                selected = isHd,
                onClick = { onSelect(true) },
            )
        }
    }
}

/** `1600 × 1200 · about 340 KB`, or just the dimensions when the size is unknown. */
private fun detailLine(estimate: ImageEditRasterizer.SizeEstimate): String {
    val dimensions = "${estimate.width} × ${estimate.height}"
    val size = formatBytes(estimate.bytes)
    return if (size.isEmpty()) dimensions else "$dimensions · about $size"
}

/**
 * Decimal KB/MB, one decimal place above a megabyte — the shape a gallery app
 * shows, not a binary-prefixed one. Blank for a size we could not estimate, so
 * the caller drops the label rather than printing a confident "0 KB".
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1_000_000 -> "${(bytes / 1_000f).toInt().coerceAtLeast(1)} KB"
    else -> "%.1f MB".format(bytes / 1_000_000f)
}

@Composable
private fun QualityRow(
    title: String,
    subtitle: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
