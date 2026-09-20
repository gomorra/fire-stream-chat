# Send addressing — the repository decides who a send is for

Brief for removing `recipientId: String` from `MessageRepository`'s seven send members
and resolving the send target inside the repository from the chat row. Written
2026-09-20 from an architecture review of `main` at `9d28ed2` (two explorer agents,
counts spot-checked); re-verify line numbers before editing.

**This is a brief, not a runnable plan.** A fresh session takes it in three moves:
grill §3 with the human and record the answers as a §0 decisions table; run the
`codebase-design` skill's *design it twice* on §4; then write `docs/plans/send-addressing.md`
with an `**Order:**` line, `skills:` tags (`code-review` is mandatory — Signal path) and
`model: max` on the step that touches the send entry. Do not implement from this file.

## 0. Why

The encrypt-to-one-session vs plaintext decision is the emptiness of a `String` the
*caller* computes. The interface therefore carries a fact a caller can get wrong, the
fact is the encryption decision, and a caller already got it wrong once in a shipped
release: the forward dialog addressed a group send to `firstOrNull` member, which in a
release build encrypted it to that member's Signal session alone (fixed 2026-09-18 by a
UI-side guard, `Chat.sendRecipientId`). TECH_DEBT's entry *"The 'who is this send addressed
to' rule is enforced by a UI helper, not by the send API"* names this brief's shape as the
fix and gives "a seventh `recipientId: String` parameter" as the trigger — `sendTimerMessage`
is the seventh. The change narrows the widest interface in the codebase by seven
parameters without splitting it, and turns the block tests from tests of a convention
into tests of the invariant.

Vocabulary (from `.claude/skills/codebase-design`): the *interface* is everything a caller
must know; today it includes `"" ⇒ plaintext`. Deepening = that fact moves behind the seam.

## 1. Current state (verified 2026-09-20)

**The interface.** `domain/repository/MessageRepository.kt` — seven members take
`recipientId: String`: `sendMessage` (:15), `sendMediaMessage` (:25), `retryFailedMessage`
(:33), `forwardMessage` (:38), `sendVoiceMessage` (:41), `sendLocationMessage` (:85),
`sendTimerMessage` (:96–103). The convention is documented once, as a `@param` on the
impl: `data/repository/MessageRepositoryImpl.kt:486–497`.

