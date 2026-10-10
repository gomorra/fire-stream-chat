# Message sync: ask for what changed, not for everything

Status: approved by the owner on 2026-10-10, with step 1 built first. Two decisions for steps 2
and 4 are open and marked in the table. Step 1 is shipped.

## Context

Every start of the app fetches every message of every chat from Firestore. Opening a chat listens
to that chat's whole history. The chat screen loads every row of the chat from Room and maps all of
them again on each change.

This is about message documents: text and metadata. Photos, videos and files are downloaded once
and kept, and this plan does not touch them.

Example: 20 chats with 2,000 messages each are 40,000 document reads for one start. Firestore's
free allowance is 50,000 reads a day. Nothing was measured on the owner's account. Step 1 adds the
log line that shows the real number.

The owner wants the phone to ask only for what it lacks.

## Decisions

Agreed with the owner on 2026-10-10:

| Question | Decision |
|---|---|
| Scope | One plan with three parts: the start of the app (step 1), a stamp for changes (steps 2 to 4), the chat screen (steps 5 and 6) |
| What is built first | Step 1. It changes nothing in Firestore and can be released alone |

Proposed. The owner confirms at the checkpoint after step 1:

| Question | Proposal | The alternatives |
|---|---|---|
| A phone with an older app does not write the change stamp. What then? | The server writes the stamp for it, in a new Cloud Function. Older apps keep working. It needs one deploy | Refuse every write without a stamp: an older app can then neither send nor mark as read until it is updated. Or do nothing: a change made by an older app is never seen once step 4 is in |
| Is there a way to fetch everything again by hand? | Yes, one row in Settings: *Reload all messages*. After step 4 nothing reads a whole chat by itself any more, so a message that was missed would stay missed | No row. The only repair is then to clear the app's data |

Set by this plan, not asked:

- **The whole history still comes back after a reinstall.** Each chat is fetched whole once per
  install. Search, the call log and the photo filters read Room and need all of it.
- **Pull to refresh becomes as cheap as a start.** It asks the same question.
- **One Room bump, in step 1.** It carries everything this plan needs from the schema.
- **Step 3 fetches everything one more time.** It is the only way to find changes that nobody
  stamped. Room is updated in place and not emptied.
- **The chat screen starts with the newest 100 messages** and loads 100 more near the top.
- **firebase flavor only.** The pocketbase sources follow every signature change and keep their
  behaviour.

Not in this plan:

- Real Room migrations. `AppDatabase` stays destructive on a bump, and each bump costs one restore.
- One receive path. `syncChatMessages` and `reconcileRawMessage` stay two loops. Joining them is on
  the list before end-to-end encryption is switched on.
- A pin, a poll vote or a timer pause by the other person reaching a row that already exists. See
  the leads in step 7.
- Fetching a restore in pages, skipping a chat by its preview, one query across all chats.
  Each start asks one small question per chat. That is worth a second look at several hundred
  chats.
- The other items of `docs/BACKLOG.md` § *Performance & pagination (6.4)*: thumbnail placeholders.

## What exists today (verified 2026-10-10)

- `ChatListViewModel.kt:99` starts `syncAllChatMessages` once per `ChatListViewModel`, which is
  once per start of the app. `refresh()` (`:240`) starts it again on pull to refresh.
- `MessageRepositoryImpl.syncAllChatMessages` (`:1365`) runs `syncChatMessages` (`:1403`) for every
  chat, three at a time. It calls `messageSource.fetchMessages(chatId)` and then reads Room once
  per fetched message.
- `FirestoreMessageSource.fetchMessages` (`:167`) is `orderBy("timestamp").get()`. It has no limit
  and no lower bound. A plain `get()` answers from the SDK's cache when the phone is offline.
- `FirestoreMessageSource.observeMessages` (`:149`) listens to the whole collection.
  `MessageRepositoryImpl.getMessages` (`:392`) collects it and passes every document to
  `reconcileRawMessage` (`:470`) on entry.
- `MessageDao.getMessagesByChatId` (`:35`) is `SELECT * … WHERE chatId = :chatId ORDER BY timestamp`.
  `MessageEntity` (`:34`) has no index, so the query scans the table.
- `reconcileFromPush` (`:1483`) fetches the one message a push names. It is the only reader that
  asks for less than a whole chat.
- A message's `timestamp` is the sender's clock at the moment of writing. The outbox sends a queued
  message whenever the network is back, with no limit in time, and keeps that `timestamp`.
