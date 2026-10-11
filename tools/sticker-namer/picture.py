"""A sticker file to the picture the model sees.

The file comes from Storage, where any signed-in user can put one. Storage
cannot check that a file matches its name, so the hash is checked here, and
the file is opened as a WebP and nothing else.
"""

from __future__ import annotations

import hashlib
import io

from PIL import Image

# The model's picture: the longer side, in pixels.
MAX_SIDE = 512

# The app stores no sticker above 512 px. A header that claims far more is refused before a pixel is decoded.
MAX_PIXELS = 2048 * 2048


class PictureError(Exception):
    """The file is no sticker this service names. The message is the `reason` of the answer."""


def check_hash(data: bytes, sticker_id: str) -> None:
    """Refuses a file whose SHA-256 is not the id it was stored under."""
    if hashlib.sha256(data).hexdigest() != sticker_id:
        raise PictureError("hash mismatch")


def png_of(data: bytes) -> bytes:
    """The first frame of the WebP [data] on white, at most [MAX_SIDE] px, as a PNG."""
    try:
        with Image.open(io.BytesIO(data), formats=["WEBP"]) as image:
            width, height = image.size
            if width * height > MAX_PIXELS:
                raise PictureError("picture too large")
            image.seek(0)
            frame = image.convert("RGBA")
    except PictureError:
        raise
    except Exception:
        # Pillow raises many types for a broken file. None of them will pass on a second try.
        raise PictureError("not a WebP picture") from None
    canvas = Image.new("RGBA", frame.size, "white")
    canvas.alpha_composite(frame)
    flat = canvas.convert("RGB")
    flat.thumbnail((MAX_SIDE, MAX_SIDE))
    out = io.BytesIO()
    flat.save(out, format="PNG")
    return out.getvalue()
