package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.firestream.chat.data.remote.LinkPreview

@Composable
internal fun LinkPreviewCard(
    preview: LinkPreview,
    textColor: Color,
    onImageClick: ((String) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = textColor.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        Column {
            if (preview.imageUrl != null) {
                AsyncImage(
                    model = preview.imageUrl,
                    contentDescription = "Link preview image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (onImageClick != null)
                                Modifier.clickable { onImageClick(preview.imageUrl) }
                            else Modifier
                        )
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (preview.title != null) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (preview.description != null) {
                Text(
                    text = preview.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
