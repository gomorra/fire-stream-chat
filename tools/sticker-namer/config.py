"""The service's settings.

They come from `~/.config/firestream-sticker-namer/env`, one `KEY=value` per
line. A variable of the process wins over the file, so one run can try another
model. No setting and no key is in the repository.
"""

from __future__ import annotations

import os
import stat
from dataclasses import dataclass
from pathlib import Path

ENV_FILE = Path.home() / ".config" / "firestream-sticker-namer" / "env"

ENGINES = ("ollama", "openai")


class ConfigError(Exception):
    """A setting is missing or wrong. The message names it."""


@dataclass(frozen=True)
class Config:
    key_file: Path
    bucket: str
    engine: str = "ollama"
    engine_url: str = "http://127.0.0.1:11434"
    model: str = "gemma4:26b"
    # Free graphics memory the model needs before it is loaded. 0 never waits.
    min_free_vram_mb: int = 10000
    # How long Ollama keeps the model on the card after a request.
    keep_alive: str = "2m"


def parse_env(text: str) -> dict[str, str]:
    """The `KEY=value` lines of [text]. A value may sit in single or double quotes."""
    values: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] == "'":
            # setup.sh writes a single quote inside a value as '\''.
            value = value[1:-1].replace("'\\''", "'")
        elif len(value) >= 2 and value[0] == value[-1] == '"':
            value = value[1:-1]
        values[key.strip()] = value
    return values


def read_settings(env_file: Path = ENV_FILE, environ: dict[str, str] | None = None) -> dict[str, str]:
    """The file's settings, each replaced by the process's variable of the same name."""
    environ = dict(os.environ) if environ is None else environ
    values = parse_env(env_file.read_text(encoding="utf-8")) if env_file.is_file() else {}
    for key in ("KEY_FILE", "BUCKET", "ENGINE", "ENGINE_URL", "MODEL", "MIN_FREE_VRAM_MB", "KEEP_ALIVE"):
        if environ.get(key):
            values[key] = environ[key]
    return values


def engine_settings(values: dict[str, str]) -> dict[str, str]:
    """`engine`, `engine_url`, `model` and `keep_alive` of [values], checked and with the defaults."""
    engine = values.get("ENGINE", Config.engine).lower()
    if engine not in ENGINES:
        raise ConfigError(f"ENGINE is {engine!r}. It must be one of: {', '.join(ENGINES)}")
    engine_url = values.get("ENGINE_URL", Config.engine_url).rstrip("/")
    if not engine_url.startswith(("http://", "https://")):
        raise ConfigError(f"ENGINE_URL is {engine_url!r}. It must start with http:// or https://")
    return {
        "engine": engine,
        "engine_url": engine_url,
        "model": values.get("MODEL", Config.model),
        "keep_alive": values.get("KEEP_ALIVE", Config.keep_alive),
    }


def load(env_file: Path = ENV_FILE, environ: dict[str, str] | None = None) -> Config:
    """The service's settings, or a [ConfigError] that names what is missing or wrong."""
    values = read_settings(env_file, environ)
    for key in ("KEY_FILE", "BUCKET"):
        if not values.get(key):
            raise ConfigError(f"{key} is not set in {env_file}")
    key_file = Path(values["KEY_FILE"]).expanduser()
    check_key_file(key_file)
    try:
        min_free_vram_mb = int(values.get("MIN_FREE_VRAM_MB", Config.min_free_vram_mb))
    except ValueError:
        raise ConfigError(f"MIN_FREE_VRAM_MB is {values['MIN_FREE_VRAM_MB']!r}. It must be a number") from None
    if min_free_vram_mb < 0:
        raise ConfigError("MIN_FREE_VRAM_MB must not be negative")
    return Config(
        key_file=key_file,
        bucket=values["BUCKET"],
        min_free_vram_mb=min_free_vram_mb,
        **engine_settings(values),
    )


def check_key_file(key_file: Path) -> None:
    """Refuses a key that is missing, or that anyone but its owner can read.

    The key reads every user's sticker files, so the service does not start
    with one that another account on the PC could copy.
    """
    if not key_file.is_file():
        raise ConfigError(f"The key file {key_file} does not exist")
    if key_file.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        raise ConfigError(f"The key file {key_file} is open to other users. Run: chmod 600 {key_file}")