- A message document has no field that says when it last changed. Receipts (`readBy`,
  `deliveredTo`, `status`), reactions, edits, the tombstone, pins, poll votes and timer state are
  all written onto the document of the message.
- `FirestoreMessageSource` is the only writer of message documents: six creates and fourteen
  updates. `functions/index.js` reads them and writes none.
- `firestore.rules:49` lets every participant of a chat read, create and update its messages. No
  rule lists fields. No rule allows a delete, so a message is never removed, only tombstoned.
- `functions/index.js` has two triggers on message documents: `sendPushNotification` on create
  (`:271`) and `sendReactionPushNotification` on update (`:177`).
- `AppDatabase` is at version 32 and destructive on a bump. `AuthRepositoryImpl.signOut` (`:190`)
  clears every table. `ChatRepositoryImpl.deleteChat` (`:178`) and `leaveGroup` (`:278`) delete a
  chat's messages.
- `ChatMessageLoader.kt:110` is the only caller of `getMessages`. The pinned banner, the receipts,
  the timers, the reply quotes and the photo viewer all read the list it collects.
- `androidx.paging` is not a dependency.

## The model

1. **Room is what the app shows.** The backend is asked only for what Room lacks.
2. **One row of sync state per chat**, in the same database as the messages:
   `message_sync_state(chatId, restoreGeneration, cursorMs)`. Whatever empties the messages empties
   the state with them, and the next start restores the chat.
3. **A chat is restored once.** A restore is one fetch of the whole chat from the server. A chat
   with no state row of the current generation was not restored. Raising the generation in the code
   makes every phone restore once more.
4. **After the restore the phone asks for changes.** Every message document carries `changedAt`,
   the server's time of its last write. The phone asks for `changedAt` later than its cursor.
5. **Three readers, one question each.** A push asks for one message. The sync asks each chat for
   its changes, at the start and on pull to refresh. The open chat listens for the same changes.
6. **The cursor moves only on an answer from the server.** Never on an answer from the cache,
   never on a push, never on a write of this phone. It never moves back.
7. **The chat screen shows a window of Room.** The window ends at the newest message and only
   grows.

Firestore gains one field: `chats/{chatId}/messages/{messageId}.changedAt`, a server timestamp.

What each step changes:

| | Start and pull to refresh | Opening a chat | The chat screen |
|---|---|---|---|
| Today | the whole history of every chat | a listener on the whole chat | every row |
| After step 1 | messages newer than the cursor, less three days | as today | as today |
| After step 3 | documents changed since the cursor | as today | as today |
| After step 4 | as step 3 | a listener on documents changed since the cursor | as today |
| After step 6 | as step 3 | as step 4 | the newest 100 rows, more on scroll |

What step 1 leaves open until step 3:

- An edit, a delete, a reaction or a receipt on a message older than three days, in a chat that is
  not opened, arrives when the chat is opened.
- A message that lands more than three days after it was written, and whose push is lost, arrives
  when the chat is opened.

## Open risks, each with the step that settles it

1. **A message carries the sender's clock, and a queued message can land late.** A cursor on
   `timestamp` can pass it. Step 1 looks back three days, the push names the message, and opening
   the chat reads everything. Step 3 replaces the clock of the sender with the clock of the server.
2. **`get()` answers from the cache when the phone is offline.** A cursor moved on such an answer
   skips messages for good. Sync fetches use `Source.SERVER` (step 1).
3. **Step 1 bumps Room, and a bump empties the database.** The first start after the update is one
   full restore, as every start is today. The sticker-manager plan bumps too, so step 1 takes the
   next free version. A phone with end-to-end encryption switched on loses its readable history on
   this bump, as on every bump.
4. **A wrong `changedAt` freezes a cursor.** One document stamped with a far future time would hide
   every later change. `firestore.rules` accepts a client's stamp only when it is the server's time
   (step 2).
5. **An older app writes no stamp.** The new Cloud Function writes it (step 2). A build with step 3
   on a project without that function misses the changes of an older app at the start. Until
   step 4, opening the chat still shows them.
6. **A server timestamp may be the time of the request and not of the commit.** Two writes can
   then become visible in the other order. Every question looks back ten minutes (step 3).
7. **A write that waits for its server timestamp may not show in a filtered listener.** That is
   SDK behaviour and no JVM test can show it. Step 4 does not rely on it either way, and the
   checkpoint after step 4 checks it on two phones.
