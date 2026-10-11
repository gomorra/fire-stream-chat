"""Firestore and Storage, as `namer.py` sees them.

The only file that imports `firebase-admin`. The Admin SDK is not bound by
`firestore.rules`, so what limits this service is the role of its service
account: read Storage objects, read and write Firestore documents.
"""

from __future__ import annotations

from typing import Callable

import firebase_admin
from firebase_admin import credentials, firestore, storage
from google.cloud import firestore as cloud_firestore
from google.cloud.firestore_v1.base_query import FieldFilter

from config import Config
from store import PENDING, FileTooLarge, Request

COLLECTION = "stickerNames"


class FirebaseStore:
    """Implements both `store.Requests` and `store.Files`."""

    def __init__(self, config: Config):
        app = firebase_admin.initialize_app(
            credentials.Certificate(str(config.key_file)), {"storageBucket": config.bucket}
        )
        self._db = firestore.client(app)
        self._bucket = storage.bucket(app=app)
        self._names = self._db.collection(COLLECTION)
        self._watch = None

    def _pending_query(self):
        return self._names.where(filter=FieldFilter("status", "==", PENDING))

    def pending(self) -> list[Request]:
        requests = []
        for snapshot in self._pending_query().stream():
            data = snapshot.to_dict() or {}
            requests.append(Request(snapshot.id, data.get("format"), data.get("requestedAt")))
        return requests

    def status(self, sticker_id: str) -> str | None:
        snapshot = self._names.document(sticker_id).get()
        return (snapshot.to_dict() or {}).get("status") if snapshot.exists else None

    def answer(self, sticker_id: str, fields: dict) -> bool:
        reference = self._names.document(sticker_id)
        if fields.get("status") == "done":
            fields = {**fields, "namedAt": cloud_firestore.SERVER_TIMESTAMP}

        @cloud_firestore.transactional
        def write_if_pending(transaction) -> bool:
            snapshot = reference.get(transaction=transaction)
            if not snapshot.exists or (snapshot.to_dict() or {}).get("status") != PENDING:
                return False
            # An update keeps `format` and `requestedAt`.
            transaction.update(reference, fields)
            return True

        return write_if_pending(self._db.transaction())

    def read(self, name: str, max_bytes: int) -> bytes | None:
        blob = self._bucket.get_blob(name)
        if blob is None:
            return None
        if blob.size is None or blob.size > max_bytes:
            raise FileTooLarge(name)
        return blob.download_as_bytes()

    def listen(self, wake: Callable[[], None]) -> None:
        """Calls [wake] whenever the set of pending requests changes."""
        self._watch = self._pending_query().on_snapshot(lambda *_: wake())
