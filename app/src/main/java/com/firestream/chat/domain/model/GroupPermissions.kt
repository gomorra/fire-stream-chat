package com.firestream.chat.domain.model

data class GroupPermissions(
    val sendMessages: GroupRole = GroupRole.MEMBER,
    val editGroupInfo: GroupRole = GroupRole.ADMIN,
    val addMembers: GroupRole = GroupRole.ADMIN,
    val createPolls: GroupRole = GroupRole.MEMBER,
    val isAnnouncementMode: Boolean = false
)
