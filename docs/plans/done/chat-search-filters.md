# Chat search — prefilter chips

Give in-chat search a row of filter chips under the search box, so the search
pane doubles as a browser ("show me the photos") rather than only a text
matcher. Merges `SharedMediaScreen` into the same surface.

**Order: 1 → 2 → 3 → 4 → 5 → 6 → 7** (fully sequential — steps 4–7 all edit
`ChatScreen.kt`, and 2 depends on the filter model from 1)

---

## Decisions already taken

Settled in discussion; do not re-litigate during implementation.

| Decision | Rationale |
|---|---|
| **Blank query + active filter = browse mode** | "Show me the photos" is the dominant use; nobody types a word first. `SearchMessagesUseCase`'s blank-query guard becomes "blank **and** no filter → empty". |
| **`SharedMediaScreen` is merged, not kept** | Otherwise two per-chat media browsers with different data sources drift apart. The overflow menu item opens search pre-filtered to Photos. Note this does **not** leave one media browser app-wide: `ProfileScreen` keeps its own grid (`SharedMediaGrid` at `:591`, fed by `getSharedMediaForUser`), which is per-*user* rather than per-chat and stays. |
| **One horizontally scrollable `LazyRow` of chips; no overflow `…` chip** | An overflow chip can't honestly show selection state — it either hides the active filter or reorders chips under the thumb. `ChatScreen` is its own NavHost destination (`NavGraph.kt:467`), **not** a page in `MainScreen`'s `HorizontalPager`, so a `LazyRow` has no gesture conflict. |
| **Type chips single-select; Starred and Date are independent toggles** | Two different axes. Multi-select across types would read as AND where the user means OR. |
| **Active filters shown in the results header, not only in the chip row** | Chips scroll off-screen; an active-but-invisible filter is the same dishonesty we rejected the overflow chip for. Reuses the existing `"N results"` line (`ChatScreen.kt:1065`). |
| **Links via `content LIKE '%http%'` heuristic — no `hasLink` column** | Zero schema change. Misses bare `www.`/domains; revisit only if that proves annoying in practice. A column would need a version bump **and** a backfill. |
| **No index on `(chatId, timestamp)` in this change** | Deliberately deferred. `LIMIT 50` caps the damage at current chat sizes. Trigger to revisit: the Photos chip feeling sluggish on a real long chat on device. **Interacts with the `LIMIT` raise in step 2** — a browse limit of 200 quadruples the scan the missing index would have covered, so 200 is chosen as the largest value that keeps the deferral honest. Going higher means doing the index too. |
| **Reuse `jumpToSourceMessage`; do NOT reuse the reaction cue pattern** | See "The landing path" below. |
| **`From…` chip dropped** | Only useful in group chats, which aren't in daily use here. |

### No Room version bump

This change adds **no column and no index**. `AppDatabase`'s `version` stays as
it is. (Stated explicitly because the project rule otherwise makes this the
first question asked.)

## Non-goals

- FTS4/FTS5. `content LIKE '%q%'` stays; the leading wildcard means no index
  helps the text match anyway. `MessageDao.kt:82` already anticipates FTS as a
  separate change.
- Filters in global search (`ChatListViewModel`). It shares
  `SearchMessagesUseCase` so it *could* come along, but it doubles the
  result-rendering work. In-chat first.
- Message paging. See step 7 for why it matters anyway.

---

## The landing path — the one subtle part

`jumpToSourceMessage` (`ChatScreen.kt:378`) returns silently when the id isn't
in `uiState.messages.messages`. Two facts make that worth handling here rather
than ignoring:

1. `getMessagesByChatId` (`MessageDao.kt:19`) has **no `LIMIT`** — the whole
   chat history is in memory, so any local hit is currently present. Fine today.
2. Search is the **only** caller whose targets come from a *different* query
   (`LIMIT 50` over all history) than the list it jumps into. Replies,
   reactions and the fullscreen-viewer exit all target the loaded window. So
   search is the caller that breaks first, and silently, if paging ever lands.

And the feature itself introduces a live instance of the mismatch:
`softDeleteMessage` (`MessageDao.kt:58`) sets `content = ''` but leaves
`mediaUrl` intact. A non-blank `content LIKE` can't match a deleted row — but a
**filter-only query with a blank query has no content predicate**, so
`type = 'IMAGE'` would return deleted images and render their thumbnails.
`SharedMediaViewModel` already guards `deletedAt == null`.

**Handling, two layers:**

