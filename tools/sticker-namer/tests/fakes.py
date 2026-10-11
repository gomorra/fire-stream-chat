"""Stand-ins for Firestore, Storage, the engine and a sticker file."""

from __future__ import annotations

import hashlib
import io

from PIL import Image

from store import FileTooLarge, Request


def webp(color=(200, 30, 30, 255), size=(64, 48), frames=1) -> bytes:
    """A small WebP picture. With [frames] above 1 it is animated, and only its first frame has [color]."""
    first = Image.new("RGBA", size, color)
    rest = [Image.new("RGBA", size, (0, 0, 255, 255)) for _ in range(frames - 1)]
    out = io.BytesIO()
    if rest:
        first.save(out, format="WEBP", save_all=True, append_images=rest, duration=100, lossless=True)
    else:
        first.save(out, format="WEBP", lossless=True)
    return out.getvalue()


def id_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


GOOD_ANSWER = {
    "name_en": "Red square",
    "name_de": "Rotes Quadrat",
    "words_en": ["red", "square"],
    "words_de": ["rot", "Quadrat"],
    "text": "",
    "emojis": ["🟥"],
}


class FakeRequests:
    """`stickerNames` as a dict of documents."""

    def __init__(self):
        self.documents: dict[str, dict] = {}
        self.writes = 0

    def ask(self, sticker_id: str, format: object = "WEBP", requested_at: object = None) -> None:
        self.documents[sticker_id] = {"status": "pending", "format": format, "requestedAt": requested_at}

    def pending(self) -> list[Request]:
        return [
            Request(sticker_id, document.get("format"), document.get("requestedAt"))
            for sticker_id, document in self.documents.items()
            if document.get("status") == "pending"
        ]

    def status(self, sticker_id: str):
        document = self.documents.get(sticker_id)
        return None if document is None else document.get("status")

    def answer(self, sticker_id: str, fields: dict) -> bool:
        if self.status(sticker_id) != "pending":
            return False
        self.documents[sticker_id].update(fields)
        self.writes += 1
        return True


class FakeFiles:
    """Storage as a dict of objects by name."""

    def __init__(self):
        self.objects: dict[str, bytes] = {}
        self.error: Exception | None = None

    def put(self, data: bytes, sticker_id: str | None = None) -> str:
        """Stores [data] as a WebP sticker under its hash, or under [sticker_id]. Returns the id."""
        sticker_id = sticker_id or id_of(data)
        self.objects[f"stickers/{sticker_id}.webp"] = data
        return sticker_id

    def read(self, name: str, max_bytes: int):
        if self.error is not None:
            raise self.error
        data = self.objects.get(name)
        if data is not None and len(data) > max_bytes:
            raise FileTooLarge(name)
        return data


class FakeEngine:
    """Answers each question with the next entry of [answers]. An exception among them is raised."""

    model = "fake-model"

    def __init__(self, *answers, loaded: bool = False):
        self.answers = list(answers) or [GOOD_ANSWER]
        self.loaded = loaded
        self.pictures: list[bytes] = []

    def ask(self, png: bytes):
        self.pictures.append(png)
        answer = self.answers.pop(0) if len(self.answers) > 1 else self.answers[0]
        if isinstance(answer, Exception):
            raise answer
        return answer

    def is_loaded(self) -> bool:
        return self.loaded
