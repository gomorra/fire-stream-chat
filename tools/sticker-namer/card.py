"""How much memory the graphics card has free, from `nvidia-smi`."""

from __future__ import annotations

import subprocess

QUERY = ["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"]


def parse_free_mb(output: str) -> int | None:
    """The free memory in MiB of the card with the most, or `None` when [output] names no number."""
    values = [int(line) for line in map(str.strip, output.splitlines()) if line.isdigit()]
    return max(values) if values else None


def free_vram_mb() -> int | None:
    """The card's free memory in MiB. `None` when `nvidia-smi` is missing or fails."""
    try:
        result = subprocess.run(QUERY, capture_output=True, text=True, timeout=10, check=True)
    except (OSError, subprocess.SubprocessError):
        return None
    return parse_free_mb(result.stdout)
