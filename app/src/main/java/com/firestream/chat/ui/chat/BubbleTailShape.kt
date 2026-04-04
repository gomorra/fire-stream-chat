package com.firestream.chat.ui.chat

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A rounded-rectangle bubble shape with a small curved tail on the bottom corner.
 * The tail protrudes outward (right for own messages, left for others) and extends
 * slightly below the main body, similar to WhatsApp-style bubbles.
 */
class BubbleTailShape(
    private val cornerRadius: Dp = 16.dp,
    private val tailWidth: Dp = 6.dp,
    private val tailHeight: Dp = 8.dp,
    private val isOwnMessage: Boolean = true
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val cr = with(density) { cornerRadius.toPx() }
        val tw = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }

        // The bubble body occupies [0, size.height - th] vertically.
        // The tail extends from the body bottom down to size.height.
        val bodyBottom = size.height - th

        val path = Path().apply {
            if (isOwnMessage) {
                // Tail on bottom-right, clockwise path
                // Start at top-left corner arc start
                moveTo(cr, 0f)
                // Top edge
                lineTo(size.width - cr, 0f)
                // Top-right corner
                quadraticTo(size.width, 0f, size.width, cr)
                // Right edge down to tail junction
                lineTo(size.width, bodyBottom - cr)
                // Bottom-right: instead of a rounded corner, flow into the tail
                quadraticTo(size.width, bodyBottom, size.width, bodyBottom)
                // Tail curves outward and down
                quadraticTo(size.width + tw * 0.3f, bodyBottom + th * 0.4f, size.width + tw, size.height)
                // Tail curves back inward to the body bottom edge
                quadraticTo(size.width - cr * 0.2f, bodyBottom + th * 0.1f, size.width - cr, bodyBottom)
                // Bottom edge
                lineTo(cr, bodyBottom)
                // Bottom-left corner
                quadraticTo(0f, bodyBottom, 0f, bodyBottom - cr)
                // Left edge up
                lineTo(0f, cr)
                // Top-left corner
                quadraticTo(0f, 0f, cr, 0f)
            } else {
                // Tail on bottom-left, clockwise path
                moveTo(cr, 0f)
                // Top edge
                lineTo(size.width - cr, 0f)
                // Top-right corner
                quadraticTo(size.width, 0f, size.width, cr)
                // Right edge down
                lineTo(size.width, bodyBottom - cr)
                // Bottom-right corner
                quadraticTo(size.width, bodyBottom, size.width - cr, bodyBottom)
                // Bottom edge to tail junction
                lineTo(cr, bodyBottom)
                // Bottom-left: flow into the tail
                quadraticTo(cr * 0.2f, bodyBottom + th * 0.1f, -tw, size.height)
                // Tail curves back up to the left edge
                quadraticTo(-tw * 0.3f, bodyBottom + th * 0.4f, 0f, bodyBottom)
                // Left edge: go from body bottom up to where the corner arc starts
                lineTo(0f, cr)
                // Top-left corner
                quadraticTo(0f, 0f, cr, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}