- **Eliminate the cause** (step 2) — `AND deletedAt IS NULL` on every filtered
  query. Mandatory, not polish. Also re-aligns "what search can return" with
  "what the list renders".
- **Make the residue loud** (step 7) — `jumpToSourceMessage` returns `Boolean`;
  on `false` keep the search overlay open and show a snackbar,
  "Message no longer available". The three existing callers ignore the return
  value, so no call-site churn.

**Rejected:** filtering search results against the loaded list (drops valid
hits, inverts the dependency); building load-around-message paging now (no
paging exists to page around).

### Reuse the helper, not the pattern

`jumpToSourceMessage` is a ~15-line helper that reactions merely *also* calls,
alongside replies (`:1278`) and the fullscreen-viewer exit (`:~610`). Search
becomes a fourth caller — that is sharing a function, not adopting an
architecture. It buys the flash-on-arrival that search taps lack today.

The actual reaction **cue** pattern — one-shot state on the owning slice,
`consumeReactionCue()`, the `withTimeoutOrNull` wait for a measured list, the
off-screen FAB — exists to answer *"the user didn't ask to go anywhere; flash
in place or offer a jump?"*. A tapped search result asks none of that: the user
asked explicitly, the overlay closes, you always travel. **Do not import it.**

Filter state goes on `OverlaysState` because that's where `searchQuery` and
`searchResults` live and because of Chat\*Manager slice ownership — ordinary
reasons, *not* by analogy to the cue. There is no `consumeFilterCue()`.

---

## Steps

### 1 — Domain: the filter model

*Lands as one commit with step 2.* A filter model with no query behind it is
green but has no consumer, and splitting costs a second full
`test` + `assembleDebug` gate for nothing.

- New `domain/model/MessageSearchFilter.kt`: the type axis as an enum
  (`PHOTOS, VIDEOS, LINKS, DOCS, VOICE`), plus `isStarred: Boolean` and a
  nullable date range.
- **`LINKS` is not a `MessageType`** — a link lives in the `content` of a
  `TEXT` row. The enum is a *filter* axis, not a message-type alias, so it maps
  to **two** DAO parameters (see step 2), not one. Keep that mapping in the data
  layer; the domain enum stays flat.
- `SearchMessagesUseCase` takes the filter; guard becomes
  "blank query **and** no active filter → `emptyList()`".

**Tests:** yes — extend `SearchMessagesUseCaseTest`. Cover blank+filter (browse
mode returns results), blank+no filter (empty), and that the filter reaches the
repository unchanged.

### 2 — Data: the filtered query

- `MessageDao`: replace `searchMessagesInChat` with a nullable-param query,
  keeping Room's compile-time verification rather than dropping to `@RawQuery`:
  - `(:type IS NULL OR type = :type)` — the `MessageType`-backed chips
  - `(:requireLink = 0 OR content LIKE '%http%')` — the separate `LINKS` param
  - `(:starredOnly = 0 OR isStarred = 1)`
  - `(:from IS NULL OR timestamp >= :from)` / `(:to IS NULL OR timestamp <= :to)`
  - `(:query = '' OR content LIKE '%' || :query || '%')` — **short-circuit**, so
    browse mode doesn't scan every row's content against `'%%'`
  - **`AND deletedAt IS NULL`** — mandatory; see "The landing path" above
- Thread through `MessageRepository` / `MessageRepositoryImpl` /
  `FakeMessageRepository`.
- `LIMIT`: **50 for text search, 200 for browse mode.** 50 truncates visibly
  when the filter rather than the query is selecting; 200 is the ceiling that
  keeps the deferred index defensible.
- **Delete `getSharedMedia(chatId)`** — dead code in all three places
  (`MessageDao.kt:92`, `MessageRepository.kt:46`,
  `MessageRepositoryImpl.kt:1249`); `SharedMediaViewModel` maps `getMessages`
  instead. The new query supersedes it, and leaving it strands a third media
  query in the codebase. (Distinct from `getSharedMediaForUser`, which
  `ProfileViewModel` does use — that one stays.)

**Tests:** yes — a Robolectric in-memory Room DAO test, following
`MessageDaoOrphanRecoveryTest.kt`
(`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [29], …)`,
`Room.inMemoryDatabaseBuilder`). **Must include a deleted-image row asserted
absent from a `PHOTOS` browse** — that is the regression test for the bug this
feature would otherwise introduce. Also cover `LINKS` (matches a `TEXT` row, not
a type) and one combined filter (`PHOTOS` + date range).

### 3 — State: `OverlaysState` + `ChatSearchManager`