8. **The stamp of an older app arrives a moment late.** After step 4 an open chat shows a message
   from an older app only when the function has run. The push brings it too. This ends when every
   phone has the build of step 2.
9. **Steps 5 and 6 touch much of the chat screen.** Step 5 changes no behaviour, so step 6 changes
   only the list.
10. **A runner session cannot run `npm`.** Step 2 writes two Node test suites. The checkpoint after
    step 2 runs them.
11. **This is seven steps.** Run it one checkpoint at a time.

## Steps

Order: 1 ‖ 2 ‖ 3 → 4 ‖ 5 → 6 ‖ 7

Every step follows CLAUDE.md's post-step workflow (tests, `./gradlew test`,
`./gradlew assembleDebug`, review skills, one commit, docs). UI steps load the `app-ui-design`
skill. User-visible steps get a CHANGELOG entry and a bump through the `changelog-release` skill.

### Step 1 — A sync state per chat, and a start that asks for the tail — skills: code-review; model: strong; effort: high

- `data/local/entity/MessageSyncStateEntity.kt`: the table `message_sync_state` with `chatId`
  (primary key), `restoreGeneration: Int` and `cursorMs: Long`. A row exists only for a chat whose
  whole history was fetched from the server on this install.
- `MessageEntity` gets an index on `(chatId, timestamp)`.
- `AppDatabase` takes the next free version and lists the new entity.
- `data/local/dao/MessageSyncStateDao.kt`: read one row, write a restored chat's row, raise a
  cursor, delete one row, delete all. Raising is one statement that never lowers:
  `SET cursorMs = MAX(cursorMs, :value) WHERE chatId = :id AND restoreGeneration = :generation`.
  `di/DatabaseModule.kt` provides the DAO.
- `MessageSource.fetchMessagesAfter(chatId, afterTimestamp)`: the messages with a `timestamp` above
  the bound, oldest first. It and `fetchMessages` answer from the server or throw.
  `FirestoreMessageSource` passes `Source.SERVER` to both. `PocketBaseMessageSource` adds the bound
  to its filter.
- `domain/util/MessageSyncPlan.kt`, pure. From a chat's state row, or none, it answers
  *everything* or *after T*. No row, or a row of an older generation: everything. Otherwise T is
  the cursor less `TAIL_OVERLAP_MS`, three days, and never below zero. `RESTORE_GENERATION` is 1.
- `MessageRepositoryImpl.syncAllChatMessages` asks the plan for each chat. The loop over the
  fetched messages in `syncChatMessages` stays as it is. When the fetch and the loop are through,
  the state is written. A restore writes the row with the highest `timestamp` it fetched. A tail
  fetch raises the cursor to the highest `timestamp` it fetched, and an empty answer leaves it.
- The cursor comes only from documents a sync fetched. A message this phone wrote and has not sent
  yet is the newest row in Room, and a cursor read from Room would pass a message of the other
  person that landed before it.
- A chat's messages and its state row leave in one DAO transaction. `ChatRepositoryImpl.deleteChat`
  and `leaveGroup` call it.
- One log line per sync: the number of chats, how many were restored whole, the number of documents
  fetched.
- `ChatListViewModel` is not changed. The start and pull to refresh both get the new sync.
- Traps:
  - The three days are fetched again on each start. An unchanged message costs one Room read and
    no write.
  - A sync that is cancelled halfway writes no state for the chat it was in. The next start
    repeats it.
  - Offline, every chat's fetch fails at once and is logged, as a failed chat is today.
  - `NonCancellable` stays around the decrypt and its insert.
- Tests:
  - `MessageSyncPlanTest`, a table: no row, a row of an older generation, a cursor, a cursor
    smaller than the overlap.
  - `MessageRepositorySyncTest`: the first sync fetches everything and writes the row. The second
    asks after the cursor less the overlap. A fetch that throws leaves the row as it was. An empty
    answer leaves the cursor. A queued own row does not move the cursor. One chat's failure does
    not stop the others. Deleting a chat removes its row.
  - `MessageSyncStateDaoTest`, in the shape of `StickerDaoTest`: raising never lowers, and a raise
    for a missing row or another generation writes nothing.
  - `FirestoreMessageSourceTest`: the bound, the order, the source.
  - The sync cases of `MessageRepositorySnapshotTest` and `MessageRepositorySyncDecryptTest` stay
    green.
