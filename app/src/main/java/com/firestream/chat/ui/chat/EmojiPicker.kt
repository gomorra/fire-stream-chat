package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

private data class EmojiCategory(val icon: String, val label: String, val emojis: List<String>)

private val EMOJI_CATEGORIES = listOf(
    EmojiCategory("😀", "Smileys", listOf(
        "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃",
        "😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
        "🥲","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🫢",
        "🤫","🤔","🫡","🤐","🤨","😐","😑","😶","🫥","😏",
        "😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷"
    )),
    EmojiCategory("👋", "People", listOf(
        "👋","🤚","🖐️","✋","🖖","🫱","🫲","👌","🤌","🤏",
        "✌️","🤞","🫰","🤟","🤘","🤙","👈","👉","👆","🖕",
        "👇","☝️","🫵","👍","👎","✊","👊","🤛","🤜","👏",
        "🙌","🫶","👐","🤲","🤝","🙏","💪","🦾","🦿","🦵"
    )),
    EmojiCategory("🐶", "Animals", listOf(
        "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐻‍❄️","🐨",
        "🐯","🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐒",
        "🐔","🐧","🐦","🐤","🐣","🐥","🦆","🦅","🦉","🦇",
        "🐺","🐗","🐴","🦄","🐝","🪱","🐛","🦋","🐌","🐞"
    )),
    EmojiCategory("🍎", "Food", listOf(
        "🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍈",
        "🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🍆","🥔",
        "🥕","🌽","🌶️","🫑","🥒","🥬","🥦","🧄","🧅","🍄",
        "🥜","🫘","🌰","🍞","🥐","🥖","🫓","🥨","🥯","🥞"
    )),
    EmojiCategory("⚽", "Activities", listOf(
        "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱",
        "🪀","🏓","🏸","🏒","🏑","🥍","🏏","🪃","🥅","⛳",
        "🪁","🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷",
        "⛸️","🥌","🎿","⛷️","🏂","🪂","🏋️","🤸","🤼","🤺"
    )),
    EmojiCategory("❤️", "Symbols", listOf(
        "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔",
        "❤️‍🔥","❤️‍🩹","💕","💞","💓","💗","💖","💘","💝","💟",
        "☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️",
        "♈","♉","♊","♋","♌","♍","♎","♏","♐","♑"
    )),
    EmojiCategory("🚗", "Travel", listOf(
        "🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐",
        "🛻","🚚","🚛","🚜","🏍️","🛵","🚲","🛴","🛺","🚔",
        "🚍","🚘","🚖","🛞","🚡","🚠","🚟","🚃","🚋","🚞",
        "🚝","🚄","🚅","🚈","🚂","🚆","🚇","🚊","🚉","✈️"
    )),
    EmojiCategory("💡", "Objects", listOf(
        "💡","🔦","🕯️","🪔","📱","💻","⌨️","🖥️","🖨️","🖱️",
        "🖲️","💾","💿","📀","📼","📷","📸","📹","🎥","📽️",
        "🎬","📺","📻","🎙️","🎚️","🎛️","🧭","⏱️","⏲️","⏰",
        "🔔","🔕","📢","📣","🔉","🔊","📯","🔇","🔈","🎵"
    ))
)

@Composable
internal fun EmojiPicker(
    currentReaction: String?,
    onEmojiSelected: (String) -> Unit
) {
    var selectedCategory by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "React",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QUICK_REACTIONS.forEach { emoji ->
                val isSelected = currentReaction == emoji
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.headlineMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(EMOJI_CATEGORIES) { index, category ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (index == selectedCategory) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { selectedCategory = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = category.icon, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val emojis = EMOJI_CATEGORIES[selectedCategory].emojis
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(emojis.size) { index ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onEmojiSelected(emojis[index]) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emojis[index], style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