**The machinery already behind the seam** (the interesting part — only the *entry* still
trusts the UI's string):
- `data/outbox/SendTarget.kt` — `Peer(id)` / `NoPeer`; `of(recipientId)` is the one place
  `""` becomes `NoPeer`; `column` / `fromColumn` map to and from the row's
  `outboxRecipientId`. Its AGENT-NOTE says "the UI's recipient id enters through `of`".
- `data/local/entity/MessageEntity.kt:61–73` — `sendTarget` reads the column;
  `MessageEntity.outbox(message, target)` writes it at the optimistic insert.
- `data/outbox/OutboxSender.kt:126, 225, 237` and `MessageWriter.kt:63, 142` — encrypt
  for the **row's recorded** target; the caller's string is never consulted after insert.
- `MessageRepositoryImpl.kt:222–252` — `blockVerdict(senderId, target)`; two rules on top:
  `refuseIfBlocked` (queued sends: Blocked refuses, Unknown queues and the worker asks
  again online) and `ensureNotBlocked(senderId, recipientId: String)` (timer, direct write:
  strict — Blocked *and* Unknown refuse). The timer rule still takes the raw string.
- `MessageRepositoryImpl.kt:213–280` — `enqueueSend(row, blockTarget, retry)`: the
  NonCancellable insert → block check → stage → schedule phase. Must not change shape.
- `retryFailedMessage` (:620–641) already prefers the row's target:
  `queued.sendTarget ?: SendTarget.of(recipientId)` — the argument only stands in for a
  row that recorded none, and the worker refuses such a row rather than guess.
- `sendBroadcastMessage` (:857–915) fans out via `chatRepository.get().getOrCreateChat(recipientId)`
  (:887) — every fan-out row belongs to an INDIVIDUAL chat, so a row-based resolution
  covers broadcasts too.
- The repository already injects `ChatDao` (:154); `ChatDao.getChatById(chatId): ChatEntity?`
  (`data/local/dao/ChatDao.kt:17`) with `type: String` and `participants: List<String>`
  (`ChatEntity.kt:13, 18`).

**The callers.** Ten send call sites in four files: `ui/chat/ChatMessageSender.kt:83, 111,
133, 154, 170`, `ui/chat/ChatViewModel.kt:663` (timer), `ui/chat/ChatMessageActions.kt:103`
(forward), `ui/share/SharePickerViewModel.kt:200, 212`. Guarded through
`Chat.sendRecipientId` (`ui/components/ChatTargets.kt:54`, INDIVIDUAL ⇒ other participant,
else `""`): only `ChatMessageActions:106` and `SharePickerViewModel:156/167`. The rest pass
`ChatViewModel.recipientId` (:122) = `savedStateHandle["recipientId"]`, a nav argument
produced at twelve `Routes.chat(` sites in `navigation/NavGraph.kt` (269–688): two hardcode
`""` (:611, :623), one does `recipientId ?: ""` (:636), the rest forward whatever the
originating screen computed with its own copy of the partner expression (see the review's
candidate 2: 13 copies, three semantics).

**Hops today** for "send a photo to a group", files an agent opens to follow the bytes:
`ChatScreen:858 → ChatViewModel:264 → ChatMessageSender:111 → MessageRepository →
MessageRepositoryImpl.sendMediaMessage:580 → MessageEntity.outbox + SendTarget.of →
enqueueSend:265 → OutboxFiles.stage → OutboxScheduler.enqueue → OutboxWorker.doWork →
OutboxJob → OutboxSender.send:100 → ImageCompressor / MediaFileManager / StorageSource →
MessageWriter.write:88 → FirestoreMessageSource.writeMessage` — 15. To answer only *why is
it plaintext*: `ChatTargets`, `NavGraph`, `ChatViewModel`, `ChatMessageSender`,
`MessageRepositoryImpl`, `SendTarget`, `MessageEntity`, `MessageWriter` — 8.

**Tests that touch this.** `data/repository/MessageRepositoryBlockTest` (12 cases; the
group case is "pass `""`", :202 — it tests the convention, not the invariant),
`MessageRepositoryForwardTest`, `MessageRepositoryTimerTest`, `ui/components/ChatPickerTargetsTest`
(`sendRecipientId` cases), `ui/chat/ChatMessageSenderOfflineTest` (recipient parameter),
`test/fakes/FakeMessageRepository` (`lastSentRecipientId`, `SentMedia.recipientId`). All 17
`MessageRepository*Test` classes build the repo through `MessageRepositoryTestFactory`
(20 constructor params, relaxed mocks); `ChatDao` is among them, so seeding a chat row is
one `coEvery`.

## 2. Constraints — must hold, argue any change explicitly

- **A group or broadcast send must be unrepresentable as encrypt-to-one.** There is no
  legitimate "encrypt a group message for one member". Make it impossible, not documented.
- **The inverse bug is worse:** a 1:1 that resolves to `NoPeer` travels in plaintext to a
  chat that should be encrypted. Any fallback that yields `NoPeer` on doubt is wrong;
  doubt must refuse (see §3.1, §3.5).
- **The row records the target at insert; the outbox reads the row, never the caller.**
  This is what makes retry and process death safe. Keep it.
- **Block-check semantics per path** (queued: Blocked refuses, Unknown queues; timer:
  strict) stay unless the plan's §0 says why not.
- **`enqueueSend`'s NonCancellable phase** (:213–280) keeps its shape; the resolution is a
  cached/local read and belongs *before or inside* it, never after the insert.
- **Flavors.** PocketBase: `BuildConfig.SUPPORTS_SIGNAL = false`, `PocketBaseMessageSource.sendMessage`
  (ciphertext) throws `NotImplementedError` — the resolution must not route a
  plaintext-only flavor into the encrypt branch (today `MessageWriter`'s gate handles it;
  keep that the one place). Debug builds: `MessageWriter`'s `DEBUG` gate ⇒ plaintext
  regardless — tests must exercise the encrypting branch via `MessageWriter`'s
  `buildEncrypts` test constructor, not a release build.
- **Gates.** Security-adjacent: strongest tier for the design and the send-entry step;
  `/code-review` on the diff (Signal path — CLAUDE.md makes it mandatory); regression test
  written *before* the fix (Change Safety).
- **Cost.** One Room read of the chat row per send. Acceptable (local-first, the row exists
  for any chat the user is in); the plan says where it is cached if it is on the hot path.

## 3. Open questions — grill these first, record answers in the plan's §0

1. **Row missing.** `getChatById` returns `null`: refuse (row FAILED with a clear reason),
   sync first, or fall back to a caller-supplied hint? Which paths can hit it — a deep link
   from a notification into a chat not yet synced (`MainActivity` extras), a fresh 1:1 whose
   `getOrCreateChat` has not landed in Room? (The broadcast fan-out creates the chat first,
   so not that one.) Given §2's second bullet, "refuse" is the safe default; say so or argue.
2. **Does the `recipientId` nav argument survive?** Non-send consumers: `ChatTimerReactor.kt:97`
   (deep-link partner, `recipientId.takeIf { it.isNotEmpty() }`), anything reading
   `ChatViewModel.recipientId`. If it survives for those, say which; if not, the twelve
   `Routes.chat(` producers simplify and `Routes.chat(chatId, recipientId)` loses a parameter.
3. **`retryFailedMessage(messageId, recipientId)`.** With the row recording the target, the
   argument's only role was a fallback the worker refuses anyway. Drop it, or keep the fallback?
4. **Where the resolution lives.** A private read on each of the seven paths, or once at
   the entry to `enqueueSend` so the timer's direct write (`ensureNotBlocked`, still
   `String`-typed) gets it too? One place is the deepening; seven reads is the same
   shallowness moved one file over.
