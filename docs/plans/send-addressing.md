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
- `refactor(chat): name the chat route's partner argument for what it is` — no CHANGELOG entry.

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
