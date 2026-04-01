package com.firestream.chat.domain.model

data class Contact(
    val uid: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val localAvatarPath: String? = null,
    val isRegistered: Boolean = false
)
