# Send addressing — the repository decides who a send is for

Plan from `docs/plans/send-addressing-brief.md` (read its §0–§2 for the why; this file
supersedes its §3–§6). Grilled with the human and designed twice on 2026-10-03 against
`main` at `c50979e`; line numbers below are from that commit — re-verify before editing.

**Order: 1 → 2 → 3 → 4 → 5 → 6 → 7**

No checkpoint (removed 2026-10-03): the runner never pushes and encryption is opt-in, so the
human reviews step 3's commit (`git show` the hash on its **Shipped** line, plus a
`/code-review ultra` if wanted) before merging `plan/send-addressing`. Step 3 still runs its
mandatory `/code-review` in-session. Steps 4–7 are mechanical once 3 holds.

**Encryption is opt-in today.** A send is encrypted only in a release build of the firebase
flavor *and* with the user's E2E toggle on — `MessageWriter.kt:51` (`buildEncrypts`) and
`:64–68`; the toggle defaults to `false` (`PreferencesDataStore.kt:130–132`). So for most users
a wrong recipient does not mis-encrypt anything today; it does point the **block check** at the
wrong user (a group send refused because of one member you blocked). This plan fixes the
addressing so that both are right now, and so that switching encryption on — per user or by
default — needs no further decision about who a send is for.

## 0. Decisions (signed off 2026-10-03 — do not re-litigate)

