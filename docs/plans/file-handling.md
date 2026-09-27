# File handling in chat bubbles

Status: shipped (steps 1–7) — pending on-device verification · branch `claude/chat-file-handling-6slm5p`

## Why

A file sent through the attachment sheet's *File* entry arrives as a bubble with a
paperclip and nothing else: no name, no type, no size, and tapping it does nothing.
Two defects sit under that:

1. **The file's identity is never sent.** `ChatScreen`'s `OpenDocument` launcher
   passes only the uri and mime type; `sendMediaMessage` writes the (empty) caption
   as `content`, and `Message` has no field for a name, a size or a type. The type
   survives only as the staged copy's extension, and only on the sender.
2. **A document never gets a local copy, on either device.** `MediaFileManager.downloadAndSave`
   writes every download through `MediaStore.Images` under `Pictures/`, which rejects
   a non-image mime type — so every auto-download, chat-open scan and backfill of a
   DOCUMENT throws and re-queues itself. Firebase Storage also names the object after
   `mimeType.substringAfter("/")` (`.plain`, `.vnd.openxmlformats-…document`), and the
   receiver derives the extension from that URL, falling back to `jpg`.

## Decisions (agreed with the owner, 2026-09-27)

| Question | Decision |
|---|---|
| Tap on a file | Android's *Open with* chooser (`ACTION_VIEW` + `createChooser`) over a `FileProvider` uri; "No app can open this file" when nothing resolves |
| Text files | First 10 lines in the bubble, *Show more* expands in place; the preview reads at most 64 KB and says so when the file is longer |
| PDF | **Both**: page-1 thumbnail with page count (`PdfRenderer`, platform) **and** an expandable text excerpt (PdfBox-Android) |
| Word / Excel / PowerPoint & others | Colour-coded type badge + name + size; no content preview |
| Extras | Send-confirm sheet (name, size, type, caption); size limit (100 MB) + warning before opening risky types (APK, scripts); *Save to Downloads* and *Share…* in the long-press menu; inline player for audio files |

**Privacy note.** The new `fileName` / `fileSize` / `mimeType` fields are plaintext on the
message document, like `mediaUrl`, `duration` and `mediaWidth` already are. The file
bytes themselves are uploaded to Storage unencrypted today, so the name adds little the
server cannot already read. Encrypting media and its metadata is a separate project.

## Where files live

- **Sender**: the input is staged under `filesDir/outbox/` as today. At SENT a DOCUMENT's
  staged copy is *moved* into `filesDir/documents/<id>.<ext>` (a durable file), rather
  than deleted and downloaded back.
- **Receiver**: a DOCUMENT downloads straight into `filesDir/documents/<id>.<ext>`
  with plain file IO; it never touches MediaStore. The extension comes from `fileName`,
  then `mimeType`, then the URL, then `bin`.
- **Sharing out**: `FileProvider` path `documents/`. *Save to Downloads* copies into
  `MediaStore.Downloads` under the original file name.

## Steps

Order: 1 → 2 → 3 → 4 → 5 → 6 → 7

### 1. File metadata end-to-end + document storage fix — model: strong, skills: code-review

- `Message` / `MessageColumns` / `MessageRecord`: `fileName`, `fileSize`, `mimeType`
  (nullable). Bump `AppDatabase` version.
- `RawMessage`, `MessageSource.sendMessage` / `sendPlainMessage`, `MessageWriter`,
  `FirestoreMessageSource` (write + read), `PocketBaseMessageSource` (accepted, ignored
  like `mediaWidth`), `MessageRepositoryImpl.toMessage`.
- `sendMediaMessage` fills the three fields for a DOCUMENT from the picked uri
  (`OpenableColumns.DISPLAY_NAME` / `SIZE`) through a small `DocumentInfoReader`, so the
  share sheet and every other caller get it too.
