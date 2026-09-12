// region: AGENT-NOTE
// Responsibility: Message CRUD across all message types — text / image / voice /
//   document / poll / location / list / call. A queued send (text, media, voice,
//   location, forward) is validate → optimistic insert → block check → stage the
//   input → OutboxScheduler.enqueue; the row returns SENDING at once and
//   OutboxWorker delivers it (docs/PATTERNS.md "Sends are idempotent by client
//   id and drained by OutboxWorker"). Also media download with in-flight dedup,
//   per-chat backfill scan, block-state filtering and Signal decryption on receive.
// Owns: MessageEntity rows; FAILED marking of what fails before the enqueue
//   (failSendOnError); the split between a definite block (refused) and an
//   unanswerable block check (queued — the worker asks again online); the
//   tombstone path of a message deleted while queued.
// Collaborators: MessageDao, ChatDao, FirestoreMessageSource, FirestoreUserSource,
//   BlockCheck (the cached block-list read, shared with OutboxWorker),
//   OutboxScheduler (enqueue / retryNow), OutboxFiles (staging), OutboxSender
//   (uploadProgress only), MessageWriter (the broadcast fan-out's direct write),
//   SendClock (every send's timestamp), SignalManager (decrypt path),
//   VideoTranscoder (pre-insert limit guard), PreferencesDataStore (HD default,
//   AutoDownloadOption), MediaFileManager, ConnectivityManager (WiFi-only download check).
// Don't put here: poll vote/close (PollRepositoryImpl), list mutations
//   (ListRepositoryImpl), call signalling (CallRepositoryImpl), the upload / write
//   / resume steps of a send (OutboxSender), the attempt's error policy
//   (OutboxWorker). Class is large (~1340 LOC) — Phase 2 plan adds a
//   section-comment TOC and 1100-LOC ceiling. See docs/PATTERNS.md for the
//   AppError-wrap convention.
// endregion

package com.firestream.chat.data.repository

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.firestream.chat.data.crypto.EncryptedMessage
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AutoDownloadOption
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.local.entity.MessageRecord
import com.firestream.chat.data.outbox.BlockCheck
import com.firestream.chat.data.outbox.MessageWriter
import com.firestream.chat.data.outbox.OutboxFiles
import com.firestream.chat.data.outbox.OutboxScheduler
import com.firestream.chat.data.outbox.OutboxSender
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.data.outbox.isUnacknowledged
import com.firestream.chat.data.outbox.needsUpload
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.data.util.MediaFileManager
import com.firestream.chat.data.util.VideoTranscoder
import com.firestream.chat.data.util.parseMessageStatus
import com.firestream.chat.data.util.parseMessageType
import com.firestream.chat.data.util.resolveTimerAlarmSound
import com.firestream.chat.data.util.resolveTimerAlarmStyle
import com.firestream.chat.data.util.parseTimerState
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.data.util.rethrowIfCancellation
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageSearchLimits
import com.firestream.chat.domain.model.MessageSearchResults
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.RecipientBlockedException
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import com.firestream.chat.domain.model.TimerState
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val AUTO_DOWNLOAD_TYPES = setOf(MessageType.IMAGE, MessageType.VIDEO, MessageType.DOCUMENT)
private const val ERR_NOT_AUTHENTICATED = "Not authenticated"
private const val VOICE_MESSAGE_CONTENT = "Voice message"
private const val LOCATION_DEFAULT_CONTENT = "Shared location"
private const val TAG = "MessageRepo"

// How long a block-state read stays good for. Blocking is a human-speed action,
// so this trades a few seconds of staleness for removing a backend round trip
// from in front of every snapshot reconcile and every send.
private const val BLOCK_CACHE_TTL_MS = 30_000L

// Receipt writes for a chat's unread backlog go out concurrently; the cap keeps
// a large backlog from opening an unbounded number of connections at once.
private const val RECEIPT_WRITE_CONCURRENCY = 8

