# Call Log Feature — Implementation Plan

## Goal

Show a call history list in the Calls tab (`CallsScreen.kt`). Each row:
- Contact avatar + name
- Direction icon (outgoing ↗ / incoming ↙ / missed ↘ in red)
- Duration or "Missed" / "No answer"
- Relative timestamp
- Callback phone button

Data source: query `MessageType.CALL` messages from Room (already synced from Firestore). No new Firestore collection needed.

## Architecture Overview

```
MessageDao.getCallMessages()
  → MessageRepositoryImpl.getCallLog(): Flow<List<Message>>
  → GetCallLogUseCase
  → CallsViewModel (combines with chats + users → List<CallLogEntry>)
  → CallsScreen (LazyColumn of CallLogRow)
```

## Steps

| Step | Task | Model | Effort |
|------|------|-------|--------|
| 1 | Room DAO `getCallMessages()` | Sonnet | Low |
| 2 | Repository interface + impl `getCallLog()` | Sonnet | Low |
| 3 | `GetCallLogUseCase` + test | Sonnet | Low |
| 4 | `CallLogEntry` domain model | Sonnet | Low |
| 5 | `CallsViewModel` | Sonnet | High |
| 6 | `CallsViewModelTest` | Sonnet | Medium |
| 7 | `CallsScreen` UI | Sonnet | Medium |
| 8 | `MainScreen` / `NavGraph` wiring | Sonnet | Low |

## Files

### New files
- `domain/model/CallLogEntry.kt`
- `domain/usecase/call/GetCallLogUseCase.kt`
- `ui/calls/CallsViewModel.kt`
- `test/.../usecase/call/GetCallLogUseCaseTest.kt`
- `test/.../ui/calls/CallsViewModelTest.kt`

### Modified files
- `data/local/dao/MessageDao.kt` — add `getCallMessages()`
- `domain/repository/MessageRepository.kt` — add `getCallLog()`
- `data/repository/MessageRepositoryImpl.kt` — implement `getCallLog()`
- `ui/calls/CallsScreen.kt` — full replacement
- `ui/main/MainScreen.kt` — add `onCallClick` param
- `navigation/NavGraph.kt` — wire `onCallClick`

## Direction Derivation

```
senderId == currentUserId → OUTGOING
senderId != currentUserId AND content in {hangup, remote_hangup} → INCOMING
senderId != currentUserId AND content in {declined, timeout, error} → MISSED
```

## Key Notes

- CALL messages are never Signal-encrypted (stored plaintext via `sendCallMessage()`)
- `otherPartyId` = the chat participant who is not `currentUserId`
- Do NOT import `ChatUtils.formatTimestamp` (internal) — add local timestamp function
- Callback button: match `ChatScreen` TopAppBar phone button mechanism exactly
