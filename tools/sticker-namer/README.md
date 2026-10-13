# Sticker naming service

Gives stickers their automatic names. It runs on the PC of the person who runs the server, with a
picture model on that PC. No sticker goes to a third party, and naming costs no tokens.

It is not part of the app's Gradle build or of the CI gate.

## What it does

A phone asks for a name by creating the Firestore document `stickerNames/<sticker id>` with
`status: "pending"` (`docs/SCHEMA-FIRESTORE.md`). The service answers each request:

1. It reads `stickers/<id>.webp` from Storage. A file whose SHA-256 is not its id is refused.
2. It turns the first frame into a PNG on white, at most 512 px.
3. It waits while the graphics card has less free memory than `MIN_FREE_VRAM_MB`. It only looks at
   the card while the model is not loaded, because a loaded model fills the card itself.
4. It asks the model for an English name, a German name, search words in both languages, the text
   on the sticker and up to three emojis. The answer is bound to a JSON schema.
5. It checks the answer against the app's limits (`StickerNameAnswer` in the app, `answer.py`
   here) and writes it with `status: "done"`.

A request has one of three ends:

| End | When | What a phone sees |
|---|---|---|
| `done` | The model named the sticker | The names, at the phone's next look |
| `skipped`, with a `reason` | The sticker cannot be named: not a WebP, a hash mismatch, no file after a day, three answers in a row that break a limit | Nothing. The sticker is not asked for again |
| still `pending` | The failure may pass: the engine is down or refuses the request (a `MODEL` it does not have), no network, the file is not uploaded yet | Nothing yet. The service tries again after a minute, then at growing intervals up to half an hour. A new request ends the wait at once |

`skipped` is final for the phones. `firestore.rules` lets no phone write over a document. To have
a sticker named after all, delete its document in the Firestore console. A phone asks again within
a day.

The service never talks to a phone and listens on no port. It writes only through the transaction
in `firebase_store.py`, which writes only while the document is still pending. Two namers at once,
or a document changed by hand, therefore lose nothing.

A log line holds the first 12 characters of a sticker's id and the outcome. It never holds what
the model wrote.

## Set it up

```bash
bash tools/sticker-namer/setup.sh
```

The wizard walks through what only the server's owner can do: the virtual environment and the
tests, the Storage bucket, a service account with its key, the engine and the model, the systemd
unit, and one sticker named end to end. It can be run again. It keeps what is already set.

A wrong `MODEL` skips nothing. The engine refuses each request, the log says so, and the requests
wait until the setting is right.

Deploy `firestore.rules` before the last stage. Without the rules, every request of a phone is
refused.

## Settings

`~/.config/firestream-sticker-namer/env`, one `KEY=value` per line. A variable of the process wins
over the file. No setting and no key is in the repository.

| Setting | Default | Meaning |
|---|---|---|
| `KEY_FILE` | none, required | The service account's key. The service refuses a key file that group or others can read |
| `BUCKET` | none, required | The Storage bucket, without `gs://` |
| `ENGINE` | `ollama` | `ollama` for Ollama's `/api/chat`, `openai` for the `/v1/chat/completions` of llama.cpp's server |
| `ENGINE_URL` | `http://127.0.0.1:11434` | Where the engine listens. Keep it on this PC: the pictures go there |
| `MODEL` | `gemma4:26b` | The model's name as the engine knows it. `gemma4:12b` when the PC is too busy |
| `MIN_FREE_VRAM_MB` | `10000` | Free graphics memory the service waits for before the model is loaded. `0` never waits |
| `KEEP_ALIVE` | `2m` | Ollama only: how long the model stays on the card after a request |

After a change: `systemctl --user restart sticker-namer`.

## The key

The key belongs to a service account with two roles: *Cloud Datastore User* (read and write
Firestore documents) and *Storage Object Viewer* (read Storage objects). It can read every user's
files, so:

- it lives on this PC only, at `~/.config/firestream-sticker-namer/key.json`, mode `600`;
- it is never the key from the Firebase console's *Generate new private key*, which may do
  everything in the project;
- `.gitignore` in this folder refuses any `.json` and any `env` file, in case one is copied here.

To take the key back, delete it under the service account's *Keys* tab in the Google Cloud console.

## Run and watch

```bash
systemctl --user status sticker-namer
journalctl --user -u sticker-namer -f
systemctl --user restart sticker-namer
```

Switch it off and on:

```bash
systemctl --user stop sticker-namer       # off until the next login
systemctl --user disable --now sticker-namer   # off, and it stays off
systemctl --user enable --now sticker-namer    # on, and it starts with your session
```

While it is off, the phones' requests wait in Firestore. They are answered when it is on again.

The service stops when you log out. `loginctl enable-linger $USER` keeps it running without a
session, and `loginctl disable-linger $USER` takes that back.

By hand, without the unit: `.venv/bin/python namer.py`.

The unit restarts the service after a failure. It does not restart after exit status 78, which is
a missing or wrong setting. A systemd user unit cannot wait for the system's network target, so the
service treats a missing network as a failure that passes.

## Choose the model

```bash
.venv/bin/python evaluate.py ~/stickers-to-test --models gemma4:26b,gemma4:12b
```

`evaluate.py` runs every `.webp` file of a folder through each model and prints the names side by
side, with the seconds per sticker. It asks the service's own question and applies its own check.
It needs no key and touches neither Firestore nor Storage. Set `MODEL` in the env file to the model
you pick.

For llama.cpp's server: `--engine openai --engine-url http://127.0.0.1:8080 --models <name>`.

## Tests

```bash
python -m pytest tools/sticker-namer
```

The tests need `Pillow` and `pytest` (`requirements-dev.txt`). They use a fake engine and fake
Firestore and Storage clients, so they need no key, no network and no graphics card.
`.github/workflows/sticker-namer.yml` runs them when a file of this folder changes.

`firebase_store.py` is the one file no test runs. It is the thin layer over `firebase-admin`, and
the last stage of `setup.sh` is its check.

## Files

| File | Holds |
|---|---|
| `namer.py` | One request from start to answer, and the loop. The listener only wakes the loop, which reads the pending requests itself |
| `store.py` | What the namer needs from Firestore and Storage, as two interfaces |
| `firebase_store.py` | Those interfaces over `firebase-admin`. The transaction that writes an answer only while the document is pending |
| `retry.py` | Tries a call again after a failure that passes. `firebase_store.py` uses it for the access token request, which fails once after standby |
| `answer.py` | The question, the JSON schema, and the check of what the model wrote |
| `picture.py` | The hash check, and a WebP's first frame as a PNG |
| `engine.py` | Both engine clients behind `Engine.ask`, and whether the model is loaded |
| `card.py` | The card's free memory, from `nvidia-smi` |
| `config.py` | The env file, the defaults, and the check of the key file's mode |
| `evaluate.py` | The model test |
| `sticker-namer.service` | The systemd user unit, with `@TOOL_DIR@` for the folder |
| `setup.sh` | The setup wizard, made with the `wizard` skill. Its part above the `STAGES` marker is the skill's library and is not edited by hand |
