"""The naming service: answers the requests in `stickerNames`.

A phone asks for a sticker's name by creating `stickerNames/<sticker id>` with
`status: "pending"`. This service reads the sticker's file from Storage, shows
its first frame to a picture model on this PC, and writes the answer into the
same document. It never talks to a phone, and nothing here listens on a port.

Three outcomes for a request:
- `done`: the names are written.
- `skipped`: the sticker cannot be named, and the reason is written. This is
  final. No phone can ask a second time, so it is only for a failure that
  will not pass.
- left `pending`: the failure may pass (the engine is down, no network, the
  file is not uploaded yet). The next round tries again.

A log line holds a sticker's id and the outcome. It never holds what the model
wrote.

Run it with `python namer.py`. `setup.sh` installs it as a systemd user unit.
"""

from __future__ import annotations

import logging
import re
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Callable

import card
import picture
from answer import AnswerError, fields_of
from config import ConfigError, load
from engine import Engine, EngineDown, NoAnswer
from store import PENDING, Files, FileTooLarge, Request, Requests

log = logging.getLogger("sticker-namer")

# The rule in firestore.rules for a request's id.
STICKER_ID = re.compile(r"[0-9a-f]{64}")

# What a request's `format` names, and the file extension in Storage. Lottie stickers are not named.
EXTENSIONS = {"WEBP": "webp"}

# Far above any sticker the app stores. A larger object is not downloaded.
MAX_FILE_BYTES = 2 * 1024 * 1024

# A model sometimes answers with a name that is too long or holds a forbidden word.
ASK_ATTEMPTS = 3

# A file that is not in Storage may still be uploading. After this long it will not come.
FILE_GRACE = timedelta(days=1)

CARD_WAIT_SECONDS = 30
# The wait before a round that left a request pending is tried again. It doubles up to the second value.
RETRY_SECONDS = 60
MAX_RETRY_SECONDS = 1800
# The listener wakes the loop. This is the fallback for a listener that died quietly.
POLL_SECONDS = 600

# sysexits.h EX_CONFIG. The unit does not restart the service after it.
EXIT_CONFIG = 78


class Skip(Exception):
    """The sticker cannot be named. The message is the answer's `reason`."""


class Later(Exception):
    """The request stays pending. The message says why, for the log."""


class Gone(Exception):
    """The request is no longer pending: another namer or the owner answered it."""


