package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType

/**
 * Projects a chat's messages onto the swipeable fullscreen gallery: every image
 * the chat has, in the order the message list shows them (oldest → newest), so
 * swiping forward in the viewer moves forward in time exactly like scrolling
 * down does. That ordering also means an image arriving while the viewer is open
 * appends at the end and never shifts the pages behind it.
 *
 * The order is taken from [messages] as-is rather than re-sorted by timestamp —
 * the gallery must agree with the rendered list, not with a second opinion about
 * ordering. (The Shared Media grid deliberately differs: it is newest-first,
 * because a grid is read top-down.)
 *
 * Deleted messages drop out (their tombstone bubble has no image), and so do
 * images with neither a remote URL nor a local file — nothing to decode. An
 * image still uploading has only [Message.localUri] and is kept, since its
 * bubble is on screen and tappable.
 *
 * Each item carries its [Message.id] so closing the viewer can scroll the chat
 * to the image the user swiped to.
 */
internal fun chatImageGallery(messages: List<Message>): List<FullscreenMediaItem> =
    messages
        .filter {
            it.type == MessageType.IMAGE &&
                it.deletedAt == null &&
                (it.mediaUrl != null || it.localUri != null)
        }
        .map {
            FullscreenMediaItem(
                imageUrl = it.mediaUrl,
                localUri = it.localUri,
                messageId = it.id,
            )
        }
