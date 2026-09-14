# Global search from the chat list

**Goal** — the search the chat pane already has (filter chips, browse mode, honest
counts, per-type result rendering) becomes available across *all* chats, reached from
the chat list's magnifier.

**Order:** 1 → 2 → 3 → 4 → 5 → 6 → 7

---

## What exists today

- **In-chat search** is rich: `MessageSearchFilter` (type / starred / date range),
  browse mode, `SearchResultList` renders photos & videos as a grid, docs/links as icon
  rows, everything else as text rows; `searchResultsSummary` reports a capped count as
  `200+`. Owned by `ChatSearchManager` on the overlays slice.
- **Global search already exists but is a stub**: `ChatListViewModel.onSearchQueryChange`
  → `SearchMessagesUseCase(query)` → an M3 `SearchBar` panel inside `ChatListScreen`
  with a private `SearchResultItem` row. It has no filters, no sender or chat label, no
  truncation reporting, and tapping a hit opens the chat **at the bottom** rather than at
  the message.
- Two DAO queries: `searchMessagesInChat` (filters, `deletedAt IS NULL`) and
  `searchMessages` (no filters, **no tombstone guard**).

## Decisions

- **A NavHost destination, not a pager overlay.** `ChatListScreen` is a page inside
  `MainScreen`'s `HorizontalPager`; the chip row is a `LazyRow`, and hosting it in a
  pager page is exactly the gesture conflict `SearchFilterChipRow`'s KDoc says it does
  not have. A `Routes.SEARCH` destination keeps that true, gets system back for free,
  and matches how every comparable app does global search.
- **The stub is replaced, not kept alongside.** Two global searches with different
  capabilities is worse than one. The `SearchBar` block, `SearchResultItem`, and the
  four search fields on `ChatListUiState` go.
- **One repository method, one DAO query.** `searchMessages(chatId: String?, …)` with
  `(:chatId IS NULL OR chatId = :chatId)`. There is no index on `messages.chatId` (the
  `(chatId, timestamp)` index is deliberately deferred), so the added clause costs no
  query plan. It also hands global search the `deletedAt IS NULL` guard that only the
  in-chat query had — load-bearing the moment global gains a Photos chip, since a
  tombstone keeps its `mediaUrl` and a filter-only query has no content predicate to
  hide it.
- **A global result row must say *where*.** Label = `"Alice · Weekend Trip"`, collapsing
  to just the chat title when the sender's name would repeat it (1:1) — resolved from
  the chats + contacts maps the view model already needs.
- **Tapping any global result jumps to the message**, via the existing
  `Routes.chat(…, targetMessageId = …)` that reminders already use. Including media
  tiles: a cross-chat fullscreen pager would need gallery args spanning chats, and
  landing in the conversation is the more useful answer for a global hit anyway.
  (In-chat media tiles keep opening the fullscreen viewer — unchanged.)

---

## 1. Domain + data: one scoped, filterable search

- `MessageSearchLimits.forQuery(query, global = false)` — `BROWSE` (200) for a blank
  query either way, `GLOBAL` (100) for global text, `TEXT` (50) for in-chat text.
  Rewrite the KDoc that says global "has no filter axis and so no browse mode".
- `MessageRepository`: collapse `searchMessages(query)` + `searchMessagesInChat(chatId,
  query, filter)` into `searchMessages(chatId: String?, query, filter)`.
- `MessageDao`: collapse both `@Query`s into one, adding `(:chatId IS NULL OR chatId =
  :chatId)`; drop the old unguarded global query.
- `MessageRepositoryImpl`: one implementation (browse-mode short-circuit, word-boundary
  pass, truncation off the raw row count — all unchanged, just no longer duplicated).
- `SearchMessagesUseCase`: one blank-query guard for both scopes (`blank && !filter
  .isActive` → `EMPTY`), filter forwarded regardless of scope. Public signature
  `(query, chatId = null, filter = NONE)` is unchanged.

## 2. Move the shared search UI to `ui/search/`

Both files now serve two features, so they stop living in `ui/chat/`:

- `ui/chat/ChatSearchResults.kt` → `ui/search/SearchResults.kt`
- `ui/chat/ChatSearchFilterBar.kt` → `ui/search/SearchFilterBar.kt`
- `test/…/ui/chat/ChatSearchFilterFormatTest.kt` → `ui/search/SearchFilterFormatTest.kt`

Composable names are unchanged (`SearchResultList`, `SearchFilterChipRow`,
`SearchResultsSummary`, `searchResultsSummary`); `internal` reaches across packages in
the same module, so `ChatScreen` only gains imports. De-chat the KDoc wording.