| # | Question | Decision |
|---|---|---|
| 1 | Chat row missing (`chatDao.getChatById` → `null`) | **Refuse before any row is written.** Typed `ChatNotReadyException`, banner *"This chat isn't ready yet"*. No plaintext, no FAILED bubble, nothing to retry. No remote fallback, no caller hint. Separately, `getOrCreateChat` also stores a chat it *found* remotely (step 1), which removes the likeliest trigger. |
| 2 | INDIVIDUAL with no other participant | `participants.filter { it != senderId }` must be **exactly one** ⇒ `Peer(it)`. Zero (self-chat, `[uid, uid]`, stale row) or more than one ⇒ refuse with the same exception (one type, a reason string for the log). GROUP / BROADCAST ⇒ `NoPeer`. An unknown `type` string ⇒ refuse. |
| 3 | `recipientId` nav argument | **Survives** for its non-send consumers (`ChatInfoManager`, `ChatTimerReactor`, `ChatMessageActions` reminders, `ChatScreen` profile/call, `setLastOpenChat`). `FCMService` stops putting the sender into the tap intent of a group notification (step 2). |
| 4 | `retryFailedMessage(messageId, recipientId)` | **Parameter dropped.** Retry uses the row's recorded target; a row with none is refused (stays FAILED, nothing requeued) — what `OutboxWorker` already does. |
| 5 | Where the resolution lives | **Exactly one place** derives a `SendTarget` from a chat, once per send, before the row is built. The timer's strict block check uses it too. Forward resolves the *target* chat. |
| 6 | Candidate 2 (partner / display name on domain `Chat`) | **Separate, later plan.** This one first. `Chat.sendRecipientId` loses its send role here; its one remaining caller (the share sheet's post-send navigation) keeps it under the step-5 name. |
| 7 | Tests | Seed a `ChatEntity` through the factory's `ChatDao` and assert the `outboxRecipientId` recorded on the inserted row, on every row path. Timer (no row column): the block check is asked about the resolved peer for INDIVIDUAL, never for GROUP. Refused case: no `insertOutbox` / `upsertRecord`, typed exception. |
| 8 | Broadcast fan-out | **Out of scope, a named non-goal.** `sendBroadcastMessage`'s `recipientIds` stay caller-computed (`ChatInfoManager:136`). Every fan-out is built as `Peer` per member and written directly via `messageWriter.send`, so a wrong list sends to the wrong people — never in plaintext. Different bug class; revisit with candidate 2. |
| 9 | Make the rule executable | **Konsist rule** in `ArchitectureTest`: no `MessageRepository` member has a parameter named `recipientId`, and `Peer(` / `NoPeer` / `SendTarget.of(` appear only in `SendTarget.kt` and in the body of `sendBroadcastMessage` (step 6). Its failure message points at the new PATTERNS entry. |
| 10 | The surviving nav argument's name | **Renamed** to `partnerIdHint` (ViewModel property, the managers' constructor parameters, the route placeholder, `Routes.chat`'s parameter), KDoc *"display/navigation only, `""` for a non-1:1 chat, never an addressing input"*. Done after step 4 (step 5) rather than with the FCM fix, so `ChatMessageSender` — which loses the parameter in step 4 — is not renamed twice. |

Design details settled by the design pass (§2), not up for re-decision either:
`ChatNotReadyException` maps to `AppError.Validation` (like `MediaLimitException`: an
intentional refusal with a human message, shown verbatim); the resolver reads
`ChatEntity.type` as a **string** and never goes through `ChatEntity.toDomain()`, which maps
an unknown type to `INDIVIDUAL` (`ChatEntity.kt:41`); the found-chat write in step 1 goes
through `ChatDao.upsertRemote`, never `insertChat` (REPLACE would wipe `isPinned`,
`isArchived`, `muteUntil` and the local avatar cache).

Not in scope: candidates 6–8 of the 2026-09-20 review (they all touch
`MessageRepositoryImpl` — never run in parallel with this plan); PocketBase beyond compiling
(it has no `MessageRepository` of its own; if its chat sync does not populate Room, its sends
refuse with decision 1's banner — accepted, the flavor is not maintained yet); `sendListMessage`
(takes no recipient today).

## 1. Current state (verified 2026-10-03 at `c50979e`)

The brief's §1 holds; drift and four facts it lacked:

- **Drift.** `MessageRepository.kt` gained `checkMessageAvailability` above the timer:
  `recipientId` sits on `sendMessage` :16, `sendMediaMessage` :26, `retryFailedMessage` :34,
  `forwardMessage` :39, `sendVoiceMessage` :42, `sendLocationMessage` :86, `sendTimerMessage`
  :104–111. `MessageRepositoryImpl.kt`: `chatDao` :155, block family :226–261, `enqueueSend`
  :274–300, `sendMessage` :498–526 (KDoc :488–497), `sendMediaMessage` :574–617,
  `retryFailedMessage` :619–634 (fallback :633), `forwardMessage` :660–683, `sendVoiceMessage`
  :685–708, `sendBroadcastMessage` :850–915, `sendLocationMessage` :1000–1026,
  `sendTimerMessage` :1028– (optimistic `upsertRecord` *before* `failSendOnError {
  ensureNotBlocked(...) }`). `ChatViewModel.recipientId` :123; timer call :667–671.
- **A second live instance of the bug.** `FCMService.kt:236` puts the message's *sender* into
  the tap intent for every chat type. A group notification tap lands on
  `Routes.chat(chatId, senderId)`; `ChatViewModel.recipientId` is then a group member and every
  send from that screen is addressed `Peer(member)`: the block check asks about that member
  (a member you blocked makes every group send fail), and with encryption on (release build +
  E2E toggle) the message is encrypted to that member alone. `MainActivity.deepLinkFromIntent` (:133–138) **requires** the extra to be
  present, so the fix writes `""`, it does not omit it.
- **The timer never encrypts.** `sendTimerMessage` is a direct plaintext write
  (`messageSource.sendTimerMessage`); its recipient only feeds `ensureNotBlocked`.
- **The broadcast fan-out is not row-based** — see decision 8.
- **Row-missing is reachable.** `ChatRepositoryImpl.getOrCreateChat` (:91–116) calls
  `chatDao.insertChat` only when it *creates* the chat; a chat `findIndividualChat` returns is
  handed back with no local row. `ChatRepository.getChatById` is a remote read
  (`chatSource.getChat`), not a local fallback.
- **The test factory trap.** `MessageRepositoryTestFactory` defaults `chatDao` to
  `mockk(relaxed = true)`. A relaxed mock answers `getChatById` with a *mock* `ChatEntity`
  (`type == ""`), not `null` (`docs/GOTCHAS.md`, "MockK `relaxed = true` returns a mock") —
  the resolver would refuse every send in all 17 `MessageRepository*Test` classes.
- **`ChatRepositoryImpl` has no unit test.** Step 1 adds the first.

## 2. Design — option A, resolved inside the repository

Three designs were drawn (2026-10-03, three parallel design agents): **A** resolve inside the
repository; **B** lift `SendTarget` into `domain/` and take it typed; **C** late binding in the
outbox. **A wins.**

- **B** makes the convention *explicit*, not *impossible*. Its best shape (a flat
  `SendTarget(chatId, peerId)` with a private constructor and one `forChat(chat: Chat, uid)`
  factory) still trusts a caller-held `Chat`: a hand-built or stale `Chat(type = INDIVIDUAL,
  participants = [me, groupMember])` addresses a group to one member. And `ChatViewModel`
  holds no `Chat` — `ChatInfoManager` does a remote read and keeps flags — so "row missing"
  comes back as "the caller has no `Chat`" at five call sites, offline included. Making it
  impossible needs the row read, which is A.
- **C** buys delivery of a message composed into a not-yet-synced chat (bind at attempt time,
  one backend read) at the cost of a fourth `outboxRecipientId` state (a sentinel), an outbox
  → chat-data dependency, a bind budget, and FAILED bubbles instead of a banner. Under
  decision 1 no row is ever inserted unbound, so all of that is dead code and C collapses into
  A. Reopen only if refusals from decision 1 show up in practice — the trigger is in §5.

### 2.1 The rule lives in `SendTarget.kt`

No new file and no injected collaborator: `SendTarget.kt`'s job is already "who a send is
for", and a separate `SendAddressing` class would be a one-adapter seam plus a hop.

```kotlin
// data/outbox/SendTarget.kt — companion
/** Who a send into this chat is for. Throws instead of guessing: doubt never becomes NoPeer. */
fun forChat(chatId: String, row: ChatEntity?, senderId: String): SendTarget {
    fun refuse(why: String): Nothing = throw ChatNotReadyException(chatId, why)
    row ?: refuse("no local chat row")
    // Not ChatEntity.toDomain(): it maps an unknown type to INDIVIDUAL.
    return when (ChatType.entries.firstOrNull { it.name == row.type }) {
        ChatType.INDIVIDUAL -> row.participants.filter { it != senderId }.let { others ->
            others.singleOrNull()?.let(::Peer) ?: refuse("INDIVIDUAL with ${others.size} other participants")
        }
        ChatType.GROUP, ChatType.BROADCAST -> NoPeer
        null -> refuse("unknown chat type '${row.type}'")
    }
}

/** From a row's `outboxRecipientId`; `null` when it never recorded one. */
fun fromColumn(column: String?): SendTarget? = when (column) { null -> null; "" -> NoPeer; else -> Peer(column) }
```

`SendTarget.of(String)` is **deleted** — `fromColumn` decodes inline. After this,
`SendTarget.kt` is the only file that turns anything into a `SendTarget` (from a chat row when
a send starts, from the stored column when the outbox drains it), plus the broadcast fan-out
(decision 8). `ChatEntity` is a data-layer type, so the rule stays in `data/`; candidate 2 can
move it onto domain `Chat` later.

```kotlin
// domain/model/AppError.kt, next to RecipientBlockedException
class ChatNotReadyException(val chatId: String, val reason: String) : Exception("This chat isn't ready yet") {
    override fun toString() = "ChatNotReadyException(chat=$chatId): $reason"  // the reason is for the log only
}
// AppError.from
is ChatNotReadyException -> Validation(throwable.message ?: "This chat isn't ready yet")
```

`SendErrorClassifier` is untouched: the worker never sees this exception (it reads the row's
recorded target).

### 2.2 One read per send, before the row

```kotlin
// MessageRepositoryImpl
private suspend fun sendTargetFor(chatId: String, senderId: String): SendTarget =
    SendTarget.forChat(chatId, chatDao.getChatById(chatId), senderId)

/** Text, media, voice and location: resolve, record the target on the row, queue. */
private suspend fun queueSend(message: Message, mimeType: String? = null): Message {
    val row = MessageEntity.outbox(message, sendTargetFor(message.chatId, message.senderId))
    messageDao.insertOutbox(row)
    return enqueueSend(row, mimeType)
}
```

| Path | Change |
|---|---|
| `sendMessage`, `sendMediaMessage`, `sendVoiceMessage`, `sendLocationMessage` | Their last three lines (`MessageEntity.outbox(…, SendTarget.of(recipientId))` → `insertOutbox` → `enqueueSend`) become `queueSend(optimistic[, mimeType])`. Media keeps its video-limit guard first. |
| `forwardMessage` | `sendTargetFor(targetChatId, senderId)` replaces `SendTarget.of(recipientId)` (:665); `refuseIfBlocked` still runs before the insert, `enqueueSend(row, blockTarget = null)` unchanged. |
| `sendTimerMessage` | `val target = sendTargetFor(chatId, senderId)` moves **before** `upsertRecord`, so a refused timer leaves no row. Inside `failSendOnError`, `ensureNotBlocked(senderId, target: SendTarget)` — the strict rule, now typed. |
| `retryFailedMessage` | Reads no chat. `val target = entity.sendTarget ?: throw IllegalStateException("no recorded target")` runs **before** `requeueForRetry`, so a refused row stays FAILED; then `enqueueSend(queued, blockTarget = target, retry = true)`. |
| `sendBroadcastMessage` | Unchanged (decision 8). |

The resolution runs outside `enqueueSend`'s `NonCancellable` phase and before any insert: a
cancellation during the read leaves nothing behind, which is correct. `enqueueSend` keeps its
shape. Cost: one indexed Room primary-key read per send, uncached on purpose (a cache would
bring staleness back).

### 2.3 Scores against the brief's §4 criteria

1. A group message cannot be encrypted to one member: GROUP/BROADCAST always resolve to
   `NoPeer`, and nothing outside `SendTarget.kt` builds a target (step 6 makes that a test).
2. INDIVIDUAL with zero or several others, a missing row, an unknown type: refused before any
   row, never plaintext.
3. Block check: queued sends ask the resolved peer in `enqueueSend` (forward before the
   insert); the timer asks strictly inside `failSendOnError`; a group never asks.
4. `MessageEntity.outbox` unchanged; the target is still recorded at insert and the outbox
   reads only the row.
5. Test surface: seed a chat, assert `outboxRecipientId`. A block-test case reads as the
   invariant.
6. Hops for "why is it plaintext": 8 → 3 (`MessageRepositoryImpl` → `SendTarget` →
   `MessageWriter`). "Send a photo to a group" stays ~15.
7. PocketBase: `MessageWriter`'s gate stays the one plaintext/encrypt switch.
8. Cost: one local read per send.

## 3. Steps

### Step 1 — `getOrCreateChat` stores a chat it found

**Why.** Removes the likeliest trigger of decision 1's refusal: Contacts → start chat with
someone you already have a 1:1 with, on an install whose chat-list sync has not landed.

- `ChatRepositoryImpl.getOrCreateChat` (:91–116): when `chatSource.findIndividualChat` returns
  a chat, write it with `chatDao.upsertRemote(listOf(ChatEntity.fromDomain(existing)))` before
  returning it. **Not** `insertChat` — see §0 design details; `upsertRemote` keeps the local-only
  fields and the newer local preview (`ChatDao.kt:28–67`).
- Tests — new `data/repository/ChatRepositoryImplGetOrCreateTest`: a found chat is upserted via
  `upsertRemote` (never `insertChat`); a created chat is still inserted as today; the returned
  `Chat` is unchanged in both cases. Build `ChatRepositoryImpl` with MockK for its constructor
  dependencies (it has no test factory).
- Commit `fix(chat): keep a chat found on the backend in the local store`. CHANGELOG: no entry of
  its own — its user-visible point is step 3's; step 3's *Fixed* entry carries this hash too.
  Untagged → no `skills:` floor.

**Approach**
- Code as it stands matches the spec: `getOrCreateChat` is at `ChatRepositoryImpl.kt:92–117`, the
  found branch returns `existing` with no local write, and `ChatDao.upsertRemote` (:47–68) is the
  merge the spec names. Nothing contradicts §0 or §2.
- Order: the regression test first, run alone and seen failing on the found-chat case; then the
  one-line write in `ChatRepositoryImpl`; then the full gate.
- Test: new `data/repository/ChatRepositoryImplGetOrCreateTest`, MockK for the six constructor
  dependencies. Found chat ⇒ `upsertRemote(listOf(ChatEntity.fromDomain(existing)))`, never
  `insertChat`, no `createChat`, the same `Chat` returned. No chat found ⇒ `createChat` +
  `insertChat` as today, never `upsertRemote`, the returned `Chat` carries the new id.
- Touches one production file under `data/repository/`, so no further skills: the diff is a single
  local write with no concurrency or crypto in it, and neither tripwire applies.

**Shipped** `d63ae9b1` (2026-10-03) — tier: mid. skills: none. Reviewer models: none.
Departures (for sign-off): none

### Step 2 — A group notification no longer names a member as the chat partner

- `FCMService.showNotification` (:190–), the tap intent at :234–241: write
  `EXTRA_SENDER_ID` as `""` when `isGroup`, the sender otherwise. Extract the choice as a pure
  `internal fun` (e.g. `notificationPartnerHint(isGroup: Boolean, senderId: String): String`) in
  `data/remote/fcm/` so it is testable without a `Service`; the intent keeps the extra present
  (`MainActivity.deepLinkFromIntent` requires it). `isGroup` is `chatType == GROUP` at both
  callers (:127, :170); broadcast messages arrive in 1:1 chats, so INDIVIDUAL vs GROUP is the
  whole distinction here.
- Tests: the pure function (group → `""`, individual → sender), and a
  `ChatListPendingActionTest` case that an `""` sender still yields `OpenChat` with
  `recipientId = ""` (the route already accepts an empty segment — `NavGraph.kt:611/623` pass `""`).
- Commit `fix(notifications): open a group from its notification without a 1:1 partner`.
  CHANGELOG *Fixed* (patch bump per the `changelog-release` skill): tapping a group
  notification opened the group addressed to the message's sender, so sends from that screen
  were refused when you had blocked that member, and — with end-to-end encryption switched on —
  were encrypted for that one member only.

**Approach**
- Code as it stands matches the spec: `FCMService.showNotification` is at :190–257 and writes the
  sender into `EXTRA_SENDER_ID` at :236 for every chat type. Both callers (:127, :165–173) pass
  `isGroup = chatType == ChatType.GROUP.name`. Nothing contradicts §0 or §2.
- Order: the two tests first, seen failing (the pure function does not exist yet, so the test
  source set does not compile); then the function and the one-line change at :236; then the gate.
- `notificationPartnerHint(isGroup, senderId)` goes into `FCMService.kt` as a top-level
  `internal fun`, so no new production file and no FEATURE-MAP change.
- Tests: new `data/remote/fcm/NotificationPartnerHintTest` (group → `""`, individual → sender);
  one `ChatListPendingActionTest` case (`pendingSenderId = ""` ⇒ `OpenChat(recipientId = "")`).
- CHANGELOG: a *Fixed* entry appended to the open `[UNRELEASED] [1.35.4]` section, date moved to
  today; the section is already a patch, so the version stays.
- Further skills: none. One production file under `data/remote/fcm/`, no concurrency or crypto in
  the diff, and neither tripwire applies.

**Shipped** `7abdf337` (2026-10-03) — tier: mid. skills: changelog-release. Reviewer models: none.
CHANGELOG: *Fixed* entry in `[UNRELEASED] [1.35.4]`, hash `7abdf337` (added in the `docs(plan):` commit); the section was already a patch, so no version change.
Departures (for sign-off): none

### Step 3 — The repository decides who a send is for; skills: code-review; model: max

The step that decides who every send is addressed to — the block check's peer always, and the
Signal session whenever encryption is on (see the note under **Order**). §2 is the spec. **The regression tests come first and must be seen
failing** against the current code, inside this step (a red commit is not allowed — write,
watch fail, implement, green, one commit).

1. **Red.** In `MessageRepositoryForwardTest`: forward into a seeded GROUP chat while passing
   a member id as the (still existing) recipient argument ⇒ assert the inserted row's
   `outboxRecipientId == ""`. Today it records the member — that is the 2026-09-18 bug class.
   Same for `sendMessage` into a GROUP with a member id (`MessageRepositoryBlockTest`). Run
   them; record that they fail.
2. **Test factory.** `MessageRepositoryTestFactory` gains `chats: List<ChatEntity>` (declared
   before `chatDao`), and the default `chatDao` answers `getChatById` from it (`null` when
   absent). Default `chats` = one INDIVIDUAL `"chat1"` between `"uid1"` and `"recipient1"` — the
   ids the existing classes already use — so untouched tests keep their meaning; tests about
   addressing pass `chats` explicitly. Add a `testChat(id, type, participants)` fixture beside the
   factory. Classes that send into another chat id seed it.
3. **Implement** §2.1–§2.2: `SendTarget.forChat`, `fromColumn` inline, delete `SendTarget.of`
   (its one test use, `OutboxSenderTest`, becomes `SendTarget.Peer(`); `ChatNotReadyException`
   + its `AppError.from` arm; `sendTargetFor` + `queueSend`; forward, timer, retry as in the
   §2.2 table. The `recipientId` parameters **stay on the signatures and are ignored** in this
   step (step 4 removes them mechanically) — keep the diff to behaviour so the review sees only
   behaviour. Retry's `recipientId` likewise ignored.
4. **Green, and the rest of the test surface:**
   - new `data/outbox/SendTargetTest`, table-driven over `forChat`: null row; INDIVIDUAL with
     0, 1, 2 others; `[uid, uid]`; GROUP; BROADCAST; unknown type `"CHANNEL"`; and `fromColumn`
     (`null`, `""`, id).
   - `MessageRepositoryBlockTest`: replace the `""`-means-group case (:202) with a seeded GROUP
     chat; for text, media, voice, location, forward assert the recorded `outboxRecipientId`
     (`Peer` id for INDIVIDUAL, `""` for GROUP). Unresolvable chat ⇒ `ChatNotReadyException`,
     `insertOutbox` never called.
   - `MessageRepositoryTimerTest`: GROUP ⇒ the block check is never asked and the timer is
     sent; INDIVIDUAL ⇒ asked about the resolved peer; refused chat ⇒ no `upsertRecord`.
   - `MessageRepositoryRetryTest`: a FAILED row with `outboxRecipientId == null` ⇒ refused,
     `requeueForRetry` never called, row stays FAILED; a row with a recorded target retries with
     that target whatever the argument says.
   - `AppError.from(ChatNotReadyException(...))` is `Validation("This chat isn't ready yet")`.
5. **Docs inside the code:** rewrite the `@param recipientId` KDoc at
   `MessageRepositoryImpl.kt:488–497` (the convention it documents is gone — say the parameter
   is ignored until step 4 removes it, and where the target comes from); rewrite
   `SendTarget.kt`'s AGENT-NOTE and class KDoc ("the UI's recipient id enters through `of`" is
   false now).
6. `/code-review` is mandatory (it decides the block check's peer and, with encryption on, the
   Signal session). Commit `fix(send): the repository decides who a send is for`. CHANGELOG
   *Fixed*, patch bump: who a message is addressed to now comes from the chat itself, so no
   screen can address a group message to one member — today that means a group send is never
   refused because of one member's block; once end-to-end encryption is switched on, it means a
   group message is never encrypted for one member only. A chat that is not in the local store
   yet refuses with *"This chat isn't ready yet"* instead of guessing. Say in the entry that
   encryption itself stays opt-in. Hashes: this commit and step 1's.
   **(step-1)** Step 1's hash is `d63ae9b1`. It has no CHANGELOG entry and no version bump of
   its own, so this entry is the only place it is recorded.

**Approach**
- Code as it stands matches §2: the seven `SendTarget.of(recipientId)` sites are at
  `MessageRepositoryImpl.kt` :241 (timer, via `ensureNotBlocked`), :523, :614, :633 (retry
  fallback), :665, :705, :1023; `chatDao` is injected. Nothing contradicts §0 or §2.
- Order: (1) the factory's `chats` + `testChat`, then the two red tests (forward and `sendMessage`
  into a seeded GROUP with a member id), run and seen failing; (2) `SendTarget.forChat` /
  `fromColumn`, `ChatNotReadyException` + its `AppError.from` arm; (3) `sendTargetFor` +
  `queueSend`, forward, timer, retry in `MessageRepositoryImpl`, KDoc and AGENT-NOTE rewrites;
  (4) the rest of the test surface; (5) gate, `/code-review`, CHANGELOG.