- Docs: `SCHEMA-ROOM.md`, `ARCHITECTURE.md`, a *Message sync* section in `FEATURE-MAP.md`.
  `PATTERNS.md` gets *A sync cursor lives beside the rows it describes*, with its pointer in
  CLAUDE.md. `GOTCHAS.md` gets the `get()` trap. `BACKLOG.md` gets the two things this step leaves
  open and the device check. CHANGELOG `Changed`.

Done when: the gate is green, and in `MessageRepositorySyncTest` the second sync asks for the tail
only.

**Approach**
- Order: `MessageSyncStateEntity` and `MessageSyncStateDao`, the index on `MessageEntity`,
  `AppDatabase` 32 → 33, `DatabaseModule`. Then `MessageSyncPlan`. Then
  `MessageSource.fetchMessagesAfter` in both flavors. Then `MessageRepositoryImpl`, then
  `ChatRepositoryImpl`. Docs last.
- `MessageSyncPlan` lives in `domain/` and cannot see the entity. It takes the row's generation
  and cursor as two nullable numbers.
- The delete of a chat's messages and its state row is one `@Transaction` on `MessageDao`
  (`deleteChatMessages`). `MessageDao` owns the messages, and a Room DAO may write any table.
  `MessageSyncStateDao` therefore gets no delete for one row.
- Writing a restored chat's row is one SQLite upsert. It keeps the higher cursor when a row of the
  same generation is already there, so two syncs of one chat cannot move a cursor back.
- A fetched document that still waits for its own write (`RawMessage.hasPendingWrites`) does not
  count for the cursor. A server `get()` still lays this phone's pending writes over its answer.
- The test factory's `MessageSyncStateDao` answers "no row", because a relaxed mock would answer
  with a mock row. Every existing sync test then takes the restore path it has today.
- Tests: `MessageSyncPlanTest`, `MessageSyncStateDaoTest` (Robolectric, also the chat delete),
  `MessageRepositorySyncTest`, new cases in `FirestoreMessageSourceTest`, and one case in a new
  `ChatRepositoryImplDeleteTest`.
- Nothing found contradicts a decision or a design point.

**Shipped** `6e24eb78` (2026-10-10) — tier: strong. skills: changelog-release, code-review, simplify. Reviewer models: code-review: opus, opus; simplify: sonnet, opus, sonnet, opus. CHANGELOG entry: `6e24eb78`.
Departures (for sign-off):
- `AppDatabase` is at version 33.
- The delete of a chat's messages and its state row is `MessageDao.deleteChatMessages`. `MessageSyncStateDao` has no delete for one row. Its read is `getState`, because `get` collides with MockK inside a stub block.
- `MessageSyncStateDao.writeRestored` is one upsert. It keeps the higher cursor within a generation. It writes nothing for a chat without a row in `chats` (/code-review: a restore that outlived a chat delete or a sign-out left a state row over no messages). `ChatRepositoryImpl.deleteChat` and `leaveGroup` now delete the chat row first, then the messages and the state.
- The cursor stops at this phone's clock, in `MessageSyncPlan.cursorFrom` (/code-review: one sender with a clock weeks ahead put the cursor past everyone else's messages).
- A fetched document with `hasPendingWrites` does not count for the cursor.
- The sync checks for cancellation before it writes the state. The last insert runs under `NonCancellable` and returns normally.
- The log line also counts the chats that failed.
- "Deleting a chat removes its row" is tested in `MessageSyncStateDaoTest` on Room and in `ChatRepositoryImplDeleteTest`, not in `MessageRepositorySyncTest`.
- Not fixed, in `TECH_DEBT.md`: the sync's cursor passes a blocked sender's messages, and PocketBase fetches one page of 200.
- Nothing ran on a device. The checklist is in `docs/BACKLOG.md` § *The message sync asks for the tail*.

### Step 2 — Every write to a message stamps `changedAt` — skills: code-review; model: strong; effort: high

Nothing reads the stamp in this step.

- `FirestoreMessageSource`: every create and every update of a message document also writes
  `changedAt = FieldValue.serverTimestamp()`. The six creates and the fourteen updates go through
  two private helpers that add the field, the three transactions included (`:223`, `:244`, `:418`).
- `FirestoreMessageSourceTest` gets a table with one row per write method of `MessageSource`. Each
  row asserts the stamp in the written data. The test fails for a write method that has no row.
