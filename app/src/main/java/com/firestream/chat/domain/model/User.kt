package com.firestream.chat.domain.model

data class User(
    val uid: String = "",
    val phoneNumber: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val statusText: String = "Hey there! I'm using Fire Stream",
    val lastSeen: Long = 0L,
    val isOnline: Boolean = false,
    val publicIdentityKey: String = ""
)
