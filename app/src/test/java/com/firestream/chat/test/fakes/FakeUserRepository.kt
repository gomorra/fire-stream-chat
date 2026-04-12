package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * In-memory fake [UserRepository] for unit tests. Methods not used by tests
 * throw to fail fast when a test unexpectedly exercises them.
 *
 * Follows the Now in Android pattern: `MutableSharedFlow(replay = 1)` backs
 * user observation; explicit setter methods let tests drive state.
 */
class FakeUserRepository(
    initialBlockedIds: Set<String> = emptySet(),
) : UserRepository {

    /** Mutable so tests can toggle block state. */
    private val blockedIds: MutableSet<String> = initialBlockedIds.toMutableSet()

    /** When non-null, `isUserBlocked` throws this instead of consulting [blockedIds]. */
    var blockCheckError: Throwable? = null

    private val userFlow = MutableSharedFlow<User>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun setBlocked(userId: String, blocked: Boolean) {
        if (blocked) blockedIds.add(userId) else blockedIds.remove(userId)
    }

    fun emitUser(user: User) {
        userFlow.tryEmit(user)
    }

    override fun observeUser(userId: String): Flow<User> = userFlow

    override suspend fun isUserBlocked(userId: String): Boolean {
        blockCheckError?.let { throw it }
        return userId in blockedIds
    }

    // --- Unused by current tests; throw to surface accidental usage. ---

    override suspend fun getUserById(userId: String): Result<User> =
        error("FakeUserRepository.getUserById not implemented")

    override suspend fun uploadAvatar(uri: String): Result<String> =
        error("FakeUserRepository.uploadAvatar not implemented")

    override suspend fun updateProfile(
        displayName: String?,
        statusText: String?,
        avatarUrl: String?,
    ): Result<Unit> = error("FakeUserRepository.updateProfile not implemented")

    override suspend fun setOnlineStatus(isOnline: Boolean): Result<Unit> =
        error("FakeUserRepository.setOnlineStatus not implemented")

    override suspend fun updateLastSeen(): Result<Unit> =
        error("FakeUserRepository.updateLastSeen not implemented")

    override suspend fun blockUser(userId: String): Result<Unit> {
        blockedIds.add(userId)
        return Result.success(Unit)
    }

    override suspend fun unblockUser(userId: String): Result<Unit> {
        blockedIds.remove(userId)
        return Result.success(Unit)
    }

    override suspend fun updateReadReceipts(enabled: Boolean): Result<Unit> =
        error("FakeUserRepository.updateReadReceipts not implemented")
}