`SearchResultList`'s `senderName: (Message) -> String` becomes `resultLabel:
(Message) -> String` — in-chat it stays the sender's name, globally it is
sender + chat.

## 3. `ui/search/GlobalSearchViewModel`

- State: `query`, `filter`, `results`, `truncated`, `chats: Map<String, Chat>`,
  `contacts: Map<String, Contact>`, `currentUserId`, plus `isSelecting` (query or
  filter — below it the pane shows a hint, not "no results").
- Debounce mirrors `ChatSearchManager`: 300 ms on typing, immediate on a chip tap, one
  `searchJob` cancelled on each re-issue, `CancellationException` rethrown so a
  superseded job cannot blank the results its successor is about to write.
- `GlobalSearchLabels.kt` — pure, unit-tested: `Chat?.displayTitle(contacts,
  currentUserId)` (mirrors `ChatListItem`'s resolution) and `globalResultLabel(message,
  chat, contacts, currentUserId)`.
- `recipientIdFor(chatId)` so a result tap can build the chat route.

## 4. `ui/search/GlobalSearchScreen`

Top bar: back arrow + borderless text field, auto-focused with the keyboard up on
entry. Below it `SearchFilterChipRow`, then one of:

- nothing selected → "Search your messages" hint;
- selected, no hits → "No messages found" / "Nothing matches these filters" (browse
  mode has no query to have found nothing *for*), taking the whole pane;
- hits → `SearchResultsSummary` + `SearchResultList`.

## 5. Wire it up, delete the stub

- `Routes.SEARCH = "search"` + a `composable(Routes.SEARCH)` whose result callback
  navigates `Routes.chat(chatId, recipientId, targetMessageId = messageId)`.
- `ChatListScreen` gains `onSearchClick`; the magnifier navigates instead of expanding a
  bar. Remove the `SearchBar` block, `SearchResultItem`, and now-dead imports.
- `MainScreen` threads `onSearchClick` through.
- `ChatListViewModel`: drop `searchQuery` / `searchResults` / `isSearchActive` /
  `isSearchBarVisible`, `onSearchQueryChange`, `toggleSearchBar`, `clearSearch`,
  `searchJob`, and the `SearchMessagesUseCase` dependency.

## 6. Tests

| Test | Covers |
|---|---|
| `GlobalSearchLabelTest` (new) | 1:1 says the name once; group says `sender · chat`; own messages say "You"; unknown sender falls back to the chat title |
| `GlobalSearchViewModelTest` (new) | typing debounces and the last query wins; a chip tap queries immediately; blank + no filter clears without a round trip; browse mode (blank + chip) *does* query; a thrown search leaves empty results, not a crash |
| `MessageDaoSearchFilterTest` | + a global (null `chatId`) case spanning two chats, and that a soft-deleted image stays out of a global Photos browse |
| `MessageRepositorySearchTruncationTest` | + global text caps at `GLOBAL`, global browse at `BROWSE` |
| `SearchMessagesUseCaseTest` | + global forwards its filter; blank + active filter is browse, not `EMPTY` |
| `FakeMessageRepository` | merge the two overrides into the scoped one (global path gains the tombstone + filter behaviour it now really has) |
| `ChatListViewModelTest` | drop the search test and the `searchMessagesUseCase` constructor arg |

Then `./gradlew test` + `./gradlew assembleDebug`. `/simplify` triggers on (c)
cross-cutting — DAO + repo + use case + two view models + nav — so it runs, and if it
changes anything the two gates re-run before the commit.

## 7. Docs

- `CHANGELOG.md` — one `Added` entry (global search with filters) and one `Fixed`
  (global results now name the chat and land on the message); `feat:` → minor bump via
  the `changelog-release` skill.
- `docs/ARCHITECTURE.md` §12 — new `ui/search/` package, `ChatSearch*` off the
  `ui/chat/` line; nav diagram gains ChatList → Search.
- `docs/FEATURE-MAP.md` — repoint the two moved rows, add the new files.
- `docs/BACKLOG.md` — the existing "Chat search prefilter chips" verification block
  gains the global surface (chips under a pager-free destination, jump-to-message from a
  global hit, keyboard-on-entry).
- `MEMORY.md` — update the chat-search entry.

## Risks

- **Room + nullable `:chatId`.** Compile-time verified, same shape as the existing
  `:type IS NULL` clauses, but it is the one thing that fails at build rather than at
  review — step 1 ends with `assembleDebug`, not just `test`.
- **`ChatListUiState` field removal** ripples into `ChatListViewModelTest` and any
  Robolectric list test; grep before deleting.
- **No `MessageSearchFilter` persistence across the destination's lifecycle** — a
  process death mid-search loses the chips. Acceptable (search is transient); noted here
  so it is a decision rather than an oversight.