- `functions/messageStamp.js`, pure: from a document before and after a write it answers whether
  the server must stamp it. It must when `changedAt` is missing after the write, or is the same as
  before it. `functions/test/messageStamp.test.js` covers a create with and without a stamp, an
  update with and without one, and the function's own stamp, which must not be stamped again.
- `functions/index.js`: a new function `stampMessageChange` on every write to
  `chats/{chatId}/messages/{messageId}`. It writes the stamp when `messageStamp` says so. The two
  existing triggers stay as they are.
- `firestore.rules`: a create or an update of a message that writes `changedAt` is accepted only
  when the value is `request.time`. A write that leaves the field alone is accepted as today, so an
  older app keeps working.
- `firestore-rules-tests/messages.test.js`: a participant writes with the server's time, with a
  made-up time, and with no stamp, on a create and on an update. Someone outside the chat is
  refused.
- If the owner chose to refuse writes without a stamp, the function is left out, the rule demands
  the field on every write, and the rule tests say so.
- A runner session cannot run `npm`. It writes both Node suites and the checkpoint runs them.
- Docs: `SCHEMA-FIRESTORE.md`, `CLOUD-FUNCTIONS.md`, the AGENT-NOTE of `FirestoreMessageSource`
  (a message is written only through the two helpers).

Done when: the gate is green and both Node suites are written.

### Step 3 — The start asks for what changed — skills: code-review; model: strong; effort: high

- `RawMessage` gets `changedAt: Long?`. `FirestoreMessageSource.mapToRaw` reads it. A stamp that
  still waits for the server is null.
- `MessageSource.fetchMessagesChangedSince(chatId, sinceMs)`: the documents with a `changedAt`
  above the bound, ordered by it, from the server. `PocketBaseMessageSource` has no stamp and
  answers with `fetchMessages`.
- `MessageSyncPlan` answers *everything* or *changed since T*. T is the cursor less
  `CHANGE_MARGIN_MS`, ten minutes. `RESTORE_GENERATION` is 2. The tail answer,
  `fetchMessagesAfter` and `TAIL_OVERLAP_MS` are removed.
- A restore of generation 2 writes the highest `changedAt` it fetched as the cursor, or zero when
  no document has one. A document without the field never answers the question, and it does not
  have to: the restore read it.
- A changes fetch raises the cursor to the highest `changedAt` it fetched. A document whose stamp
  is null moves nothing.
- Every chat has a row of generation 1 after the update, so the first start restores each chat
  once more. The messages are updated in place. Files, stars and queued sends stay.
- The loop in `syncChatMessages` stays. It now gets old documents that changed, and it already
  handles a tombstone, reactions, the status of an own message and an edit.
- Tests: the table in `MessageSyncPlanTest`. `MessageRepositorySyncTest`: a row of generation 1
  leads to a full fetch and a row of generation 2; the next sync asks since the cursor less the
  margin; the cursor never moves back; a deleted, an edited and a reacted-to old message reach
  Room through a changes fetch; a document without a stamp breaks nothing.
  `FirestoreMessageSourceTest`: the question and the mapping.
- Docs: `ARCHITECTURE.md`, `FEATURE-MAP.md`. `BACKLOG.md` loses the two open points of step 1.
  CHANGELOG `Changed`.
- **(step-1)** The code as it stands:
  - The plan's answer type is `MessageSyncPlan.Fetch` (`Everything`, `After`), from
    `fetchFor(restoreGeneration, cursorMs)`. The DAO's read is `MessageSyncStateDao.getState`.
  - `MessageSyncPlan.cursorFrom(timestamps, nowMs)` makes the cursor and caps it at this phone's
    clock. With `changedAt` the values are the server's, so decide whether the cap stays. A phone
    whose clock runs behind would hold its cursor back and only fetch more.
  - `MessageRepositoryImpl.syncChatMessages` leaves a document with `hasPendingWrites` out of the
    cursor. Keep that: its `changedAt` is null or an estimate.
  - `MessageSyncStateDao.writeRestored` already replaces a row of generation 1 and takes the new
    cursor as it is, so the switch from `timestamp` to `changedAt` needs no change there.
    `MessageSyncStateDaoTest` pins it.
  - The two open points of step 1 sit in `BACKLOG.md` § *Performance & pagination (6.4)*.
    `ARCHITECTURE.md` § *Message sync* and the `GOTCHAS.md` entry on `get()` name the tail too.