- Tests: new `data/outbox/SendTargetTest`; `MessageRepositoryBlockTest` (recorded target per row
  path for INDIVIDUAL and GROUP, unresolvable chat refused with no insert);
  `MessageRepositoryTimerTest` (GROUP never asks, INDIVIDUAL asks the resolved peer, refused chat
  writes no row); `MessageRepositoryRetryTest` (no recorded target ⇒ refused before
  `requeueForRetry`); `AppErrorTest`.
- Three places where the spec's wording does not fit the code, none touching §0 or §2:
  `OutboxSenderTest.store` defaults its recipient to `""`, so `SendTarget.of` there cannot become a
  bare `Peer(` — the helper takes a `SendTarget` (default `NoPeer`). `MessageRepositoryRetryTest`
  has a case pinning the fallback decision 4 removes; it is replaced by the refusal case.
  `MessageRepositoryMediaSendFailureTest` asserts `outboxRecipientId == ""` for `"chat1"`, which is
  the default INDIVIDUAL chat now, so it asserts the resolved peer.
- Further skills: `changelog-release` (a user-visible *Fixed* entry, bump decision). `simplify`
  is intended because the diff is security-adjacent; re-decided against the real diff.

**Shipped** `f411a330` (2026-10-03) — tier: max. skills: code-review, simplify, changelog-release. Reviewer models: code-review: opus, opus; simplify: sonnet, sonnet, sonnet, sonnet.
CHANGELOG: *Fixed* entry in `[UNRELEASED] [1.35.4]`, hashes `f411a330` and step 1's `d63ae9b1` (this commit's hash added in the `docs(plan):` commit); the section was already a patch, so no version change.
Red run, before the implementation: `MessageRepositoryForwardTest` forward into a seeded GROUP failed with `expected:<[]> but was:<[member1]>`; `MessageRepositoryBlockTest` `sendMessage` into a seeded GROUP failed with `RecipientBlockedException`. That second case now lives in the every-row-path group test.
Gate: `:app:testFirebaseDebugUnitTest`, `:app:testPocketbaseDebugUnitTest` and `assembleDebug` (both flavors) green.
Departures (for sign-off):
- **(/code-review)** `SendTarget.forChat` also refuses a 1:1 chat whose one other participant has a blank id. `Peer("")` is stored as `""`, which `fromColumn` reads back as `NoPeer`: plaintext and no block check. §2.1's snippet had that gap. The test was seen failing before the fix.
- `sendTargetFor` logs the refusal (`Log.w`) and rethrows; §2.2 shows a one-liner. Without it decision 2's "reason string for the log" reaches no log.
- Retry is `checkNotNull(entity.sendTarget)` then `enqueueSend(queued, retry = true)`; §2.2 writes `blockTarget = target`. Same value, since the default is the row's recorded target (/simplify).
- The three wording mismatches named in **Approach**: `OutboxSenderTest.store` takes a `SendTarget`; the retry fallback case became the refusal case; `MessageRepositoryMediaSendFailureTest` asserts the resolved peer.
- `testChat`'s `type` is a `String`, so a test can seed a type the app does not know. It is built over `TestData.chat`.
- /simplify findings not applied: resolving the target first in the four `queueSend` paths (§2.2 fixes the order, "Media keeps its video-limit guard first" — a refused chat with an over-limit video reports the video limit); calling `BootRestoreLogic.resolveOtherUserId` from `forChat` (decision 6); dropping the forward group case (item 1 names it).
- Three `TECH_DEBT.md` entries for findings outside the plan: *An unsent message keeps the target it recorded, even a wrong one*; *An unknown chat type from the backend is stored as a 1:1 chat*; *A failed timer shows a retry button that cannot retry it*. The first is the one to read before merging: a row left `SENDING` or `FAILED` by an older build is not re-addressed.
- `docs/agents/issue-tracker.md` does not exist; the `code-review` skill asks for `/setup-matt-pocock-skills` in that case. The spec was passed by path, so neither review axis was skipped.

