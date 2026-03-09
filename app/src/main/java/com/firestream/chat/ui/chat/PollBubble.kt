package com.firestream.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.PollOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PollBubble(
    message: Message,
    isOwnMessage: Boolean,
    currentUserId: String,
    onVote: (optionIds: List<String>) -> Unit,
    onClose: () -> Unit
) {
    val poll = message.pollData ?: return
    val bubbleColor = if (isOwnMessage) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOwnMessage) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOwnMessage) Alignment.End else Alignment.Start

    val totalVotes = remember(poll) { poll.options.sumOf { it.voterIds.size } }
    val hasVoted = remember(poll, currentUserId) { poll.options.any { it.voterIds.contains(currentUserId) } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    text = poll.question,
                    style = MaterialTheme.typography.titleSmall,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )

                if (poll.isMultipleChoice) {
                    Text(
                        text = "Multiple choice",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                poll.options.forEach { option ->
                    PollOptionRow(
                        option = option,
                        totalVotes = totalVotes,
                        hasVoted = hasVoted,
                        isClosed = poll.isClosed,
                        isSelected = option.voterIds.contains(currentUserId),
                        isAnonymous = poll.isAnonymous,
                        textColor = textColor,
                        isOwnMessage = isOwnMessage,
                        onClick = {
                            if (!poll.isClosed) {
                                if (poll.isMultipleChoice) {
                                    val currentVotes = poll.options
                                        .filter { it.voterIds.contains(currentUserId) }
                                        .map { it.id }
                                        .toMutableList()
                                    if (currentVotes.contains(option.id)) {
                                        currentVotes.remove(option.id)
                                    } else {
                                        currentVotes.add(option.id)
                                    }
                                    onVote(currentVotes)
                                } else {
                                    onVote(listOf(option.id))
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$totalVotes vote${if (totalVotes != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f)
                    )

                    if (poll.isClosed) {
                        Text(
                            text = "Closed",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.6f)
                        )
                    } else if (isOwnMessage) {
                        TextButton(onClick = onClose) {
                            Text(
                                text = "Close poll",
                                style = MaterialTheme.typography.labelSmall,
                                color = textColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(message.timestamp)),
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    option: PollOption,
    totalVotes: Int,
    hasVoted: Boolean,
    isClosed: Boolean,
    isSelected: Boolean,
    isAnonymous: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    isOwnMessage: Boolean,
    onClick: () -> Unit
) {
    val showResults = hasVoted || isClosed
    val percentage = remember(option.voterIds.size, totalVotes, showResults) {
        if (totalVotes > 0 && showResults) (option.voterIds.size * 100) / totalVotes else 0
    }

    val barColor = if (isOwnMessage) {
        textColor.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    }

    val selectedBarColor = if (isOwnMessage) {
        textColor.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !isClosed) { onClick() }
    ) {
        if (showResults) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentage / 100f)
                    .height(36.dp)
                    .background(
                        color = if (isSelected) selectedBarColor else barColor,
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    color = if (!showResults && !isClosed) {
                        textColor.copy(alpha = 0.08f)
                    } else {
                        androidx.compose.ui.graphics.Color.Transparent
                    },
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = buildString {
                    if (isSelected) append("✓ ")
                    append(option.text)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (showResults) {
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}