Done when: the gate is green and a changed old message reaches Room without its chat being opened.

### Step 4 — The open chat listens for changes — skills: code-review, simplify; model: max; effort: xhigh

The listener, the sync and the push write the same rows, and the cursor now has two writers.

- `MessageSource.observeMessagesChangedSince(chatId, sinceMs)` emits `MessageChanges`: the
  documents, and whether the answer came from the server. `FirestoreMessageSource` listens on the
  question of step 3 and keeps `MetadataChanges.INCLUDE`. `PocketBaseMessageSource` wraps
  `observeMessages`.
- `MessageRepositoryImpl.getMessages`: a chat with a state row of the current generation gets the
  changes listener, from its cursor less the margin. A chat without one keeps today's listener on
  the whole chat, and the next sync restores it.
- Every answer from the server raises the chat's cursor to the highest `changedAt` in it. An answer
  from the cache never does.
- Every document still goes through `reconcileRawMessage`. The map of reconciled documents and
  `conflate()` stay.
- An own write does not need its pending echo. `acknowledgeOwnEcho` ignores a pending echo today,
  and the acknowledged document always arrives, because the server's stamp makes it answer the
  question.
- If the owner confirmed the Settings row: `MessageRepository.reloadAllMessages()` deletes every
  state row and runs the sync for every chat, on the application scope. `SettingsViewModel` and
  `SettingsScreen` get the row *Reload all messages* beside the media download row, with a running
  state. A chat that is open meanwhile keeps its listener, and its raise finds no row and writes
  nothing.
- Tests: `MessageRepositorySnapshotTest` keeps its cases for a chat without a row. New cases: a
  chat with a row gets the changes listener from the cursor less the margin; an answer from the
  server raises the cursor; an answer from the cache does not; a listener that fails still serves
  Room. `FirestoreMessageSourceTest`: the listener's question and the server flag.
  `SettingsViewModelTest`: the reload.
- Docs: `ARCHITECTURE.md`, `FEATURE-MAP.md`, the AGENT-NOTE of `MessageRepositoryImpl`,
  `BACKLOG.md` § *Pending on-device verification*. CHANGELOG `Changed`, and `Added` for the row.
- **(step-1 /code-review)** The sync skips a blocked sender's messages and its cursor passes them
  (`TECH_DEBT.md` § *Unblocking a user does not bring back what the sync skipped*). Today the open
  chat's whole listener brings them back after an unblock. This step takes that listener away, so
  an unblock must delete the state rows, or the messages stay missing. Add the test.
- **(step-1)** `MessageSyncStateDao.deleteAll` exists and has no caller yet.
  `reloadAllMessages` is its first. `writeRestored` writes only for a chat with a row in `chats`.

Done when: the gate is green and opening a restored chat asks for its changes only.

### Step 5 — What the chat needs from old messages comes from Room (UI + state) — skills: app-ui-design; model: strong; effort: high

The list is still complete in this step, so nothing changes on screen. Each reader below moves from
the list to its own Room query, collected by the manager that owns the slice.

- The pinned banner (`ChatMessageLoader.kt:125`, `ChatScreen.kt:1216`): the chat's pinned messages.
- The receipts (`ChatMessageLoader.kt:166-202`): the ids of incoming messages with status `SENT`,
  and with status `DELIVERED`. The two steps and the delay stay.
- The timers (`ChatTimerReactor.kt:51`, `:67`): the chat's timer messages. A timer that is not in
  the list must not lose its alarm.
- The reply quotes (`ChatScreen.kt:1401`, `:1436`): a quoted message that the list does not hold is
  read by id.
- The photo viewer (`ChatScreen.kt:2469`, `ChatMediaGallery.kt:26`): the chat's pictures.
- The shared lists (`ChatMessageLoader.kt:254`, `:268`): the list ids of the chat's list messages,
  the same ten as today.
- `MessageDao` gets the six queries. `MessageRepository` exposes them as flows of domain models.
- Tests: each query in a DAO test. `ChatTimerReactorTest`, `ChatMediaGalleryTest`,
  `ChatViewModelReadReceiptTest` and `ChatViewModelFullscreenImageTest` feed the new flows. One new
  case each for a message the list does not hold: a pin, a running timer, a quoted message, an
  unread message.