### Step 4 — Drop `recipientId` from the seven send members; skills: code-review

Mechanical; behaviour already lives in step 3.

- `MessageRepository.kt`: remove `recipientId` from `sendMessage`, `sendMediaMessage`,
  `forwardMessage`, `sendVoiceMessage`, `sendLocationMessage`, `sendTimerMessage`;
  `retryFailedMessage(messageId)`. `MessageRepositoryImpl` overrides follow; drop the step-3
  "ignored" KDoc.
- Callers: `ChatMessageSender` (:83, :111–118, :133, :154, :170 — and its `recipientId`
  constructor parameter, :20, and the argument at `ChatViewModel.kt:188`); `ChatViewModel` timer
  (:667–671); `ChatMessageActions.forwardMessage` (:103–107 — `forwardMessage(message, chat.id)`);
  `SharePickerViewModel.sendToChat` (:193–) and its call at :156 (`sendToChat(chat.id, content)`).
  `Chat.sendRecipientId`'s one remaining caller is then `SharePickerViewModel:167` (navigation) —
  leave it for step 5.
- Tests: every `MessageRepository*Test` call site; `FakeMessageRepository` loses
  `lastSentRecipientId`, `SentMedia.recipientId`, and `forwardedTargets` becomes a list of chat
  ids, `retryCalls` a list of message ids; `ChatMessageSenderOfflineTest` (passes `"peer-1"`
  positionally) and every ViewModel/manager test asserting on those fields.
