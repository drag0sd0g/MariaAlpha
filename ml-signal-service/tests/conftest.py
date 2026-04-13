"""Shared test fixtures."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

# Ensure proto stubs are importable
_proto_path = Path(__file__).resolve().parent.parent.parent / "proto" / "generated" / "python"
if _proto_path.exists() and str(_proto_path) not in sys.path:
    sys.path.insert(0, str(_proto_path))

from ml_signal.config import Settings
from ml_signal.features.engine import FEATURE_NAMES, FeatureEngine


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        kafka_bootstrap_servers="localhost:9092",
        signal_model_path=str(tmp_path / "nonexistent.joblib"),
        bar_interval_seconds=60,
        min_bars_for_features=5,  # low threshold for testing
        max_bars_retained=200,
    )


@pytest.fixture
def feature_engine(settings: Settings) -> FeatureEngine:
    return FeatureEngine(settings)


@pytest.fixture
def sample_features() -> dict[str, float]:
    """A plausible feature vector for testing."""
    rng = np.random.default_rng(42)
    return {name: float(rng.standard_normal()) for name in FEATURE_NAMES}
