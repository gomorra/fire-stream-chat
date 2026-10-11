"""What the namer needs from Firestore and Storage.

`firebase_store.py` implements both interfaces for the real backend. The tests
implement them with fakes.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

# The `status` of a request that waits for its answer.
PENDING = "pending"


@dataclass(frozen=True)
class Request:
    """One pending document of `stickerNames`. `format` and `requested_at` are as a client wrote them."""

    sticker_id: str
    format: object
    requested_at: object


class FileTooLarge(Exception):
    """The object in Storage is larger than the caller allows."""


class Requests(Protocol):
    """The `stickerNames` collection."""

    def pending(self) -> list[Request]:
        """Every request that waits for an answer."""

    def status(self, sticker_id: str) -> str | None:
        """The document's `status`, or `None` when it is gone."""

    def answer(self, sticker_id: str, fields: dict) -> bool:
        """Writes [fields] into the document if it is still pending. False when it was not."""


class Files(Protocol):
    """The sticker files in Storage."""

    def read(self, name: str, max_bytes: int) -> bytes | None:
        """The object's bytes, `None` when there is none, [FileTooLarge] above [max_bytes]."""