- `refactor(send): drop recipientId from the send API` — no CHANGELOG entry, no bump.
  Two ViewModels change, so the tripwire asks for `code-review` regardless.
- **(step-3)** The "ignored" KDoc sits in three places in `MessageRepositoryImpl`: the
  `@param recipientId` of `sendMessage`, the `@param recipientId` of `sendMediaMessage`, and the
  last sentence of `forwardMessage`'s KDoc. `sendMessage`'s paragraph on where the target comes
  from stays.
- **(step-3)** Tests that pass a contradicting recipient on purpose lose that argument and the
  "whatever recipient the caller names" clause of their names, and keep their assertions:
  `MessageRepositoryBlockTest.rowPaths(chatId, recipientId)` and its three every-row-path cases,
  two cases in `MessageRepositoryTimerTest`, one in `MessageRepositoryForwardTest`, two in
  `MessageRepositoryRetryTest`. The factory's `chats` / `testChat` stay as they are.
- **(step-3)** Retry no longer passes a `blockTarget`: `retryFailedMessage` is
  `checkNotNull(entity.sendTarget)` then `enqueueSend(queued, retry = true)`. Dropping the
  parameter touches the signature only.

**Approach**
- Code as it stands matches the spec. The parameter sits on seven `MessageRepository` members and
  is read by none of the `MessageRepositoryImpl` overrides. Nothing contradicts §0 or §2.
