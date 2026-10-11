"""Runs a folder of stickers through one or more models and prints the names side by side.

    python evaluate.py <folder> [--models gemma4:26b,gemma4:12b] [--engine ollama] [--engine-url URL]

The tool for choosing the model. It reads `.webp` files from the folder, asks
each model the service's own question, and applies the service's own check.
It needs no key and touches neither Firestore nor Storage. Without arguments
the engine and the model come from the service's env file.

With Ollama, one model after the other is loaded onto the graphics card, so
the first sticker of each model takes longer.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import picture
from answer import AnswerError, fields_of
from config import ENGINES, ConfigError, engine_settings, read_settings
from engine import Engine, EngineDown


def name_one(engine: Engine, png: bytes) -> tuple[str, float]:
    """One model's answer for one picture as a printable block, and the seconds it took."""
    started = time.monotonic()
    try:
        fields = fields_of(engine.ask(png))
    except AnswerError as e:
        return f"  refused: {e}", time.monotonic() - started
    seconds = time.monotonic() - started
    lines = [
        f"  en:     {fields['nameEn']}",
        f"  de:     {fields['nameDe']}",
        f"  words:  {', '.join(fields['words'])}",
        f"  text:   {fields['text']}",
        f"  emojis: {' '.join(fields['emojis'])}",
    ]
    return "\n".join(lines), seconds


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("folder", type=Path, help="a folder of .webp stickers")
    parser.add_argument("--models", help="model names, separated by commas")
    parser.add_argument("--engine", choices=ENGINES)
    parser.add_argument("--engine-url")
    args = parser.parse_args(argv)

    values = read_settings()
    if args.engine:
        values["ENGINE"] = args.engine
    if args.engine_url:
        values["ENGINE_URL"] = args.engine_url
    try:
        settings = engine_settings(values)
    except ConfigError as e:
        print(e, file=sys.stderr)
        return 2
    models = [m.strip() for m in (args.models or settings["model"]).split(",") if m.strip()]

    files = sorted(args.folder.glob("*.webp"))
    if not files:
        print(f"No .webp file in {args.folder}", file=sys.stderr)
        return 2

    pictures: list[tuple[str, bytes]] = []
    for file in files:
        try:
            pictures.append((file.name, picture.png_of(file.read_bytes())))
        except picture.PictureError as e:
            print(f"{file.name}: left out, {e}", file=sys.stderr)
    if not pictures:
        print(f"No file in {args.folder} is a WebP picture", file=sys.stderr)
        return 2

    # One model at a time, so each is loaded once. The answers are printed per sticker afterwards.
    answers: dict[str, dict[str, str]] = {name: {} for name, _ in pictures}
    seconds: dict[str, list[float]] = {model: [] for model in models}
    for model in models:
        engine = Engine(settings["engine"], settings["engine_url"], model, settings["keep_alive"])
        for name, png in pictures:
            try:
                answers[name][model], took = name_one(engine, png)
            except EngineDown as e:
                # The models that ran keep their answers. This one is left out from here on.
                print(f"The engine at {settings['engine_url']} did not answer for {model}: {e}", file=sys.stderr)
                break
            seconds[model].append(took)
            print(f"{model}: {name} in {took:.1f} s", file=sys.stderr)

    for name, _ in pictures:
        print(f"\n{name}")
        for model in models:
            print(f" {model}")
            print(answers[name].get(model, "  no answer: the engine was down"))

    print("\nSeconds per sticker")
    for model in models:
        took = seconds[model]
        if took:
            print(f" {model}: {sum(took) / len(took):.1f} on average, {max(took):.1f} at most")
        else:
            print(f" {model}: no answer")
    return 0 if all(seconds.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