- Docs: `FEATURE-MAP.md`, `PATTERNS.md` § *ChatUiState slice composition* if a slice gains a field.

Done when: the gate is green and each of the six readers passes with a list that lacks its message.

### Step 6 — The chat loads a window (UI + state) — skills: app-ui-design, code-review; model: strong; effort: xhigh

This step runs one effort level above its tier. Scrolling, jumping and loading all change the same
list at once.

- `MessageDao`: the chat's rows from a timestamp on, the timestamp of the n-th newest row, and
  whether older rows exist.
- `MessageRepository.getMessages` splits in two. One call syncs the chat while it is open: the
  listener and the media scan. The other reads the window from Room. Growing the window must not
  start the listener again.
- `ChatMessageLoader` owns the start of the window. It begins at the 100th newest row and moves
  down 100 rows when the oldest loaded row is within 20 rows of the screen. `MessagesState` says
  whether older rows exist.
- The window has no upper end, so a new message arrives in it by itself. Older rows are added at
  the far end of the reversed list, and the rows on screen keep their place.
- A jump to a message outside the window loads it first: the start moves to 20 rows before the
  target, the caller waits until the list holds the id, then the existing scroll runs. This is
  `jumpToSourceMessage` (`ChatScreen.kt:520`) for the reply quote (`:1549`), the reaction button
  (`:1679`), the viewer's exit (`:552`) and a search hit (`:601`). The deep link (`:705`,
  `DeepLinkTarget.kt:33`) loads a message that Room holds and no longer waits for it.
- A jump to a very old message loads everything from there to the newest. That is no worse than
  today.
- The remembered scroll position is a message id, not an index (`ChatViewModel.kt:150`,
  `ChatScreen.kt:611`, `:657`). An index stored by an older build is ignored.
- The effects keyed on `messages.size` (`ChatScreen.kt:687`, `:893`) are keyed on the id of the
  newest message, so loading older rows does not run them.
- Tests: `ChatViewModelScrollRestoreTest`, `ChatViewModelSearchLandingTest`, `DeepLinkTargetTest`,
  `DeepLinkJumpGuardTest`, `AutoScrollOnNewMessageTest`. New: the first window, a load near the
  top, a jump outside the window, a new message while an older page loads, a chat with fewer rows
  than one window.
- Docs: `ARCHITECTURE.md`, `FEATURE-MAP.md`, `PATTERNS.md` § *reverseLayout for chat lists*,
  `BACKLOG.md` (6.4 loses message paging; the device check). CHANGELOG `Changed`.

Done when: the gate is green and a chat of 5,000 rows opens with 100 loaded.

### Step 7 — Bug hunt over everything this plan built — model: max; effort: xhigh; budget: 60

`/code-review` is the standards-and-spec skill while `.claude/skills/code-review` is a symlink,
and it looks for no bugs. This step is the correctness review. It adds no feature.

- **Scope from git.** The base is the parent of step 1's code commit, named in its Shipped line.
  `git diff --name-only <base>..HEAD -- app/src/main functions firestore.rules` lists the files.
  Leave out a file that only work from outside this plan changed.
- **Which review.** When `.claude/skills/code-review` is a directory of this repo and no symlink,
  run it on that range and skip the next bullet.
- **One reviewer per area**, on this step's tier or below. Each reads its files whole and follows
  the calls that leave them.
  1. The state and the cursors: `MessageSyncPlan`, the sync in `MessageRepositoryImpl`,
     `MessageSyncStateDao`. A cursor that moves on an answer it should not trust. A sync and a
     listener that write the same chat at once. A sync that is cancelled. A chat that is deleted,
     left or reloaded while it syncs. Sign-out during a sync.
  2. The stamp: every write of `FirestoreMessageSource`, `functions/messageStamp.js`,
     `stampMessageChange`, `firestore.rules`. A write that is not stamped. A stamp the function
     writes twice. A phone on the last release from before this plan in the same chat.
  3. The open chat: `getMessages`, the changes listener, `reloadAllMessages`. Offline entry.
     A chat opened before its first restore. An own message from queued to read.
  4. The chat screen: `ChatMessageLoader`, the six Room readers, the window, the jumps, the
     remembered position. Rotation and process death. A chat shorter than one window.
- **A finding needs a failure path.** A reviewer reports the input or state, the path through the
  code with `file:line`, and the wrong outcome. The step session reads each path itself and keeps
  only what holds. Where a JVM test can show the failure, the test is written first.