5. **The sharp edge — INDIVIDUAL with no other participant.** A self-chat, or a 1:1 whose
   `participants` is stale/unsynced: `INDIVIDUAL` + `singleOrNull` other = `null`. That is
   *not* `NoPeer` (plaintext); it is "cannot address" ⇒ refuse. Decide the exact rule and
   the user-visible failure.
6. **Sequencing with candidate 2** (the same review: "who is the 1:1 partner" and "what is
   this chat called" become behaviour on domain `Chat`). After this change
   `Chat.sendRecipientId` is unused for sends; its partner expression is one of 13 copies.
   One plan or two? (Recommendation: two — this one first, it removes candidate 2's stated
   reason for living in `ui/`.)
7. **Test proxies.** Which tests assert the recipient string as a proxy for "encrypted"
   (`MessageRepositoryBlockTest:202` does) and what do they assert instead — the
   `outboxRecipientId` column on the inserted row is the honest observable.

## 4. Design it twice — forced options for the `codebase-design` DESIGN-IT-TWICE pass

- **A. Resolve inside the repository.** `recipientId` leaves all seven signatures; the send
  entry reads `chatDao.getChatById(chatId)` once and derives `SendTarget` (INDIVIDUAL ⇒
  `Peer(other)`, GROUP/BROADCAST ⇒ `NoPeer`, unresolvable ⇒ refuse). Everything from the row
  down is unchanged. `SendTarget` stays in `data/outbox` as the outbox's private shape.
- **B. Lift `SendTarget` into `domain/` and take it typed.** Every send takes a `SendTarget`
  built by one factory. Typed, but still caller-computed: it makes the `""` convention
  *explicit*, not *impossible* — a caller can still build `Peer(arbitraryMember)` for a group.
  Include so the comparison shows the difference between a narrower type and a deeper module.
- **C. Late binding.** The repository inserts the row with the target unresolved; the outbox
  resolves it at attempt time from the chat row (after sync has had a chance). Include for
  the unsynced-row case (§3.1) and process death; costs a second column state (`null` today
  already means "never recorded — do not send", `SendTarget.fromColumn`).

Judge each on: (1) can a group message ever be encrypted to one member; (2) the
INDIVIDUAL-with-no-other case (§3.5) — refuse or plaintext; (3) what the block check asks
per path, and when; (4) outbox row construction and `MessageEntity.outbox`; (5) the test
surface — the interface is the test surface: what does a `MessageRepositoryBlockTest` case
look like; (6) hops for "send a photo to a group" (15 today) and for "why is it plaintext"
(8 today); (7) PocketBase; (8) cost on the send path and where the read is cached.

## 5. Test surface after (whichever option wins)

- `MessageRepositoryBlockTest`: seed an INDIVIDUAL vs a GROUP `ChatEntity` via the factory's
  `ChatDao`; assert the `outboxRecipientId` recorded on the inserted row (`Peer` vs `NoPeer`)
  on every path — text, media, voice, location, timer, forward, retry. This is the invariant.
- `MessageRepositoryForwardTest`: forwarding into a group records `NoPeer` — the 2026-09-18
  bug as a regression test. Write it first against the current shape (passing an arbitrary
  member) and watch it fail; that is the red step.
- New case per §3.1 / §3.5: unresolvable chat ⇒ refused, row FAILED, no plaintext.
- Waste to delete: `ChatPickerTargetsTest`'s `sendRecipientId` cases; `FakeMessageRepository`'s
  two recipient fields; the recipient parameter in `ChatMessageSenderOfflineTest`.

## 6. Docs that move with the code

- `TECH_DEBT.md` — delete *"The 'who is this send addressed to' rule…"*; edit the
  "Partly resolved" paragraph of *"Who is the other participant…"* (its "the deeper fix is
  the repository API" reason is gone).
- `docs/PATTERNS.md` *One chat picker, three hosts* — the `sendRecipientId` line moves inside
  the seam; *Sends are idempotent…* only if `enqueueSend`'s contract wording changes.
- `data/outbox/SendTarget.kt` AGENT-NOTE ("the UI's recipient id enters through `of`") and the
  `@param` KDoc at `MessageRepositoryImpl.kt:486–497` — rewrite, don't leave stale.
- `CHANGELOG.md` — a `Fixed` entry (closes a bug class; `fix:` ⇒ patch bump per the
  `changelog-release` skill).
- `docs/FEATURE-MAP.md` if any file moves.

## 7. Not in scope

Candidates 6–8 of the same review (fold the send managers; one reconcile body, no
`dagger.Lazy`; the outbox front door) all touch `MessageRepositoryImpl` — never run any of
them in parallel with this. Candidate 2 per §3.6.