- Order: (1) `MessageRepository.kt` and the `MessageRepositoryImpl` overrides, with the three
  "ignored" KDoc passages; (2) callers: `ChatMessageSender` (and its constructor parameter, with
  the argument in `ChatViewModel`), the `ChatViewModel` timer call, `ChatMessageActions.forwardMessage`
  (its KDoc names `sendRecipientId` as the addressing rule, which is no longer true),
  `SharePickerViewModel.sendToChat`; (3) `FakeMessageRepository`; (4) the test call sites the
  compiler names; (5) gate, `/code-review`.
- Tests: no new ones, the step changes no behaviour. The cases that passed a contradicting
  recipient lose the argument and the clause in their names, and keep their assertions.
  `ChatMessageSenderOfflineTest`, `SharePickerViewModelTest` and `ChatMessageActionsForwardTest`
  assert on fake fields that go away; each assertion is restated on what the fake still records.
- Further skills: none. The diff is a signature change the compiler checks end to end.

**Shipped** `58de0f05` (2026-10-03) — tier: mid. skills: code-review. Reviewer models: code-review: sonnet, sonnet.
Gate: `:app:testFirebaseDebugUnitTest` and `assembleFirebaseDebug` green. The pocketbase flavor was not built; it has no `MessageRepository` implementation or caller of its own.
Departures (for sign-off):
- The compiler does not check this change end to end, as **Approach** claimed. Every member has a `String` parameter behind the removed one, so a positional call such as `sendMessage(chatId, text, "recipient1")` still compiles and binds the old recipient to `replyToId` (`caption` on `sendMediaMessage`). Eight test calls had that shape. All were fixed by hand, and the /code-review standards pass re-checked every call site of the seven members and found none left.
- Three assertions on fake fields that no longer exist were restated, not dropped: `ChatMessageSenderOfflineTest` asserts the sent message's chat id; `ChatMessageActionsForwardTest` asserts the forwarded chat ids for a 1:1 and a group; `SharePickerViewModelTest`'s group case asserts the chat id sent to and that `onDone` hands back `""` as the partner.
- The negative checks on the contradicting recipient (`isUserBlocked(any(), "someone-else")`, `"peer-from-screen"`) went with the argument. The `NoPeer` retry case now stubs every block check as blocked and still asserts none is asked.
- `ChatMessageActions.forwardMessage`'s KDoc named `sendRecipientId` as the addressing rule and linked the picker pattern. It now says the repository decides; the unused import went too.
- /code-review findings not acted on here, because later steps own them: stale `sendRecipientId` wording in `docs/PATTERNS.md`, `docs/FEATURE-MAP.md` and `CLAUDE.md` (steps 6 and 7), and the `onDone` parameter name (step 5). Both are annotated below.
- `docs/agents/issue-tracker.md` does not exist; the `code-review` skill asks for `/setup-matt-pocock-skills` in that case. The spec was passed by path, so neither review axis was skipped.

### Step 5 — The surviving nav argument says what it is: `partnerIdHint`

- `ChatViewModel.recipientId` (:123) → `partnerIdHint`, with the §0-10 KDoc; the constructor
  parameters of `ChatInfoManager` (:39), `ChatMessageActions` (:24), `ChatTimerReactor` (:34)
  and their call sites in `ChatViewModel` (:180–204); `ChatScreen`'s reads (:949, :1006, :1410,
  :1685); `setLastOpenChat` (:236) keeps its stored meaning.
- `navigation/NavGraph.kt`: the `CHAT` route placeholder `{recipientId}` → `{partnerIdHint}`
  (:126, the `navArgument` at :469, the read at :489) and `Routes.chat`'s parameter (:151). The
  twelve producers' lambda parameter names may stay (candidate 2 collapses them).
- `ui/components/ChatTargets.kt`: `Chat.sendRecipientId` → `Chat.partnerIdHint(currentUserId)`,
  KDoc rewritten (navigation only; sends resolve their own target); `SharePickerViewModel:167`
  and `ChatPickerTargetsTest`'s cases follow (keep the INDIVIDUAL / GROUP / BROADCAST cases —
  they still pin the navigation hint).
- Re-verify `ChatInfoManager` / `ChatTimerReactor` / `ChatMessageActions` treat `""` as
  "no partner" everywhere they read it (they did at `c50979e`: `isNotBlank` / `takeIf { it.isNotEmpty() }`).
- **(step-2)** `notificationPartnerHint` (bottom of `FCMService.kt`) already carries the new name.
  The intent extra `MainActivity.EXTRA_SENDER_ID`, `DeepLinkRequest.senderId` and
  `ChatListPendingAction.OpenChat.recipientId` still carry the old ones; they feed `Routes.chat`,
  so decide here whether they follow the rename. The other two writers of the extra are
  `ReminderNotificationPoster` (:66, the reminder's stored `recipientId`, which is whatever the
  chat screen held when the reminder was set) and `TimerAlarmReceiver` (:257). Step 2 did not
  touch them: after step 3 neither can address a send.
