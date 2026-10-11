"""Puts the tool's folder on the import path, so `python -m pytest tools/sticker-namer` works from the repo's root."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