- **Leads to confirm or refute first.** They were read from the code on 2026-10-10 and never
  confirmed. All three are older than this plan.
  1. A pin, a poll vote or a timer pause by the other person does not reach a row that already
     exists. The reconcile rewrites an incoming row only when `editedAt` or `deletedAt` moved
     (`MessageRepositoryImpl.kt:501`, `:1441`), and an own row only in its status (`:1545`).
  2. `markChatAsDelivered` asks for every document with status `SENT` in a chat, at each start
     (`FirestoreMessageSource.kt:392`). How many documents that returns on a real account is not
     known.
  3. `PocketBaseMessageSource.fetchMessages` asks for one page of 200 messages.
     **(step-1)** Confirmed by reading, and it now marks a chat restored at its 200th message
     (`TECH_DEBT.md` § *PocketBase: a message fetch is one page of 200*).
- **(step-1 /code-review)** For area 1, three things step 1 settled and how. Check that they still
  hold after steps 3 and 4.
  - A restore that outlives a chat delete or a sign-out: `writeRestored` writes only while the chat
    row exists, and `ChatRepositoryImpl.deleteLocally` deletes the chat row first. The reconcile
    loop can still insert messages of the deleted chat after the delete. They have no state row.
  - A cancelled sync: `ensureActive()` before the state write.
  - `MessageDao.deleteMessagesByChatId` is still public. Nothing but `deleteChatMessages` may call
    it, and no `ArchitectureTest` rule says so.
- **Fix what is confirmed and severe.** A confirmed finding that loses a message or a change, shows
  a deleted message, or opens a security hole is fixed in this step, with a test that fails
  without the fix. A fix that would change a decision of this plan goes to the owner in the Shipped
  block. Every other confirmed finding becomes one line in `docs/BACKLOG.md` or `TECH_DEBT.md`.
- **Record** a `**Bug hunt**` block above the Shipped line: the areas, each reviewer's model, every
  confirmed finding with its failure path and what became of it, and the leads that did not hold.

Done when: the **Bug hunt** block is committed, and every fix carries its test.

## Verification

- **Gate, every step:** `./gradlew test` and `./gradlew assembleDebug`. The pocketbase sources
  follow every signature change. `./gradlew test` compiles them, and CI also runs
  `assemblePocketbaseDebug`.
- **After step 1, on the phone, by upgrading over an install:** the first start restores every
  chat, and the log line shows the full number. The second start shows a small number. A message
  sent to the phone while the app is closed is there after the next start, without opening its
  chat. Start once in flight mode: nothing is lost, and the next start with a network catches up.
  Search finds an old message.
- **After step 2, before the deploy:** `cd functions && npm test`, and `npm test` in
  `firestore-rules-tests/`. Then `firebase deploy --only functions,firestore:rules`. In the
  Firestore console a new message has `changedAt`. A message from a phone on the released app gets
  it within seconds.
- **After step 4, two phones:** the first start restores once more. With chat A closed on phone 1,
  phone 2 edits an old message, deletes one, reacts to one and reads one. After a start of phone 1,
  all four are there without opening the chat. With the chat open on both: a message, a reaction
  and the read ticks arrive live. An own message goes from the clock to two blue ticks. A chat
  opened in flight mode shows its history. *Reload all messages* runs through and changes nothing.
- **After step 6, on the phone:** a long chat opens at the newest message. Scrolling up loads more
  without a jump. A reply quote, a search hit, a pinned message and a reminder each travel to a
  message far up. The photo viewer swipes past the loaded rows. Leaving and coming back returns to
  the same message.
- **After step 7:** read the **Bug hunt** block: what was fixed, and what waits for the owner.
- **Owed on hardware** (to `docs/BACKLOG.md` § *Pending on-device verification*): the reads per
  day in the Firebase console before and after, a chat with several thousand messages on the S24
  and the S25, a phone on the older app as the partner.

## Run

```bash
scripts/run-plan.sh docs/plans/message-sync.md --dry-run
scripts/run-plan.sh docs/plans/message-sync.md
```

A run stops at every `‖` of the Order line, so the first run builds step 1 only. Run it again to
go on.

Step 1 can also be built by hand in a normal session, on main. It then gets its `**Shipped**` line
under the heading, committed, and the runner goes on with step 2. Every step heading names its
tier and its effort, and the runner uses both.