// How long a list-update bubble stays "open" for further merging. Once the gap
// between the previous list update and the next one exceeds this window, the
// next update starts a fresh bubble instead of silently extending the old one.
private const val LIST_MESSAGE_MERGE_WINDOW_MS = 10L * 60L * 1000L

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageSource: MessageSource,
    private val authSource: AuthSource,
    private val signalManager: SignalManager,
    private val outboxSender: OutboxSender,
    private val outboxScheduler: OutboxScheduler,
    private val outboxFiles: OutboxFiles,
    private val blockCheck: BlockCheck,
    private val messageWriter: MessageWriter,
    private val chatRepository: dagger.Lazy<ChatRepository>,
    private val listRepository: dagger.Lazy<ListRepository>,
    private val mediaFileManager: MediaFileManager,
    private val videoTranscoder: VideoTranscoder,
    private val preferencesDataStore: PreferencesDataStore,
    private val connectivityManager: ConnectivityManager,
    private val userSource: UserSource,
    private val sendClock: SendClock,
) : MessageRepository {

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val uploadProgress: StateFlow<Map<String, Float>> = outboxSender.uploadProgress

    // The block list is read once per backend snapshot on receive, and each
    // read was a round-trip to the backend. It goes through a short-lived cache:
    // the block list changes at human speed, so a few seconds of staleness costs
    // nothing while the round trip sat in front of the message the user is
    // waiting to see. The per-peer read in front of a send is BlockCheck's.
    private val blockCacheMutex = Mutex()
    private var blockedIdsCache: Set<String>? = null
    private var blockedIdsCacheUid: String? = null
    private var blockedIdsCachedAt = 0L

    /**
     * The set of users [userId] has blocked, cached for [BLOCK_CACHE_TTL_MS].
     * Fails open (empty set) on error: a transient fetch failure must not hide
     * every message, at the cost of blocked senders rendering until the next
     * successful refresh.
     */
    private suspend fun blockedUserIds(userId: String, chatId: String): Set<String> {
        if (userId.isEmpty()) return emptySet()
        blockCacheMutex.withLock {
            val cached = blockedIdsCache
            if (cached != null && blockedIdsCacheUid == userId &&
                System.currentTimeMillis() - blockedIdsCachedAt < BLOCK_CACHE_TTL_MS
            ) {
                return cached
            }
            return try {
                userSource.getBlockedUserIds(userId).also {
                    blockedIdsCache = it
                    blockedIdsCacheUid = userId
                    blockedIdsCachedAt = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.w(TAG, "observeMessages: block-list fetch failed for chat=$chatId — block filtering degraded", e)
                emptySet()
            }
        }
    }

    private sealed interface BlockVerdict {
        data object Clear : BlockVerdict
        data object Blocked : BlockVerdict
        class Unknown(val cause: Exception) : BlockVerdict
    }

    /** Whether [senderId] may send to [target]: the cached block-list read, with an unanswerable one as its own verdict. */
    private suspend fun blockVerdict(senderId: String, target: SendTarget?): BlockVerdict {
        val peer = target?.peerId ?: return BlockVerdict.Clear
        return try {
            if (blockCheck.isBlocked(senderId, peer)) BlockVerdict.Blocked else BlockVerdict.Clear
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            BlockVerdict.Unknown(e)
        }
    }

    /**
     * The strict rule, for a send written directly (a timer): a definite block and
     * an unanswerable check both refuse it — nothing would ask again later.
     */
    private suspend fun ensureNotBlocked(senderId: String, recipientId: String) {
        when (val verdict = blockVerdict(senderId, SendTarget.of(recipientId))) {
            BlockVerdict.Blocked -> throw RecipientBlockedException()
            is BlockVerdict.Unknown -> throw verdict.cause
            BlockVerdict.Clear -> Unit
        }
    }

    /**
     * The rule in front of a queued send. A definite block refuses it —
     * [RecipientBlockedException], the row lands FAILED, the banner shows as it
     * always has. A check that cannot be answered (offline, the block list not
     * cached) lets the row queue: `OutboxWorker` runs the authoritative check
     * once it is online, and a block there is a permanent failure.
     */
    private suspend fun refuseIfBlocked(senderId: String, target: SendTarget?) {
        when (val verdict = blockVerdict(senderId, target)) {
            BlockVerdict.Blocked -> throw RecipientBlockedException()
            is BlockVerdict.Unknown ->
                Log.w(TAG, "block check unanswerable for target=$target — queuing, the worker asks again online", verdict.cause)
            BlockVerdict.Clear -> Unit
        }
    }

    /**
     * Copies a send's input to where the worker can read it after the process,
     * and with it a picker grant, are gone — unless the row has already uploaded
     * (a forward, a resumed row) or points at a file the app keeps. The row then
     * points at the copy. A DOCUMENT's [mimeType] travels as the copy's extension.
     */
    private suspend fun stageInput(row: MessageEntity, mimeType: String?) {
        val localUri = row.localUri ?: return
        if (row.mediaUrl != null) return
        outboxFiles.stage(row.id, localUri, mimeType)?.let { messageDao.updateLocalUri(row.id, it) }
    }

    /**
     * What follows an optimistic insert or a FAILED → SENDING flip: the block
     * check against [blockTarget] (`null`: none), the staged input, the worker.
     * Returns as soon as the work is enqueued — the row is SENDING and
     * `OutboxWorker` owns it from here. Anything that throws before that marks
     * the row FAILED ([failSendOnError]).
     *
     * Not cancellable: the caller runs in the chat's scope, and a user leaving
     * the chat between the insert and the enqueue would otherwise leave a
     * SENDING row no work owns until the next app start — when the picker's
     * grant is gone. The phase is short (a cached read, a file copy, an enqueue).
     */
    private suspend fun enqueueSend(
        row: MessageEntity,
        mimeType: String? = null,
        blockTarget: SendTarget? = row.sendTarget,
        retry: Boolean = false,
    ): Message = failSendOnError(row.id) {
        withContext(NonCancellable) {
            refuseIfBlocked(row.senderId, blockTarget)
            stageInput(row, mimeType)
            if (retry) outboxScheduler.retryNow(row.id, row.needsUpload) else outboxScheduler.enqueue(row.id, row.needsUpload)
            row.toDomain()
        }
    }

    override fun getMessages(chatId: String): Flow<List<Message>> {
        val currentUid = authSource.currentUserId ?: ""

        return channelFlow {
            // No SENDING → FAILED flip on chat entry any more: a SENDING row is a
            // live queued send that OutboxWorker owns, whether or not this chat is open.
            downloadPendingMediaForChat(chatId)
            launch {
                try {
                try {
                    signalManager.ensureInitialized()
                } catch (t: Throwable) {
                    t.rethrowIfCancellation()
                    Log.w(TAG, "observeMessages: Signal init failed — incoming encrypted messages may not decrypt", t)
                }
                // Every backend snapshot carries the *whole* message collection, so
                // reconciling it is O(chat length). Remember the exact RawMessage we
                // last reconciled for each id and skip the ones that did not change:
                // a receipt write on one message would otherwise re-walk (and hit Room
                // once per message for) the entire history. Scoped to this collection,
                // so re-entering the chat always does a full pass.
                val reconciled = HashMap<String, RawMessage>()
                messageSource.observeMessages(chatId)
                    // conflate(), not collectLatest(): a snapshot is complete state, so
                    // dropping intermediate ones is free — but *cancelling* a reconcile
                    // pass mid-list is not. A burst of receipt writes (one snapshot per
                    // write) used to restart the loop from index 0 every time, so the
                    // newest message at the tail could be cancelled before its Room
                    // insert on every pass and never appear until the burst stopped.
                    .conflate()
                    .collect { rawList ->
                        val blocked = blockedUserIds(currentUid, chatId)
                        for (raw in rawList) {
                            // Skip messages from users the current user has blocked.
                            // Log so "message isn't appearing" scenarios are diagnosable via logcat.
                            // Checked before the unchanged-skip so an unblock re-admits
                            // the message on the next snapshot instead of staying hidden.
                            if (raw.senderId != currentUid && raw.senderId in blocked) {
                                Log.d(TAG, "observeMessages: filtered blocked sender=${raw.senderId} msg=${raw.id} chat=$chatId")
                                continue
                            }
                            if (reconciled[raw.id] == raw) continue

                            reconcileRawMessage(raw, currentUid, chatId)
                            reconciled[raw.id] = raw
                        }
                    }
            } catch (t: Throwable) {
                t.rethrowIfCancellation()
                Log.e(TAG, "observeMessages: remote pipeline failed for chat=$chatId — serving cached messages only", t)
            }
            }

            messageDao.getMessagesByChatId(chatId)
                .conflate()
                .map { entities -> entities.map { it.toDomain() } }
                // Room re-emits on every write to the table, including the outbox
                // columns Message does not carry; an unchanged list stops here
                // instead of re-running the chat's whole message pipeline.
                .distinctUntilChanged()
                // toDomain() re-parses several JSON columns per row, for the whole
                // chat, on every emission. Collectors run on viewModelScope's main
                // dispatcher, so without this the mapping janks the frame that a
                // status write or a new message lands on.
                .flowOn(Dispatchers.Default)
                .collect { send(it) }
        }
    }

    /**
     * Reconciles one backend snapshot row into Room: soft-deletes, reaction and
     * status updates, the optimistic-echo guard for our own sends, and
     * decrypt-then-insert for incoming messages.
     *
     * Every early `return` means "nothing left to do for this [raw] in this
     * state" — the caller records the raw as reconciled once this returns
     * normally, so a throw here leaves it to be retried on the next snapshot.
     */
    private suspend fun reconcileRawMessage(raw: RawMessage, currentUid: String, chatId: String) {
        val existing = messageDao.getMessageById(raw.id)

        // Handle deletion update for any message (own or incoming)
        if (existing != null && existing.deletedAt == null && raw.deletedAt != null) {
            messageDao.softDeleteMessage(raw.id, raw.deletedAt!!)
            return
        }

        // Update reactions from remote even if message is already cached
        if (existing != null && existing.reactions != raw.reactions) {
            val reactionsJson = JSONObject().apply {
                raw.reactions.forEach { (k, v) -> put(k, v) }
            }.toString()
            messageDao.updateReactions(raw.id, reactionsJson)
        }

        if (raw.senderId == currentUid) {
            if (existing != null) {
                acknowledgeOwnEcho(raw, existing)
                return
            }
            // Skip if there's a pending optimistic message being replaced
            val pending = messageDao.getPendingSendingMessage(raw.chatId, raw.timestamp, raw.senderId)
            if (pending != null) return
            val content = raw.content ?: "[Sent message]"
            val message = raw.toMessage(content)
            messageDao.upsertRecord(MessageRecord.fromDomain(message))
            return
        }

        if (existing != null && existing.editedAt == raw.editedAt && existing.deletedAt == raw.deletedAt) return

        // Determine whether this message needs Signal decryption.
        val needsDecryption = raw.ciphertext != null && raw.signalType != null
                && !(raw.editedAt != null && raw.content != null)

        // Guard: skip messages with no usable content (unless deleted).
        if (raw.deletedAt == null && !needsDecryption && raw.content == null) return

        // Wrap decrypt+save in NonCancellable so that cancelling the
        // collector (the user leaving the chat) cannot interrupt between
        // Signal decryption (which advances the ratchet) and the Room
        // insert (which records that decryption happened). Without this,
        // a re-emitted snapshot would attempt to decrypt the same
        // ciphertext again against an already-advanced ratchet, causing
        // sporadic "unable to decrypt" errors.
        withContext(NonCancellable) {
            val content = when {
                raw.deletedAt != null -> ""
                else -> try {
                    when {
                        raw.editedAt != null && raw.content != null -> raw.content
                        needsDecryption ->
                            signalManager.decrypt(
                                raw.senderId,
                                EncryptedMessage(raw.ciphertext!!, raw.signalType!!)
                            )
                        else -> raw.content!!
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "observeMessages: decrypt failed for msg=${raw.id} sender=${raw.senderId} chat=$chatId", e)
                    "[Encrypted message — unable to decrypt]"
                }
            }

            val message = raw.toMessage(content)
            // A record write: the row's localUri, star and outbox columns are
            // not the backend's to overwrite, and the partial entity cannot.
            writeRecord(MessageRecord.fromDomain(message), existing)

            // Auto-download media for incoming messages
            if (message.mediaUrl != null && existing?.localUri == null &&
                message.type in AUTO_DOWNLOAD_TYPES
            ) {
                tryAutoDownload(message)
            }

            // Sync shared/unshared list to Room so ListsScreen updates immediately
            if (message.type == MessageType.LIST && message.listId != null &&
                (message.listDiff?.shared == true || message.listDiff?.unshared == true)
            ) {
                listRepository.get().fetchAndCacheList(message.listId!!)
            }
        }
    }

    /**
     * Wraps what runs between a send's optimistic insert and its enqueue (or, for
     * a timer, its direct write) so any failure flips the row at [messageId] to
     * FAILED — restoring the retry affordance — before rethrowing. Cancellation is
     * a control-flow signal — the user left the chat — so the row is left SENDING;
     * a queued row stays the worker's, and `OutboxScheduler.requeueAll` re-queues
     * it on the next start if the enqueue itself never happened.
     */
    private suspend fun <T> failSendOnError(messageId: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                Log.w(TAG, "Send failed for msg=$messageId — marking FAILED", t)
                runCatching { messageDao.updateMessageStatus(messageId, MessageStatus.FAILED.name) }
                    .onFailure { Log.w(TAG, "Could not mark msg=$messageId as FAILED", it) }
            }
            throw t
        }
    }

    /**
     * Send a text message to a chat.
     *
     * @param recipientId The 1:1 peer user id for INDIVIDUAL chats, used by the
     *   block check and Signal encryption. **For GROUP and BROADCAST chats,
     *   callers must pass an empty string** — Signal sessions are 1:1, so
     *   group/broadcast messages must travel through the plaintext branch of
     *   [MessageWriter.encode]. Passing an arbitrary group member
     *   as the recipient will encrypt the message for that single member and
     *   leave every other participant unable to read it.
     */
    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.TEXT,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            replyToId = replyToId,
            mentions = mentions,
            emojiSizes = emojiSizes
        )
        val row = MessageEntity.outbox(optimisticMessage, SendTarget.of(recipientId))
        messageDao.insertOutbox(row)
        enqueueSend(row)
    }

    /**
     * An acknowledged message is tombstoned on the backend and soft-deleted here.
     *
     * An own message the backend has not acknowledged — queued, in flight or
     * given up on — takes the outbox's tombstone path instead (plan §2.5).
     * Cancelling its work is not enough: a first attempt's `set()` the SDK has
     * persisted still replays on reconnect, and a hard-deleted local row would be
     * re-inserted by its own echo. So the row stays, soft-deleted (the echo then
     * finds it, and `OutboxJob` reads it as a tombstone whether it was SENDING or
     * FAILED), its staged input goes, and a REPLACE run asks `OutboxSender` to
     * write the tombstone only if the document exists.
     */
    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> = resultOf {
        val deletedAt = System.currentTimeMillis()
        val row = messageDao.getMessageById(messageId)
        if (row != null && row.senderId == authSource.currentUserId && row.isUnacknowledged) {
            messageDao.softDeleteMessage(messageId, deletedAt)
            outboxFiles.delete(messageId)
            outboxScheduler.retryNow(messageId, uploads = false)
            return@resultOf
        }
        messageSource.deleteMessage(chatId, messageId)
        messageDao.softDeleteMessage(messageId, deletedAt)
    }

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String): Result<Unit> = resultOf {
        messageSource.updateMessageStatus(chatId, messageId, status)
        messageDao.updateMessageStatus(messageId, status)
    }

    override suspend fun editMessage(chatId: String, messageId: String, newContent: String, emojiSizes: Map<Int, Float>): Result<Unit> = resultOf {
        val editedAt = System.currentTimeMillis()
        messageSource.editMessage(chatId, messageId, newContent, editedAt, emojiSizes)
        messageDao.editMessage(messageId, newContent, editedAt, emojiSizes)
    }

    /**
     * Send a media (image / video / document) message to a chat.
     *
     * @param recipientId See [sendMessage] — must be an empty string for GROUP
     *   and BROADCAST chats so the plaintext branch is used; Signal sessions
     *   are 1:1 and cannot address a group.
     * @param isHd per-image override from the send preview; `null` falls back to
     *   the global preference, so a caller that never offers the choice — the
     *   share sheet, a retry — behaves exactly as it did before per-image HD.
     */
    override suspend fun sendMediaMessage(chatId: String, uri: String, mimeType: String, recipientId: String, caption: String, isHd: Boolean?): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()
        val isImage = mimeType.startsWith("image/")
        val isVideo = mimeType.startsWith("video/")
        val messageType = when {
            isImage -> MessageType.IMAGE
            isVideo -> MessageType.VIDEO
            else -> MessageType.DOCUMENT
        }
        // Resolved onto the row: it renders the HD badge and is what OutboxSender
        // compresses by, so badge and bytes cannot disagree.
        val sendAsHd = if (isImage) {
            isHd ?: preferencesDataStore.sendImagesFullQualityFlow.first()
        } else {
            false
        }

        // Guard BEFORE the optimistic insert: reject over-limit videos so no dead
        // SENDING row is left behind. MediaLimitException maps to AppError.Validation.
        if (isVideo) videoTranscoder.ensureWithinLimits(Uri.parse(uri))

        // Insert the optimistic row BEFORE any IO so the bubble appears immediately
        // and survives a downstream failure (e.g. concurrent-compression OOM when
        // sending two images rapidly). Coil/AsyncImage renders content:// URIs, so
        // the original picked URI works as `localUri` until compression finishes.
        val placeholder = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = caption,
            type = messageType,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            localUri = uri,
            mediaWidth = null,
            mediaHeight = null,
            isHd = sendAsHd
        )
        val row = MessageEntity.outbox(placeholder, SendTarget.of(recipientId))
        messageDao.insertOutbox(row)
        enqueueSend(row, mimeType)
    }

    override suspend fun retryFailedMessage(messageId: String, recipientId: String): Result<Message> = resultOf {
        val entity = messageDao.getMessageById(messageId)
            ?: throw IllegalStateException("Cannot retry unknown message $messageId")
        if (entity.status != MessageStatus.FAILED.name) {
            throw IllegalStateException("Cannot retry message in state ${entity.status}")
        }
        // Flip the row back to SENDING so the bubble updates immediately, and give
        // it a fresh budget of automatic attempts; failSendOnError reverts it to
        // FAILED if the enqueue itself fails. OutboxSender resumes past whatever the
        // earlier attempts persisted and encrypts for the target recorded on the row
        // at insert; the screen's recipient only stands in for the block check of a
        // row that recorded none, which the worker refuses rather than guess.
        messageDao.requeueForRetry(messageId)
        val queued = entity.copy(record = entity.record.copy(status = MessageStatus.SENDING.name))
        enqueueSend(queued, blockTarget = queued.sendTarget ?: SendTarget.of(recipientId), retry = true)
    }

    override suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit> = resultOf {
        val existing = messageDao.getMessageById(messageId)
        val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
        updatedReactions[userId] = emoji
        val reactionsJson = JSONObject().apply {
            updatedReactions.forEach { (k, v) -> put(k, v) }
        }.toString()
        messageDao.updateReactions(messageId, reactionsJson)
        messageSource.updateReactions(chatId, messageId, updatedReactions)
    }

    override suspend fun removeReaction(chatId: String, messageId: String, userId: String): Result<Unit> = resultOf {
        val existing = messageDao.getMessageById(messageId)
        val updatedReactions = (existing?.reactions ?: emptyMap()).toMutableMap()
        updatedReactions.remove(userId)
        val reactionsJson = JSONObject().apply {
            updatedReactions.forEach { (k, v) -> put(k, v) }
        }.toString()
        messageDao.updateReactions(messageId, reactionsJson)
        messageSource.updateReactions(chatId, messageId, updatedReactions)
    }

    /**
     * A forward is a queued send like any other: the source message's row, re-stamped
     * for the target chat, goes through the outbox. Its media is already uploaded
     * (`mediaUrl` set), so the worker skips straight to the write.
     */
    override suspend fun forwardMessage(message: Message, targetChatId: String, recipientId: String): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val target = SendTarget.of(recipientId)
        // Before the insert: the source message stays in its chat, so a refusal
        // loses nothing and leaves no FAILED bubble in the target chat.
        refuseIfBlocked(senderId, target)
        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()

        val optimisticMessage = message.copy(
            id = tempId,
            chatId = targetChatId,
            senderId = senderId,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            isForwarded = true,
            replyToId = null,
            reactions = emptyMap(),
            // Mentions name members of the source chat, not of this one.
            mentions = emptyList(),
        )
        val row = MessageEntity.outbox(optimisticMessage, target)
        messageDao.insertOutbox(row)
        enqueueSend(row, blockTarget = null)
    }

    override suspend fun sendVoiceMessage(chatId: String, uri: String, recipientId: String, durationSeconds: Int): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = VOICE_MESSAGE_CONTENT,
            type = MessageType.VOICE,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            localUri = uri,
            duration = durationSeconds
        )
        val row = MessageEntity.outbox(optimisticMessage, SendTarget.of(recipientId))
        messageDao.insertOutbox(row)
        enqueueSend(row)
    }

    override suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit> = resultOf {
        messageDao.setStarred(messageId, starred)
    }

    override fun getStarredMessages(): Flow<List<Message>> {
        return messageDao.getStarredMessages().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun searchMessages(
        chatId: String?,
        query: String,
        filter: MessageSearchFilter,
    ): MessageSearchResults {
        return try {
            // Browse mode (blank query + an active filter) selects by chip, not
            // by text, so it takes the larger cap — see MessageSearchLimits.
            val browsing = query.isEmpty()
            val limit = MessageSearchLimits.forScope(query, chatId)
            val rows = messageDao.searchMessages(
                chatId = chatId,
                query = query,
                // LINKS is a content property, not a MessageType, so it maps to
                // `requireLink` and leaves `type` unconstrained.
                type = filter.type?.toMessageTypeName(),
                requireLink = filter.type == MessageFilterType.LINKS,
                starredOnly = filter.isStarred,
                from = filter.fromMs,
                to = filter.toMs,
                limit = limit,
            )
            // The word-boundary pass narrows LIKE's substring match to whole
            // words. It must not run in browse mode: there is no query to bound,
            // and media rows carry an empty content that no regex would match.
            val filtered = if (browsing) {
                rows
            } else {
                val regex = wordBoundaryRegex(query)
                rows.filter { regex.containsMatchIn(it.content) }
            }
            MessageSearchResults(
                messages = filtered.map { it.toDomain() },
                // Off the raw row count, not `filtered`: the word-boundary pass
                // runs after SQLite's LIMIT, so a page truncated by the cap can
                // still come back far shorter than it. See MessageSearchResults.
                truncated = rows.size >= limit,
            )
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "searchMessages failed for chat=${chatId ?: "*"} (query length=${query.length})", e)
            MessageSearchResults.EMPTY
        }
    }

    private fun MessageFilterType.toMessageTypeName(): String? = when (this) {
        MessageFilterType.PHOTOS -> MessageType.IMAGE.name
        MessageFilterType.VIDEOS -> MessageType.VIDEO.name
        MessageFilterType.DOCS -> MessageType.DOCUMENT.name
        MessageFilterType.VOICE -> MessageType.VOICE.name
        MessageFilterType.LINKS -> null
    }

    private fun wordBoundaryRegex(query: String) =
        Regex("\\b${Regex.escape(query)}\\b", RegexOption.IGNORE_CASE)

    /**
     * Fans [ids] out over [write] instead of awaiting one round trip per id.
     * Receipt writes are independent, so serialising them made opening a chat
     * with N unread messages cost N sequential round trips before the ticks
     * settled — and produced N snapshots for the reconcile loop to chew on.
     * A per-id failure is logged and skipped, matching the previous behaviour.
     */
    private suspend fun forEachReceipt(
        ids: List<String>,
        chatId: String,
        label: String,
        write: suspend (String) -> Unit
    ): Unit = coroutineScope {
        val permits = Semaphore(RECEIPT_WRITE_CONCURRENCY)
        ids.map { id ->
            async {
                try {
                    permits.withPermit { write(id) }
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    Log.w(TAG, "$label failed for msg=$id chat=$chatId", e)
                }
            }
        }.awaitAll()
    }

    override suspend fun markChatAsDelivered(chatId: String): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        val undeliveredIds = messageSource.getUndeliveredMessageIds(chatId, userId)
        forEachReceipt(undeliveredIds, chatId, "markDelivered") {
            messageSource.markDelivered(chatId, it, userId, now)
        }
    }

    override suspend fun markMessagesAsDelivered(chatId: String, messageIds: List<String>): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        forEachReceipt(messageIds, chatId, "markDelivered") {
            messageSource.markDelivered(chatId, it, userId, now)
        }
        // Batch-update Room in one shot so the DAO flow emits only once
        messageDao.updateMessageStatusBatch(messageIds, MessageStatus.DELIVERED.name)
    }

    override suspend fun markMessagesAsRead(chatId: String, messageIds: List<String>): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val now = System.currentTimeMillis()
        forEachReceipt(messageIds, chatId, "markRead") {
            messageSource.markRead(chatId, it, userId, now)
        }
        // Batch-update Room in one shot so the DAO flow emits only once
        messageDao.updateMessageStatusBatch(messageIds, MessageStatus.READ.name)
    }

    override fun getSharedMediaForUser(userId: String): Flow<List<Message>> {
        return messageDao.getSharedMediaForUser(userId).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun sendBroadcastMessage(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val timestamp = sendClock.next()

        // 1. Save message to broadcast chat (sender's record)
        val broadcastRemoteId = messageSource.sendPlainMessage(
            chatId = broadcastChatId,
            senderId = senderId,
            messageId = UUID.randomUUID().toString(),
            content = content,
            type = MessageType.TEXT,
            replyToId = null,
            timestamp = timestamp
        )
        val broadcastMessage = Message(
            id = broadcastRemoteId,
            chatId = broadcastChatId,
            senderId = senderId,
            content = content,
            type = MessageType.TEXT,
            status = MessageStatus.SENT,
            timestamp = timestamp
        )
        messageDao.upsertRecord(MessageRecord.fromDomain(broadcastMessage))
        chatDao.updateLastMessage(broadcastChatId, broadcastRemoteId, messageSource.lastContentFor(MessageType.TEXT, content), timestamp)

        // 2. Fan out to each recipient's individual chat
        val semaphore = kotlinx.coroutines.sync.Semaphore(5)
        kotlinx.coroutines.coroutineScope {
            recipientIds.map { recipientId ->
                async {
                    semaphore.acquire()
                    try {
                        // Get or create the 1:1 chat with each recipient
                        val chatResult = chatRepository.get().getOrCreateChat(recipientId)
                        val individualChat = chatResult.getOrThrow()
                        // Send as 1:1 message (encrypted in release, plain in debug).
                        // One fresh id per target chat — the broadcast's own id
                        // must not be reused across collections.
                        val fanOut = Message(
                            id = UUID.randomUUID().toString(),
                            chatId = individualChat.id,
                            senderId = senderId,
                            content = content,
                            type = MessageType.TEXT,
                            status = MessageStatus.SENDING,
                            timestamp = timestamp,
                        )
                        val fanOutRemoteId = messageWriter.send(fanOut, SendTarget.Peer(recipientId))
                        chatDao.updateLastMessage(individualChat.id, fanOutRemoteId, messageSource.lastContentFor(MessageType.TEXT, content), timestamp)
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        // Best-effort delivery to each recipient
                        Log.w(TAG, "sendBroadcastMessage: fan-out failed for recipient=$recipientId", e)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        broadcastMessage
    }

    override suspend fun sendListMessage(
        chatId: String,
        listId: String,
        listTitle: String,
        listDiff: ListDiff?
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val timestamp = sendClock.next()
        val content = when {
            listDiff?.shared == true -> "\uD83D\uDCCB Shared list: $listTitle"
            listDiff?.unshared == true -> "\uD83D\uDCCB Removed list: $listTitle"
            listDiff?.deleted == true -> "\uD83D\uDCCB Deleted list: $listTitle"
            else -> "\uD83D\uDCCB List updated: $listTitle"
        }

        // Merge into the last message if it's a diff bubble for the same list from this user,
        // but only while the previous update is still within the merge window. Once the gap
        // exceeds LIST_MESSAGE_MERGE_WINDOW_MS, a new bubble is started so later activity is
        // visible instead of silently extending a stale bubble.
        if (listDiff != null && !listDiff.deleted && !listDiff.unshared && !listDiff.shared) {
            val lastEntity = messageDao.getLastMessageByChatId(chatId)
            if (lastEntity != null) {
                val lastMessage = lastEntity.toDomain()
                if (lastMessage.type == MessageType.LIST
                    && lastMessage.listId == listId
                    && lastMessage.listDiff != null
                    && !lastMessage.listDiff.deleted
                    && !lastMessage.listDiff.unshared
                    && !lastMessage.listDiff.shared
                    && lastMessage.senderId == senderId
                    && (timestamp - lastMessage.timestamp) < LIST_MESSAGE_MERGE_WINDOW_MS
                ) {
                    val mergedDiff = ListDiff.accumulate(lastMessage.listDiff, listDiff)
                    messageSource.updateListMessageDiff(chatId, lastMessage.id, content, mergedDiff.toMap(), timestamp)
                    val updatedMessage = lastMessage.copy(
                        content = content,
                        listDiff = mergedDiff,
                        timestamp = timestamp,
                        editedAt = timestamp
                    )
                    messageDao.upsertRecord(MessageRecord.fromDomain(updatedMessage))
                    chatDao.updateLastMessage(chatId, lastMessage.id, messageSource.lastContentFor(MessageType.LIST, content), timestamp)
                    return@resultOf updatedMessage
                }
            }
        }

        val remoteId = messageSource.sendListMessage(
            chatId = chatId,
            senderId = senderId,
            listId = listId,
            content = content,
            timestamp = timestamp,
            listDiff = listDiff?.toMap()
        )

        val message = Message(
            id = remoteId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.LIST,
            status = MessageStatus.SENT,
            timestamp = timestamp,
            listId = listId,
            listDiff = listDiff
        )
        messageDao.upsertRecord(MessageRecord.fromDomain(message))
        chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.LIST, content), timestamp)
        message
    }

    override suspend fun pinMessage(
        chatId: String,
        messageId: String,
        pinned: Boolean
    ): Result<Unit> = resultOf {
        messageSource.pinMessage(chatId, messageId, pinned)
        // A column update: a whole-row replace would reset an unsent row's outbox columns.
        messageDao.setPinned(messageId, pinned)
    }

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        recipientId: String,
        comment: String
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()
        val content = comment.ifBlank { LOCATION_DEFAULT_CONTENT }

        val optimisticMessage = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.LOCATION,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            latitude = latitude,
            longitude = longitude
        )
        val row = MessageEntity.outbox(optimisticMessage, SendTarget.of(recipientId))
        messageDao.insertOutbox(row)
        enqueueSend(row)
    }

    override suspend fun sendTimerMessage(
        chatId: String,
        durationMs: Long,
        caption: String?,
        recipientId: String,
        style: TimerAlarmStyle,
        sound: TimerAlarmSound,
    ): Result<Message> = resultOf {
        val senderId = authSource.currentUserId ?: throw Exception(ERR_NOT_AUTHENTICATED)
        require(durationMs > 0L) { "Timer duration must be positive" }

        val tempId = UUID.randomUUID().toString()
        val timestamp = sendClock.next()
        val content = caption.orEmpty()

        val optimistic = Message(
            id = tempId,
            chatId = chatId,
            senderId = senderId,
            content = content,
            type = MessageType.TIMER,
            status = MessageStatus.SENDING,
            timestamp = timestamp,
            timerDurationMs = durationMs,
            timerStartedAtMs = timestamp,
            timerState = TimerState.RUNNING,
            timerAlarmStyle = style,
            timerAlarmSound = sound,
        )
        messageDao.upsertRecord(MessageRecord.fromDomain(optimistic))

        failSendOnError(tempId) {
            ensureNotBlocked(senderId, recipientId)
            val result = messageSource.sendTimerMessage(
                chatId = chatId,
                senderId = senderId,
                durationMs = durationMs,
                caption = caption,
                timestamp = timestamp,
                style = style,
                sound = sound,
            )

            val sent = optimistic.copy(
                id = result.messageId,
                status = MessageStatus.SENT,
                timerStartedAtMs = result.startedAtMs,
            )
            messageDao.markSent(tempId, MessageRecord.fromDomain(sent), sent.localUri)
            chatDao.updateLastMessage(
                chatId,
                result.messageId,
                messageSource.lastContentFor(MessageType.TIMER, content),
                timestamp,
            )
            sent
        }
    }

    override suspend fun cancelTimer(chatId: String, messageId: String): Result<Unit> = resultOf {
        messageSource.updateTimerState(chatId, messageId, TimerState.CANCELLED.name)
        messageDao.getMessageById(messageId)?.let { existing ->
            messageDao.upsertRecord(existing.record.copy(timerState = TimerState.CANCELLED.name))
        }
    }

    override suspend fun markTimerCompleted(chatId: String, messageId: String): Result<Unit> = resultOf {
        messageSource.updateTimerState(chatId, messageId, TimerState.COMPLETED.name)
        messageDao.getMessageById(messageId)?.let { existing ->
            messageDao.upsertRecord(existing.record.copy(timerState = TimerState.COMPLETED.name))
        }
    }

    override suspend fun pauseTimer(
        chatId: String,
        messageId: String,
        remainingMs: Long,
    ): Result<Unit> = resultOf {
        messageSource.pauseTimer(chatId, messageId, remainingMs)
        messageDao.getMessageById(messageId)?.let { existing ->
            messageDao.upsertRecord(
                existing.record.copy(
                    timerState = TimerState.PAUSED.name,
                    timerRemainingMs = remainingMs,
                )
            )
        }
    }

    override suspend fun resumeTimer(
        chatId: String,
        messageId: String,
    ): Result<Unit> = resultOf {
        val existing = messageDao.getMessageById(messageId) ?: return@resultOf
        val remaining = existing.timerRemainingMs
            ?: throw IllegalStateException("Cannot resume: timerRemainingMs is null for $messageId")
        val newStartedAtMs = messageSource.resumeTimer(chatId, messageId, remaining)
        messageDao.upsertRecord(
            existing.record.copy(
                timerState = TimerState.RUNNING.name,
                timerDurationMs = remaining,
                timerStartedAtMs = newStartedAtMs,
                timerRemainingMs = null,
            )
        )
    }

    override fun getCallLog(): Flow<List<Message>> =
        messageDao.getCallMessages().map { entities -> entities.map { it.toDomain() } }

    override suspend fun syncAllChatMessages(chatIds: List<String>) {
        val currentUid = authSource.currentUserId ?: return
        if (chatIds.isEmpty()) return

        try {
            signalManager.ensureInitialized()
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            Log.w(TAG, "syncAllChatMessages: Signal init failed — incoming encrypted messages may not decrypt", t)
        }

        val blockedUserIds = try {
            userSource.getBlockedUserIds(currentUid)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Fail open: a transient fetch error must not hide every message,
            // but it means blocked senders may sync until the next pass.
            Log.w(TAG, "syncAllChatMessages: block-list fetch failed — block filtering degraded", e)
            emptySet()
        }

        val semaphore = Semaphore(3)
        coroutineScope {
            chatIds.map { chatId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            syncChatMessages(chatId, currentUid, blockedUserIds)
                        } catch (t: Throwable) {
                            t.rethrowIfCancellation()
                            Log.w(TAG, "syncAllChatMessages: sync failed for chat=$chatId", t)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun syncChatMessages(
        chatId: String,
        currentUid: String,
        blockedUserIds: Set<String>
    ) {
        val rawList = messageSource.fetchMessages(chatId)
        for (raw in rawList) {
            if (raw.senderId != currentUid && raw.senderId in blockedUserIds) {
                Log.d(TAG, "syncChatMessages: filtered blocked sender=${raw.senderId} msg=${raw.id} chat=$chatId")
                continue
            }

            val existing = messageDao.getMessageById(raw.id)

            if (existing != null && existing.deletedAt == null && raw.deletedAt != null) {
                messageDao.softDeleteMessage(raw.id, raw.deletedAt!!)
                continue
            }

            if (existing != null && existing.reactions != raw.reactions) {
                val reactionsJson = JSONObject().apply {
                    raw.reactions.forEach { (k, v) -> put(k, v) }
                }.toString()
                messageDao.updateReactions(raw.id, reactionsJson)
            }

            if (raw.senderId == currentUid) {
                if (existing != null) {
                    acknowledgeOwnEcho(raw, existing)
                    continue
                }
                val content = raw.content ?: "[Sent message]"
                val message = raw.toMessage(content)
                messageDao.upsertRecord(MessageRecord.fromDomain(message))
                continue
            }

            // Incoming messages
            if (existing != null && existing.editedAt == raw.editedAt && existing.deletedAt == raw.deletedAt) continue

            val needsDecryption = raw.ciphertext != null && raw.signalType != null
                    && !(raw.editedAt != null && raw.content != null)
            if (raw.deletedAt == null && !needsDecryption && raw.content == null) continue

            // NonCancellable for the same reason as in reconcileRawMessage: the
            // decrypt advances the ratchet, so a cancellation between it and the
            // insert — withContext discards its result when the job was cancelled
            // while the block ran — would lose a plaintext no second decrypt can
            // recover.
            withContext(NonCancellable) {
                val content = when {
                    raw.deletedAt != null -> ""
                    else -> try {
                        when {
                            raw.editedAt != null && raw.content != null -> raw.content
                            needsDecryption -> signalManager.decrypt(
                                raw.senderId, EncryptedMessage(raw.ciphertext!!, raw.signalType!!)
                            )
                            else -> raw.content!!
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "syncChatMessages: decrypt failed for msg=${raw.id} sender=${raw.senderId} chat=$chatId", t)
                        "[Encrypted message — unable to decrypt]"
                    }
                }

                val message = raw.toMessage(content)
                writeRecord(MessageRecord.fromDomain(message), existing)
            }
        }
    }

    /**
     * An own message's row against the backend's copy of it. Only an acknowledged
     * snapshot may move the status: with client-set ids the SDK echoes this
     * client's own write — the payload's SENT included — before the server has
     * it, to the listener and, merged into a `get()`, to the sync alike. The
     * acknowledged case also heals a row a send left SENDING or FAILED but whose
     * write landed, and `acknowledge` takes it out of the outbox.
     */
    private suspend fun acknowledgeOwnEcho(raw: RawMessage, existing: MessageEntity) {
        if (raw.hasPendingWrites) return
        // Deleted here, not there: a message deleted while queued whose write
        // landed anyway. Its tombstone is still owed (OutboxJob), and healing the
        // row to SENT would take it out of the queue with the message delivered.
        if (existing.deletedAt != null && raw.deletedAt == null) return
        val remoteStatus = parseMessageStatus(raw.status)
        if (existing.status != remoteStatus.name) {
            messageDao.acknowledge(raw.id, remoteStatus.name)
        }
    }

    /** Writes the backend's columns of a message, as an update when [existing] is in hand. */
    private suspend fun writeRecord(record: MessageRecord, existing: MessageEntity?) {
        if (existing == null) messageDao.upsertRecord(record) else messageDao.updateRecord(record)
    }

    /** The backend's copy of a message as a domain [Message], with [content] decrypted or defaulted by the caller. */
    private fun RawMessage.toMessage(content: String) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = parseMessageType(type),
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = mediaThumbnailUrl,
        status = parseMessageStatus(status),
        replyToId = replyToId,
        timestamp = timestamp,
        editedAt = editedAt,
        reactions = reactions,
        isForwarded = isForwarded,
        duration = duration,
        readBy = readBy,
        deliveredTo = deliveredTo,
        pollData = pollData?.let { parsePollFromFirestore(it) },
        mentions = mentions,
        deletedAt = deletedAt,
        emojiSizes = emojiSizes,
        listId = listId,
        listDiff = listDiff?.let { ListDiff.fromMap(it) },
        isPinned = isPinned,
        mediaWidth = mediaWidth,
        mediaHeight = mediaHeight,
        latitude = latitude,
        longitude = longitude,
        isHd = isHd,
        timerDurationMs = timerDurationMs,
        timerStartedAtMs = timerStartedAtMs,
        timerState = timerState?.let { parseTimerState(it) },
        timerRemainingMs = timerRemainingMs,
        timerAlarmStyle = resolveTimerAlarmStyle(timerAlarmStyle, timerSilent),
        timerAlarmSound = resolveTimerAlarmSound(timerAlarmSound),
    )

    private fun downloadPendingMediaForChat(chatId: String) {
        downloadScope.launch {
            try {
                val option = preferencesDataStore.autoDownloadFlow.first()
                if (option == AutoDownloadOption.NEVER) return@launch
                if (option == AutoDownloadOption.WIFI_ONLY && !isOnWifi()) return@launch
                savePendingMediaForChat(chatId)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.w(TAG, "downloadPendingMediaForChat: scan failed for chat=$chatId", e)
            }
        }
    }

    override suspend fun ensureLocalCopiesForChat(chatId: String) {
        // Unconditional counterpart of downloadPendingMediaForChat: no
        // auto-download-preference gate, because the caller (a media browse in
        // in-chat search) is an explicit user view already fetching these files
        // to render them — persisting a local copy just stops the re-download
        // on every re-entry.
        try {
            savePendingMediaForChat(chatId)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            Log.w(TAG, "ensureLocalCopiesForChat: scan failed for chat=$chatId", e)
        }
    }

    /**
     * Download and persist a local copy for every pending media row in [chatId].
     * Preference gating is the caller's responsibility; this helper always
     * downloads. The per-row try/catch keeps one failed download from aborting
     * the rest.
     */
    private suspend fun savePendingMediaForChat(chatId: String) {
        val pending = messageDao.getMessagesWithoutLocalMediaForChat(chatId)
        for (entity in pending) {
            try {
                val url = entity.mediaUrl ?: continue
                val file = mediaFileManager.downloadAndSave(chatId, entity.id, url)
                messageDao.updateLocalUri(entity.id, file.absolutePath)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                Log.w(TAG, "savePendingMediaForChat: download failed for msg=${entity.id} chat=$chatId", e)
            }
        }
    }

    private fun tryAutoDownload(message: Message) {
        downloadScope.launch {
            try {
                val option = preferencesDataStore.autoDownloadFlow.first()
                if (option == AutoDownloadOption.NEVER) return@launch
                if (option == AutoDownloadOption.WIFI_ONLY && !isOnWifi()) return@launch

                val file = mediaFileManager.downloadAndSave(
                    message.chatId, message.id, message.mediaUrl!!
                )
                messageDao.updateLocalUri(message.id, file.absolutePath)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                // Best-effort download; the user can still download manually from the bubble.
                Log.w(TAG, "tryAutoDownload: download failed for msg=${message.id} chat=${message.chatId}", e)
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