- **(step-4)** `Chat.sendRecipientId` has three callers left, not one: `SharePickerViewModel.kt:166`
  and `ChatListScreen.kt:238` and `:259`, all navigation. The header comment of `ChatTargets.kt`
  (:10) names the chat list as a caller. `ChatMessageActions` no longer imports it.
- **(step-4 /code-review)** `SharePickerViewModel.send`'s callback is
  `onDone: (singleChatId: String?, recipientId: String?)` (:141). Its second argument is the
  navigation hint, so it follows the rename.
- **(step-4)** `ChatMessageSender` no longer takes the argument, so the `ChatViewModel` call sites to
  rename are `ChatMessageActions`, `ChatInfoManager`, `ChatTimerReactor` and `setLastOpenChat`. The
  test builders that pass `recipientId =` by name follow: `ChatMessageActionsForwardTest`,
  `ChatMessageActionsEditTest`, `ChatInfoManagerTest`, `ChatInfoManagerRecentEmojiTest`,
  `ChatTimerReactorTest`.
- `refactor(chat): name the chat route's partner argument for what it is` — no CHANGELOG entry.

**Approach**
- Code as it stands matches the spec and the step-2 and step-4 annotations. `ChatViewModel.recipientId`
  is at :123, the three managers take it as a constructor parameter, and `Chat.sendRecipientId` has
  three callers, all navigation. Nothing contradicts §0 or §2.
- Order: (1) `NavGraph.kt` (route placeholder, `navArgument`, the read, `Routes.chat`'s parameter);
  (2) `ChatViewModel` with the decision-10 KDoc, then `ChatInfoManager`, `ChatMessageActions`,
  `ChatTimerReactor` and `ChatScreen`'s four reads; (3) `ChatTargets.kt`, `ChatListScreen`,
  `SharePickerViewModel.send`'s `onDone` and `SharePickerScreen`; (4) tests; (5) gate.
- The step-2 question: `ChatListPendingAction.OpenChat.recipientId` follows the rename, because it is
  handed straight to `Routes.chat`. `MainActivity.EXTRA_SENDER_ID` and `DeepLinkRequest.senderId`
  keep their names: they name what the intent carries, and `notificationPartnerHint` is the one
  place that decides what goes into it.
- `Reminder.recipientId`, `setLastOpenChat`'s parameter and the producers' lambda parameter names
  stay, as the spec says.
- Tests: no new ones, the step changes no behaviour. The six `ChatViewModel*Test` classes build a
  `SavedStateHandle` with the key `"recipientId"`, which the spec does not list. They follow the
  route placeholder, or `checkNotNull` throws.
- `CLAUDE.md`'s Navigation example and the `CHAT` row in `docs/ARCHITECTURE.md` name the parameter,
  so they follow in the same commit.
- Further skills: `code-review`. `ChatViewModel` and `SharePickerViewModel` both change, so the
  tripwire asks for it. It runs after the gate.

**Shipped** `8ea45884` (2026-10-03) — tier: mid. skills: app-ui-design, code-review. Reviewer models: code-review: sonnet, sonnet.
Gate: `:app:testFirebaseDebugUnitTest` and `assembleFirebaseDebug` green. The pocketbase flavor was not built.
Departures (for sign-off):
- The step-2 question is decided as **Approach** says. `ChatListPendingAction.OpenChat.recipientId` is `partnerIdHint`. `MainActivity.EXTRA_SENDER_ID` and `DeepLinkRequest.senderId` keep their names.
- The six `ChatViewModel*Test` classes build their `SavedStateHandle` with the key `"partnerIdHint"`. The spec did not list them.
- `SharePickerScreen`'s `onDone` parameter follows `SharePickerViewModel.send`'s. `CLAUDE.md`'s Navigation example and the `CHAT` row of `docs/ARCHITECTURE.md` follow `Routes.chat`'s parameter.
- `Reminder.recipientId`, `GlobalSearchUiState.recipientIdFor`, `setLastOpenChat`'s parameter and the route producers' lambda parameter names keep the old word. /code-review named the split vocabulary as a judgement call. The spec leaves them to candidate 2.
- /code-review findings not acted on here, because later steps own them: `sendRecipientId` in `CLAUDE.md:189`, `docs/PATTERNS.md:194` and `:203` (step 6) and `docs/FEATURE-MAP.md:544–554` (step 7). Both are annotated below.
- The local `val partnerIdHint` in the `CHAT` nav entry (`NavGraph.kt:489`) is never read. It was unused under the old name too, and it stays.
- `docs/agents/issue-tracker.md` does not exist; the `code-review` skill asks for `/setup-matt-pocock-skills` in that case. The spec was passed by path, so neither review axis was skipped.

### Step 6 — Make the rule a test, and write it down

- `ArchitectureTest` gains two rules (shape from the design pass):
  ```kotlin
  private val TARGET_BUILD = Regex("""\bPeer\(|\bNoPeer\b|\bSendTarget\.of\(""")
  private fun String.code() = replace(Regex("""//[^\n]*|/\*[\s\S]*?\*/"""), "")   // KDoc links don't count

  @Test fun `no MessageRepository member takes a caller-computed recipientId`() { … assertFalse on parameter names;
      assert exactly one MessageRepository interface was found so the rule cannot pass vacuously }
  @Test fun `a SendTarget is built only from a chat row or a stored column`() { … every production file except
      data/outbox/SendTarget.kt has no TARGET_BUILD match in its code, except inside the body of
      MessageRepositoryImpl.sendBroadcastMessage (decision 8 — allowlisted per function, not per file,
      so the seven send paths stay covered) }
  ```
  Re-verify the token census first (at `c50979e`: `SendTarget.kt`, the broadcast fan-out at
  `MessageRepositoryImpl.kt:902`, and a KDoc mention in `MessageWriter.kt:57`). Readers of
  `peerId` / `sendTarget` (`MessageWriter`, `OutboxSender`, `OutboxWorker`) are fine — the rule is
  on construction. Each assertion's message names `docs/PATTERNS.md#the-repository-decides-who-a-send-is-for`.
  Prove each rule bites: temporarily add a violation, see it fail, remove it.
- `docs/PATTERNS.md`: new entry **The repository decides who a send is for** — definition (a send
  takes a chat id; `SendTarget.forChat` over the Room row; refuse on doubt), the trap (a caller
  computing a recipient — the 2026-09-18 forward bug, the 2026-10-03 group-notification one), the
  executable fence (the two rules), when not to use (broadcast fan-out, decision 8). Edit **One
  chat picker, three hosts**: `sendRecipientId` is gone — the picker hands over chats, the
  repository addresses them; `partnerIdHint` is navigation-only.
- `CLAUDE.md` Key Conventions: add the one-line pointer for the new entry; fix the *One chat
  picker* line ("a send is addressed to a recipient only in a 1:1 (`sendRecipientId`)" is no
  longer how it works).
- `test(architecture): fence send addressing inside SendTarget` — test + docs, no CHANGELOG.
- **(step-3)** Token census after step 3: `SendTarget.of` no longer exists, so its alternative in
  `TARGET_BUILD` only guards against the name coming back. `Peer(` / `NoPeer` appear in
  `SendTarget.kt`, in `sendBroadcastMessage` (`MessageRepositoryImpl.kt:930`) and in a KDoc link in
  `MessageWriter.kt:57`. The two factories are `SendTarget.forChat(`, called only from
  `MessageRepositoryImpl.sendTargetFor`, and `SendTarget.fromColumn(`, called only from
  `MessageEntity.sendTarget`. Decision 5 ("exactly one place") is worth a third assertion: `forChat(`
  has one caller.
- **(step-3 /code-review)** The PATTERNS entry's "refuse on doubt" list has one more case than
  decision 2: a 1:1 chat whose one other participant has a blank id. The column stores `NoPeer` as
  `""`, so a blank peer id would be read back as nobody.
- **(step-3)** `docs/PATTERNS.md:203` (in *One chat picker, three hosts*) still explains the trap
  through `SendTarget.of(recipientId)`, which is deleted.
- **(step-4)** The first rule can be fooled by nothing now, but the PATTERNS entry should name the
  trap it does not catch: a positional `String` argument left behind when a parameter is removed
  still compiles and binds to the next `String` parameter (`replyToId`, `caption`). Eight test calls
  had that shape in step 4.
- **(step-4 /code-review)** `docs/PATTERNS.md:194` also still lists `Chat.sendRecipientId` among the
  panel's rules as an addressing rule. No caller addresses a send with it any more.
- **(step-5)** The helper is `Chat.partnerIdHint(currentUserId)` in `ui/components/ChatTargets.kt`.
  Its callers are `ChatListScreen` (twice) and `SharePickerViewModel.send`, all navigation.
  `CLAUDE.md:189` (the *One chat picker* line) is the only place in `CLAUDE.md` that still says
  `sendRecipientId`. The Navigation example already reads `Routes.chat(chatId, partnerIdHint)`.

### Step 7 — Docs that move with the code

- `TECH_DEBT.md`: delete *"The 'who is this send addressed to' rule is enforced by a UI
  helper, not by the send API"* (:386–); in *"Who is the other participant…"* (:396–) rewrite the
  *Partly resolved* paragraph (:402) — the "deeper fix is the repository API" reason is done, the
  remaining hand-rolled partner expressions are candidate 2's. Add an entry for decision 8 (the
  broadcast list) with its trigger: candidate 2, or any second caller of `sendBroadcastMessage`.
