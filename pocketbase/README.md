# PocketBase backend (self-host variant)

This directory holds the schema and JS hooks for the **`pocketbase`** Gradle
flavor of the Android app. PocketBase is the self-hostable alternative to the
default Firebase backend.

> **Status:** v0 walking skeleton — login, 1:1 text messaging, presence, push.
> Calls / lists / polls / Signal encryption are stubbed and will surface
> `NotImplementedError` at runtime in the UI. Tracked in `TECH_DEBT.md`.

## Prerequisites

- PocketBase **v0.22+** (older versions lack the `cronAdd` JS hook + realtime
  collection filters this code relies on).
- A Firebase project (we still use Firebase **Phone OTP** for login and **FCM**
  for push delivery — only the data layer is self-hosted).
- The Firebase project's FCM access token (see *FCM access token rotation*
  below — PB hooks can't sign RS256, so tokens are minted out-of-band by
  `gcloud auth print-access-token` and exported as `FCM_ACCESS_TOKEN`).

## One-time setup

```bash
# 1. Download the binary (Linux x86_64 example; pick the build for your OS)
curl -L https://github.com/pocketbase/pocketbase/releases/download/v0.22.21/pocketbase_0.22.21_linux_amd64.zip \
  -o pocketbase.zip
unzip pocketbase.zip pocketbase
rm pocketbase.zip

# 2. Boot once so PocketBase creates pb_data/ and prompts for the admin user
./pocketbase serve

# (visit the printed admin URL, e.g. http://127.0.0.1:8090/_/, create the admin
#  account, then Ctrl-C)

# 3. Import the v0 schema
./pocketbase import collections pb_schema.json
```

## Running

```bash
# Set environment variables, then start
export FIREBASE_PROJECT_ID="your-firebase-project-id"
export FCM_PROJECT_ID="your-firebase-project-id"   # usually the same

# FCM access token — see "FCM access token rotation" below for why this
# isn't read from the service-account JSON directly.
export FCM_ACCESS_TOKEN="$(gcloud auth print-access-token)"

./pocketbase serve --http=0.0.0.0:8090
```

### FCM access token rotation

The push hook (`push_on_message.pb.js`) posts to FCM HTTP v1, which requires
an OAuth2 access token. The standard way to mint one is signing a JWT with
the service-account private key (RS256), but **PocketBase v0.22's Goja
runtime only ships HMAC JWT signing** — there is no RSA signer, no Node
modules, and no shell-out. Mint tokens out-of-band instead:

```bash
# One-shot
export FCM_ACCESS_TOKEN="$(gcloud auth print-access-token)"

# Or refresh on a 50-minute cron (token TTL is 1 h)
*/50 * * * * pgrep pocketbase >/dev/null && \
    systemctl --user set-environment FCM_ACCESS_TOKEN=$(gcloud auth print-access-token)
```

If `FCM_ACCESS_TOKEN` is unset or expired, the hook **logs and skips the
push** — it does not 500 the message-create request, so chat messages still
land for live SSE subscribers. Automating refresh inside PocketBase
(sidecar / Go plugin) is tracked in `TECH_DEBT.md`.

`--http=0.0.0.0:8090` makes the server reachable from your phone over LAN. If
you only need the emulator, `127.0.0.1:8090` is fine.

## Connecting from the Android app

The pocketbase flavor reads its server URL from `BuildConfig.POCKETBASE_URL`,
configured in `app/build.gradle.kts`. Default is `http://10.0.2.2:8090`
(emulator's loopback to the host).

For a physical device on the same Wi-Fi, find your dev PC's LAN IP
(`ip -4 addr show` on Linux) and rebuild with the override:

```bash
./gradlew assemblePocketbaseDebug -PpocketbaseUrl=http://192.168.x.x:8090
```

You can also set `pocketbaseUrl=http://192.168.x.x:8090` in `local.properties`
to make the override sticky across builds.

## Smoke test

After the server is running and the app is installed via
`./gradlew installPocketbaseDebug`, watch logcat in another terminal:

```bash
adb logcat -c && adb logcat | grep -E "PbAuth|PbPresence|PbRealtime|PbClient"
```

Then run through the v0 surface in order. Each row is something v0 actually
implements; deferred surface (calls, polls, lists, groups, encryption,
reactions, edits, typing, unread counters) surfaces a `NotImplementedError`
snackbar in the UI and is tracked in `TECH_DEBT.md`.

| # | Action | What you should see |
|---|--------|---------------------|
| 1 | Phone OTP sign-in | `PbAuth` line — POST to `/api/auth/firebase-bridge` returns a PB session token; row appears in PB admin → `users` |
| 2 | App in foreground | `PbRealtime` connect — single SSE stream open; reconnects within seconds if you toggle airplane mode |
| 3 | Open a chat with another phone-number you've added | `users` row for the peer; `chats` row of `type=INDIVIDUAL` if it's a fresh conversation |
| 4 | Send a text | New `messages` row in PB admin; chat's `last_message_*` denormalized fields update |
| 5 | Send a text from a second device → first device receives it live | Receiver's `PbRealtime` logs a `messages` event; bubble appears without a refresh |
| 6 | Background the app for ~70 s, then re-foreground | `presence` row flips to `is_online=false` after the cron sweeps (every 30 s, 60 s threshold); flips back to `true` on resume |
| 7 | With `FCM_ACCESS_TOKEN` set: kill the app, send a message from device 2 | Device 1 gets a system notification; PB server log shows the FCM POST returning 200 |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| App can't reach the server | Emulator pointing at localhost instead of `10.0.2.2` | Use `installPocketbaseDebug` (default) or override `pocketbaseUrl` for a LAN host |
| Sign-in returns 401 | `FIREBASE_PROJECT_ID` env var missing on the server | Export it and restart `pocketbase serve` |
| Push works for a while then stops | `FCM_ACCESS_TOKEN` expired (1 h TTL) | Re-export from `gcloud auth print-access-token`; or set up the 50-min cron above |
| `users` row created but app shows "complete profile" loop | `name` field is empty (intentional — the bridge creates the row, profile-setup populates `name`) | Complete the profile-setup screen; subsequent logins skip it |
| Online indicator stuck on a peer | PC hibernated mid-test (stale snapshot) | Reconnect and wait for the next 30 s cron tick |
| Build fails in `mergeFirebaseReleaseNativeLibs` with `Input/output error` | Corrupted `.so` in the Gradle transform cache | `find ~/.gradle/caches -name '*.so' -exec cat {} >/dev/null \;` to find it; remove the parent transform dir; retry |

## Directory layout

```
pocketbase/
├── README.md                       # this file
├── .gitignore                      # binary, pb_data/, key file
├── pb_schema.json                  # v0 collections (users/chats/messages/presence)
└── pb_hooks/
    ├── firebase_bridge.pb.js       # POST /api/auth/firebase-bridge — exchanges
    │                               # Firebase ID token for a PB session
    ├── push_on_message.pb.js       # onRecordAfterCreateRequest("messages") —
    │                               # posts to FCM HTTP v1
    └── presence_sweeper.pb.js      # cron — marks stale heartbeats offline
```