- Add the active filter to `ChatOverlaysState` alongside `searchQuery`.
- `ChatSearchManager` gains `onFilterChange`. Re-run the search on filter
  change **without** the 300 ms debounce — a chip tap is a discrete action, not
  typing; only text keeps the debounce.
- `clearSearch()` / `toggleSearch()` must reset the filter too.

**Tests:** yes — new `ChatSearchManagerTest`: chip tap re-queries immediately,
typing still debounces, clearing resets both axes.

### 4 — UI: chip row, date picker, filter summary

- `LazyRow` of `FilterChip`s under the search box, in use order:
  **Photos · Videos · Links · Docs · Voice · Starred · Date**.
- Date opens a range picker; the chip's own label reflects the choice
  (`Date` → `Mar 2026 ▾`) so it's self-describing once scrolled to.
- The `"N results"` line becomes the active-filter summary with a clear
  affordance — `42 photos · Mar 2026  ✕`.
- **The count is `LIMIT`-capped**, so it must not be presented as exact:
  render `200+` when the result list is at the browse limit, rather than
  claiming 200 photos exist when there are 2,000.

**Tests:** skip (UI-only). A Robolectric Compose test is optional if the chip
row's selection logic ends up non-trivial.

### 5 — UI: per-type result rendering

- Photos/Videos → thumbnail grid. Docs/Links → icon + filename/URL rows
  (`content` carries the filename for `DOCUMENT` — cf. `ChatListScreen.kt:503`,
  so text search over docs is meaningful). Everything else → the existing text
  rows.
- Grid tiles hand off **per type**: `FullscreenImagePager`
  (`ChatScreen.kt:2138`) for photos, `FullscreenVideoPlayer` (`:2180`) for
  videos. One grid, two destinations — the image pager cannot play video.
- Decide where closing the viewer lands: back in the grid (keeps the browse
  session) rather than in the conversation. `fullscreenMediaMessageId` currently
  drives the land-in-chat behaviour for bubble-opened media; the search-opened
  path must not inherit it blindly.

**Tests:** skip (UI-only).

### 6 — Merge `SharedMediaScreen`

- Point the overflow menu item (`NavGraph.kt:504`) at search pre-filtered to
  Photos; delete `SharedMediaScreen` / `SharedMediaViewModel`, their route, and
  the import at `NavGraph.kt:50`.
- **Do not delete `SharedMediaTile`** (`ui/components/`) — `ProfileScreen` uses
  it (`:605`). Only the chat-scoped screen and its ViewModel go.
- **Carry over `ensureLocalCopiesForChat` on `@ApplicationScope`** —
  `SharedMediaViewModel` fires it there deliberately, so the copies finish when
  the user backs out immediately. **It cannot move into `ChatSearchManager`:**
  `ChatViewModel.kt:158` constructs that manager with `viewModelScope`, so
  homing it there would silently downgrade the guarantee — the exact failure the
  `@ApplicationScope` convention exists to prevent. Fire it from `ChatViewModel`,
  which already injects `appScope` (`:100`), when a media filter first
  activates.
- Check `docs/FEATURE-MAP.md` for both deleted files and update.

**Tests:** skip, but confirm no orphaned route constant in `NavGraph.kt`.

### 7 — The landing path

- `jumpToSourceMessage` → `Boolean`.
- Search-result taps call it instead of bare `scrollToAndCenter`
  (`ChatScreen.kt:1084`), gaining the arrival flash.
- On `false`: keep the overlay open (don't discard the user's results) and
  snackbar "Message no longer available". On `true`: `clearSearch()` as today.
- Comment the call site with fact 2 above, so the paging coupling is discoverable.

**Tests:** yes — regression test that a result whose message is absent from the
loaded list leaves search open rather than dead-tapping.

---

## Per-step close-out

Per `CLAUDE.md`: `./gradlew test` → `./gradlew assembleDebug` → commit
immediately once green, code and tests in one commit. **Steps 1 and 2 close out
together as a single commit** (see step 1); every other step is its own.

`/simplify` is **not** expected to trigger here — this is neither
concurrency-heavy, security-adjacent, nor cross-cutting; run it only if the
diff crosses ~600 lines (steps 4–5 are the candidates).

`/code-review` is worth one manual pass at the end: step 2 rewrites a query
every result path depends on.

## Changelog

User-visible feature → `CHANGELOG.md` entry under `Added` and a **minor** bump
(`feat:`). Land the entry with step 7, when the feature is whole, not per step.
