"""Shared numeric type aliases.

``FloatArray`` rather than ``NDArray[np.float64]`` throughout the portfolio and risk packages.
numpy's stubs widen the result of arithmetic on a ``float64`` array to ``floating[Any]``, so
annotating every intermediate as ``float64`` forces a cast at nearly every line — casts that buy
no safety and hide the occasional real shape or dtype mistake in the noise. ``floating[Any]`` is
what the operations genuinely produce.
"""

from __future__ import annotations

from typing import Any

import numpy as np
from numpy.typing import NDArray

type FloatArray = NDArray[np.floating[Any]]
"""A 1-D or 2-D array of floats — the working type for every matrix in this service."""