- `FirebaseStorageSource.uploadMedia`: extension from `MimeTypeMap`, sanitized fallback.
- `MediaFileManager`: documents download into `filesDir/documents/`, not MediaStore.
- `OutboxSender` at SENT: a DOCUMENT's staged copy moves into the documents dir.
- Tests: Firestore map round-trip, extension resolution, the document download route,
  the SENT move, the send filling metadata.

**Shipped** — departures from the step text:
- No separate `DocumentInfoReader`: `DocumentFiles.describe` reads name and size, beside
  the directory and extension rule it belongs with.
- The share sheet passes the name it resolved (`sendMediaMessage(fileName = …)`): its
  cache copy is named by a random id, so `describe` alone would send that id.
- The move into the documents dir happens once the upload is done, before the write;
  a row deleted while queued loses the moved copy in `tombstone` (`DocumentFiles.discard`).
- PocketBase: its Storage source is still a stub, so documents cannot be sent there yet;
  a PB id swap would leave the kept copy named after the client id (harmless, not renamed).

### 2. File card bubble + *Open with*

- `domain/util/FileKind.kt`: classify by mime type then extension → PDF, TEXT, WORD,
  SPREADSHEET, PRESENTATION, ARCHIVE, AUDIO, VIDEO, IMAGE, APK, CODE, OTHER, plus
  human-readable size. Pure, unit-tested.
- `ui/chat/FileMessageBubble.kt`: badge (colour + short label), middle-ellipsized name,
  `PDF · 1.4 MB`, upload progress while sending, a download/spinner state when there is
  no local copy yet; caption below.
- `data/util/FileOpener.kt`: `FileProvider` uri + `ACTION_VIEW` chooser; a download
  first when the local copy is missing (`ChatViewModel.openFile`), with an error on failure.

**Shipped** — departures: the opener is `ChatFileActions` (ui/chat, owns no `ChatUiState`
slice) + `ui/components/FileIntents`, not `data/util/FileOpener` — intents need the Activity.
The APK/script confirm (planned for step 6) landed here with the first Open.

### 3. Text preview

- `FilePreviewLoader` (data/util): reads ≤ 64 KB of a local file on IO, decodes UTF-8
  leniently, strips a BOM, detects binary; an LRU cache by message id.
- The bubble shows 10 lines, *Show more* / *Show less*; the expanded view notes when the
  file was cut at 64 KB.

**Shipped** — departures: the limit is 32 KB, not 64 — a few hundred lines is already more
than a bubble should lay out. The chat reaches `FilePreviewLoader` through a domain
`FilePreviewSource` (bound in `AppModule`) rather than growing the UI→data allowlist.

### 4. PDF preview

- `PdfRenderer` renders page 1 into a PNG in `cacheDir/file_previews/`; page count is
  shown on the badge line. Rendering is bounded by `MediaProcessingLimiter`.
- PdfBox-Android extracts text from the first pages (≤ 64 KB), shown with the same
  expandable text block. Encrypted or damaged PDFs fall back to the plain card.

**Shipped** — PdfBox-Android 2.0.27.0 adds ~4.6 MB of font/CMap assets. Text from the first
3 pages; the thumbnail box is at most 200 dp tall and shows the top of page one.

### 5. Send-confirm sheet + size limit

- Picking a file opens a sheet (badge, name, size, optional caption, *Send*).
- Files over 100 MB are refused in the sheet and in `sendMediaMessage`
  (`MediaLimitException` → `AppError.Validation`).

**Shipped**.

### 6. Save / Share + risky-type warning

- Long-press menu on a file bubble: *Save to Downloads*, *Share…* (`ACTION_SEND`).
- Opening an APK or script file first shows a confirm dialog.

**Shipped** — the confirm dialog shipped with step 2.

### 7. Audio files play inline

- A DOCUMENT whose kind is AUDIO renders the voice-note player over its local file,
  with the file card's name line above it; `duration` is read at send time.

**Shipped** — departure: the player shows only once the file is on the device. The shared
voice player calls `MediaPlayer.prepare()` on the main thread, harmless for a local file and an
ANR risk for a large remote one.
