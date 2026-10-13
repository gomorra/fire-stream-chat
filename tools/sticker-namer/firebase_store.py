"""Firestore and Storage, as `namer.py` sees them.

The only file that imports `firebase-admin`. The Admin SDK is not bound by
`firestore.rules`, so what limits this service is the role of its service
account: read Storage objects, read and write Firestore documents.
"""

from __future__ import annotations

from typing import Callable

import firebase_admin
from firebase_admin import credentials, firestore, storage
from google.auth.exceptions import TransportError
from google.cloud import firestore as cloud_firestore
from google.cloud.firestore_v1.base_query import FieldFilter
from google.oauth2 import service_account

from config import Config
from retry import retrying
from store import PENDING, FileTooLarge, Request

COLLECTION = "stickerNames"


class _Credentials(service_account.Credentials):
    """The service account, with a token request that survives a dead connection.

    The access token runs out during the PC's standby. The request for a new
    one then goes out on a connection that died in the meantime. gRPC logs that
    failure with its whole traceback, so it is tried again here.
    """

    def refresh(self, request):
        retrying(lambda: super(_Credentials, self).refresh(request), TransportError)


class _Certificate(credentials.Certificate):
    """A key file whose credential is a [_Credentials]."""

    def __init__(self, key_file: str):
        super().__init__(key_file)
        plain = self._g_credential
        # A copy keeps the class, so the scoped copies the clients make retry too.
        self._g_credential = _Credentials.from_service_account_file(key_file, scopes=plain.scopes)


class FirebaseStore:
    """Implements both `store.Requests` and `store.Files`."""

    def __init__(self, config: Config):
        app = firebase_admin.initialize_app(
            _Certificate(str(config.key_file)), {"storageBucket": config.bucket}
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
