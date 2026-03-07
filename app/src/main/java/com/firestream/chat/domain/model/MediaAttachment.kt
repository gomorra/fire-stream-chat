package com.firestream.chat.domain.model

data class MediaAttachment(
    val uri: String = "",
    val mimeType: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val thumbnailUri: String? = null
)
