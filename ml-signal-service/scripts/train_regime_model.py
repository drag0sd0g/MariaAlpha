#!/usr/bin/env python3
"""Train the Random Forest regime classifier on synthetic labelled price paths.

Real desk-quality regime labels are expensive to obtain (they require analyst
hand-labelling of historical bars), so this script bootstraps the model from
synthetic OHLCV trajectories drawn under each of the five regime processes:

  * TRENDING_UP / TRENDING_DOWN — geometric Brownian motion with non-zero drift
  * MEAN_REVERTING — Ornstein–Uhlenbeck process around a moving level
  * HIGH_VOLATILITY — Brownian motion with elevated diffusion coefficient
  * LOW_VOLATILITY — Brownian motion with suppressed diffusion coefficient

The eight features used for training are exactly the ones computed at
inference time (see `ml_signal.features.regime_features`) — the training and
serving pipelines share the same code path to avoid drift.

Output: ml-models/regime_model.joblib, loaded by RegimeModel at startup.

Usage:
    cd ml-signal-service
    python scripts/train_regime_model.py [--samples-per-class N] [--seed S]
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, classification_report

sys.path.insert(0, str(Path(__file__).parent.parent / "src"))
from ml_signal.features.regime_features import (  # noqa: E402
    MIN_BARS_FOR_REGIME,
    REGIME_FEATURE_NAMES,
    compute_regime_features_from_closes,
)
from ml_signal.model.regime_model import (  # noqa: E402
    REGIME_HIGH_VOLATILITY,
    REGIME_LOW_VOLATILITY,
    REGIME_MEAN_REVERTING,
    REGIME_NAMES,
    REGIME_TRENDING_DOWN,
    REGIME_TRENDING_UP,
)

OUTPUT_PATH = Path(__file__).parent.parent.parent / "ml-models" / "regime_model.joblib"
WINDOW = MIN_BARS_FOR_REGIME
DEFAULT_SAMPLES_PER_CLASS = 2_000


@dataclass(frozen=True)
class _GeneratorParams:
    drift: float
    sigma: float
    ar1: float
    kind: str


_GENERATOR_PARAMS: dict[int, _GeneratorParams] = {
    REGIME_TRENDING_UP: _GeneratorParams(drift=0.0010, sigma=0.0030, ar1=0.0, kind="gbm"),
    REGIME_TRENDING_DOWN: _GeneratorParams(drift=-0.0010, sigma=0.0030, ar1=0.0, kind="gbm"),
    REGIME_MEAN_REVERTING: _GeneratorParams(drift=0.0, sigma=0.0025, ar1=-0.40, kind="ar1"),
    REGIME_HIGH_VOLATILITY: _GeneratorParams(drift=0.0, sigma=0.0120, ar1=0.0, kind="gbm"),
    REGIME_LOW_VOLATILITY: _GeneratorParams(drift=0.0, sigma=0.0006, ar1=0.0, kind="gbm"),
}


def _generate_path(
    regime: int,
    rng: np.random.Generator,
    n_bars: int = WINDOW,
    start_price: float = 100.0,
) -> np.ndarray:
    """Generate a single synthetic close-price path of length n_bars under the regime."""
    params = _GENERATOR_PARAMS[regime]
    drift = params.drift * float(rng.uniform(0.7, 1.3))
    sigma = params.sigma * float(rng.uniform(0.7, 1.3))

    if params.kind == "gbm":
        log_returns = rng.normal(loc=drift, scale=sigma, size=n_bars - 1)
    elif params.kind == "ar1":
        log_returns = np.zeros(n_bars - 1, dtype=np.float64)
        prev = 0.0
        for i in range(n_bars - 1):
            innovation = rng.normal(loc=0.0, scale=sigma)
            log_returns[i] = params.ar1 * prev + innovation
            prev = log_returns[i]
    else:
        raise ValueError(f"unknown generator kind: {params.kind}")

    start_log = np.log(start_price)
    log_prices = np.concatenate([[start_log], start_log + np.cumsum(log_returns)])
    return np.exp(log_prices)


def _build_dataset(
    samples_per_class: int, rng: np.random.Generator
) -> tuple[np.ndarray, np.ndarray]:
    """Generate (X, y) over all regime classes."""
    feature_rows: list[list[float]] = []
    labels: list[int] = []

    classes = list(_GENERATOR_PARAMS.keys())
    for regime in classes:
        for _ in range(samples_per_class):
            closes = _generate_path(regime, rng)
            features = compute_regime_features_from_closes(closes)
            if features is None:
                continue
            feature_rows.append([features[name] for name in REGIME_FEATURE_NAMES])
            labels.append(regime)

    X = np.array(feature_rows, dtype=np.float64)
    y = np.array(labels, dtype=np.int64)

    perm = rng.permutation(len(y))
    return X[perm], y[perm]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--samples-per-class",
        type=int,
        default=DEFAULT_SAMPLES_PER_CLASS,
        help="Synthetic samples drawn per regime class (default: %(default)s)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="RNG seed for reproducible training data (default: %(default)s)",
    )
    args = parser.parse_args()

    rng = np.random.default_rng(args.seed)

    print(
        f"Generating {args.samples_per_class} samples per class × "
        f"{len(_GENERATOR_PARAMS)} classes (window={WINDOW} bars)..."
    )
    X, y = _build_dataset(args.samples_per_class, rng)
    print(f"Dataset shape: X={X.shape}, y={y.shape}")
    print(f"Class distribution: {dict(zip(*np.unique(y, return_counts=True), strict=False))}")

    split_idx = int(len(X) * 0.8)
    X_train, X_val = X[:split_idx], X[split_idx:]
    y_train, y_val = y[:split_idx], y[split_idx:]
    print(f"\nTrain: {len(X_train)}, Val: {len(X_val)}")

    print("\nTraining RandomForestClassifier...")
    clf = RandomForestClassifier(
        n_estimators=200,
        max_depth=10,
        min_samples_leaf=20,
        class_weight="balanced",
        n_jobs=-1,
        random_state=args.seed,
    )
    clf.fit(X_train, y_train)

    y_pred = clf.predict(X_val)
    accuracy = accuracy_score(y_val, y_pred)
    print(f"\nValidation accuracy: {accuracy:.4f}")
    print(
        classification_report(
            y_val,
            y_pred,
            target_names=[REGIME_NAMES[int(c)] for c in sorted(_GENERATOR_PARAMS.keys())],
            labels=sorted(_GENERATOR_PARAMS.keys()),
        )
    )

    print("Feature importance:")
    for name, imp in sorted(
        zip(REGIME_FEATURE_NAMES, clf.feature_importances_, strict=False),
        key=lambda x: -x[1],
    ):
        print(f"  {name:30s} {imp:8.4f}")

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    version = datetime.now().strftime("%Y%m%d-%H%M%S")
    joblib.dump(
        {
            "model": clf,
            "version": version,
            "feature_names": REGIME_FEATURE_NAMES,
            "accuracy": accuracy,
            "n_train": len(X_train),
            "n_val": len(X_val),
            "samples_per_class": args.samples_per_class,
            "seed": args.seed,
            "window_bars": WINDOW,
            "regime_labels": list(_GENERATOR_PARAMS.keys()),
        },
        OUTPUT_PATH,
    )
    print(f"\nModel saved to {OUTPUT_PATH}")
    print(f"Version: {version}")


if __name__ == "__main__":
    main()
