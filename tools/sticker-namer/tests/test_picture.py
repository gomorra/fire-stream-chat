import io

import pytest
from PIL import Image

import picture
from fakes import id_of, webp


def opened(png: bytes) -> Image.Image:
    image = Image.open(io.BytesIO(png))
    image.load()
    return image


def test_a_file_under_its_own_hash_passes():
    data = webp()
    picture.check_hash(data, id_of(data))


def test_a_file_under_another_id_is_refused():
    with pytest.raises(picture.PictureError, match="hash mismatch"):
        picture.check_hash(webp(), "0" * 64)


def test_a_webp_becomes_a_png():
    image = opened(picture.png_of(webp(size=(64, 48))))
    assert image.format == "PNG"
    assert image.size == (64, 48)
    assert image.mode == "RGB"


def test_a_large_sticker_is_scaled_to_512():
    image = opened(picture.png_of(webp(size=(1024, 512))))
    assert image.size == (512, 256)


def test_transparent_parts_become_white():
    image = opened(picture.png_of(webp(color=(0, 0, 0, 0))))
    assert image.getpixel((5, 5)) == (255, 255, 255)


def test_an_animated_sticker_gives_its_first_frame():
    image = opened(picture.png_of(webp(color=(255, 0, 0, 255), frames=3)))
    red, green, blue = image.getpixel((5, 5))
    assert red > 200 and green < 50 and blue < 50


@pytest.mark.parametrize(
    "data",
    [
        b"",
        b"not a picture",
        b'{"v":"5.5.2","layers":[]}',  # a Lottie animation
        b"\x1f\x8b\x08\x00" + b"\x00" * 32,  # a gzipped one
    ],
)
def test_a_file_that_is_no_webp_is_refused(data):
    with pytest.raises(picture.PictureError, match="not a WebP picture"):
        picture.png_of(data)


def test_a_png_under_a_webp_name_is_refused():
    out = io.BytesIO()
    Image.new("RGB", (8, 8), "red").save(out, format="PNG")
    with pytest.raises(picture.PictureError, match="not a WebP picture"):
        picture.png_of(out.getvalue())


def test_a_picture_far_above_a_stickers_size_is_refused(monkeypatch):
    monkeypatch.setattr(picture, "MAX_PIXELS", 100)
    with pytest.raises(picture.PictureError, match="picture too large"):
        picture.png_of(webp(size=(64, 48)))