- `docs/FEATURE-MAP.md` (:544–554): `sendRecipientId` is no longer the panel's load-bearing rule;
  `ChatMessageActions` forwards by chat id; `SendTarget.kt` is where addressing lives.
- `docs/BACKLOG.md` § *Pending on-device verification*: release build **with the E2E toggle
  on** — (a) tap a group
  notification, send, a second member can read it; (b) forward into a group from a release build,
  every member can read it; (c) fresh install, open a chat from Contacts before the list syncs —
  the send goes through (step 1) or refuses with the banner, never as plaintext to a 1:1.
- `docs/plans/send-addressing-brief.md`: one line under its title — superseded by this plan.
- `docs(send): addressing lives in the repository` — docs only, no gate run needed.
- **(step-3)** `TECH_DEBT.md` gained three entries after *"Who is the other participant…"*: *An
  unsent message keeps the target it recorded, even a wrong one*, *An unknown chat type from the
  backend is stored as a 1:1 chat*, *A failed timer shows a retry button that cannot retry it*.
  Keep them. The line numbers above (:386, :396, :402) are unchanged.
- **(step-3)** `docs/ARCHITECTURE.md:409` describes `SendTarget.kt` as "Peer / NoPeer, the
  outboxRecipientId column in one place". It also holds the rule that turns a chat row into a
  target now (`forChat`), as `docs/FEATURE-MAP.md:278` should say too.
- **(step-4 /code-review)** `docs/FEATURE-MAP.md:554` says `ChatMessageActions` addresses each
  forward by `sendRecipientId`. Since step 4 it passes the chat id only.
- **(step-3)** One more item for the on-device list: (d) retry a message that failed in a 1:1 chat —
  it goes to the same person; a failed timer's retry shows a banner and stays failed (see the
  `TECH_DEBT.md` entry).
- **(step-5 /code-review)** `docs/FEATURE-MAP.md` names `sendRecipientId` at :544, :552 and :554.
  The function is `Chat.partnerIdHint` now and is navigation only. `TECH_DEBT.md` names the old
  helper too, so check the entries this step rewrites for it.

## 4. Gates

Every step: `./gradlew :app:testFirebaseDebugUnitTest` and `./gradlew assembleFirebaseDebug`
green before its commit (cloud containers: CLAUDE.md's note on the one known
`ApkDownloaderTest` DNS failure). Steps 3 and 4 run `/code-review`; step 3 is `model: max`
because it decides who every send is addressed to, not because encryption is on by default (it
is not — see the note under **Order**).

## 5. Revisit triggers

- **Option C (late binding)**: users report *"This chat isn't ready yet"* after step 1 shipped —
  a reachable unsynced-chat path step 1 did not close.
- **Decision 8**: candidate 2 starts, or `sendBroadcastMessage` gets a second caller.
