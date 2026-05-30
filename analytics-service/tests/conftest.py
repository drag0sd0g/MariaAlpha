"""Pytest path setup so ``src/analytics`` is importable as a top-level package."""

from __future__ import annotations

import sys
from pathlib import Path

_src = Path(__file__).resolve().parent.parent / "src"
if str(_src) not in sys.path:
    sys.path.insert(0, str(_src))