class Namer:
    def __init__(
        self,
        requests: Requests,
        files: Files,
        engine: Engine,
        min_free_vram_mb: int,
        free_vram_mb: Callable[[], int | None] = card.free_vram_mb,
        sleep: Callable[[float], None] = time.sleep,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self.requests = requests
        self.files = files
        self.engine = engine
        self.min_free_vram_mb = min_free_vram_mb
        self.free_vram_mb = free_vram_mb
        self.sleep = sleep
        self.now = now

    def run_once(self) -> bool:
        """Works through every pending request. True when one was left pending, or none could be read."""
        try:
            pending = self.requests.pending()
        except Exception as e:
            log.warning("could not read the requests: %s", type(e).__name__)
            return True
        left = False
        for request in pending:
            started = time.monotonic()
            short_id = request.sticker_id[:12]
            try:
                outcome = self.handle(request)
            except EngineDown as e:
                # No other request would fare better. The batch ends here.
                log.warning("engine down, %s stays pending: %s", short_id, e)
                return True
            except Later as e:
                log.info("%s stays pending: %s", short_id, e)
                left = True
                continue
            except Exception as e:
                # No network, or a fault in this service. One request must not end the batch.
                log.warning("%s stays pending after %s", short_id, type(e).__name__)
                left = True
                continue
            log.info("%s %s in %.1f s", short_id, outcome, time.monotonic() - started)
        return left

    def handle(self, request: Request) -> str:
        """Answers one request and says how: `done`, `skipped: <reason>` or `answered elsewhere`."""
        try:
            fields = {"status": "done", **self._answer_fields(request), "model": self.engine.model}
            outcome = "done"
        except Skip as e:
            fields = {"status": "skipped", "reason": str(e)}
            outcome = f"skipped: {e}"
        except Gone:
            return "answered elsewhere"
        written = self.requests.answer(request.sticker_id, fields)
        return outcome if written else "answered elsewhere"

    def _answer_fields(self, request: Request) -> dict:
        sticker_id = request.sticker_id
        # Both come from a client. The id becomes a part of an object's name.
        if not STICKER_ID.fullmatch(sticker_id):
            raise Skip("bad id")
        extension = EXTENSIONS.get(request.format) if isinstance(request.format, str) else None
        if extension is None:
            raise Skip("format not named")

        try:
            data = self.files.read(f"stickers/{sticker_id}.{extension}", MAX_FILE_BYTES)
        except FileTooLarge:
            raise Skip("file too large") from None
        if data is None:
            if self._is_older_than_grace(request):
                raise Skip("no file")
            raise Later("no file yet")
        try:
            picture.check_hash(data, sticker_id)
            png = picture.png_of(data)
        except picture.PictureError as e:
            raise Skip(str(e)) from None

        if self._wait_for_card() and self.requests.status(sticker_id) != PENDING:
            raise Gone()

        refusal: AnswerError | None = None
        for _ in range(ASK_ATTEMPTS):
            try:
                return fields_of(self.engine.ask(png))
            except AnswerError as e:
                log.info("%s: asking again, %s", sticker_id[:12], e)
                refusal = e
        if isinstance(refusal, NoAnswer):
            # A wrong MODEL or an engine that misbehaves. A final answer here would
            # close the whole library to naming for a fault in the settings.
            raise Later("the engine gave no answer")
        raise Skip("no usable answer")

    def _is_older_than_grace(self, request: Request) -> bool:
        asked = request.requested_at
        # A request without a time was not written by the app. It gets no grace.
        if not isinstance(asked, datetime):
            return True
        if asked.tzinfo is None:
            asked = asked.replace(tzinfo=timezone.utc)
        return self.now() - asked > FILE_GRACE

    def _wait_for_card(self) -> bool:
        """Waits until the card has room for the model. True when it had to wait.

        A model that is loaded fills the card itself, so the card is only
        looked at while the model is not loaded. Without `nvidia-smi` there is
        nothing to look at, and the request goes ahead.
        """
        if self.min_free_vram_mb <= 0 or self.engine.is_loaded():
            return False
        waited = False
        while True:
            free = self.free_vram_mb()
            if free is None:
                log.warning("nvidia-smi gave no answer. Not waiting for the graphics card")
                return waited
            if free >= self.min_free_vram_mb:
                return waited
            if not waited:
                log.info("the graphics card is busy (%d MiB free). Waiting", free)
            waited = True
            self.sleep(CARD_WAIT_SECONDS)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        config = load()
    except ConfigError as e:
        log.error("%s", e)
        return EXIT_CONFIG

    # Imported here, so the tests need no firebase-admin.
    from firebase_store import FirebaseStore

    store = FirebaseStore(config)
    engine = Engine(config.engine, config.engine_url, config.model, config.keep_alive)
    namer = Namer(store, store, engine, config.min_free_vram_mb)

    wake = threading.Event()
    store.listen(wake.set)
    log.info("naming with %s on %s", config.model, config.engine)

    # No signal is handled. Every answer is one transaction, so a stop in the
    # middle of a batch leaves the rest pending for the next start.
    retry_seconds = RETRY_SECONDS
    while True:
        # Cleared before the round, so a request that arrives during it is not lost.
        wake.clear()
        if namer.run_once():
            # Every round reads each pending document again, and Firestore bills each read.
            # So the wait grows while nothing changes. A new request still ends it at once.
            wake.wait(retry_seconds)
            retry_seconds = min(retry_seconds * 2, MAX_RETRY_SECONDS)
        else:
            retry_seconds = RETRY_SECONDS
            wake.wait(POLL_SECONDS)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
