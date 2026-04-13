#!/usr/bin/env python3
"""Train the LightGBM signal model on Alpaca historical 1-minute bars.

Usage:
    # Set Alpaca credentials first
    source .env
    cd ml-signal-service
    python scripts/train_signal_model.py

Requires ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY environment variables.
"""

from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta
from pathlib import Path

import httpx
import joblib
import numpy as np
import pandas as pd
from lightgbm import LGBMClassifier
from sklearn.metrics import accuracy_score, classification_report

# Add src to path so we can import our indicator functions
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))
from ml_signal.features.indicators import atr, ema, macd, rsi  # noqa: E402

FEATURE_NAMES = [
    "ema_20",
    "ema_50",
    "ema_cross",
    "ema_20_dist",
    "ema_50_dist",
    "rsi_14",
    "macd_line",
    "macd_signal",
    "macd_hist",
    "atr_14",
    "atr_norm",
    "volume_ratio",
    "realized_vol",
    "return_1",
    "return_5",
]

SYMBOLS = ["AAPL", "MSFT", "GOOGL", "AMZN", "META"]
LOOKBACK_DAYS = 180  # 6 months
LABEL_HORIZON = 5  # predict 5-bar (5-minute) return
TRAIN_SPLIT = 0.8
OUTPUT_PATH = Path(__file__).parent.parent.parent / "ml-models" / "signal_model.joblib"

ALPACA_DATA_URL = "https://data.alpaca.markets/v2/stocks/bars"


def fetch_bars(symbol: str, api_key: str, api_secret: str) -> pd.DataFrame:
    """Fetch 1-minute bars from Alpaca for the given symbol."""
    end = datetime.now()
    start = end - timedelta(days=LOOKBACK_DAYS)

    headers = {
        "APCA-API-KEY-ID": api_key,
        "APCA-API-SECRET-KEY": api_secret,
    }

    all_bars: list[dict] = []
    page_token = None

    print(f"  Fetching {symbol} bars from {start.date()} to {end.date()}...")

    with httpx.Client(timeout=30) as client:
        while True:
            params: dict[str, str | int] = {
                "timeframe": "1Min",
                "start": start.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "end": end.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "limit": 10000,
                "adjustment": "raw",
                "feed": "iex",
            }
            if page_token:
                params["page_token"] = page_token

            resp = client.get(
                f"{ALPACA_DATA_URL}",
                params={**params, "symbols": symbol},
                headers=headers,
            )
            resp.raise_for_status()
            data = resp.json()

            bars = data.get("bars", {}).get(symbol, [])
            all_bars.extend(bars)

            page_token = data.get("next_page_token")
            if not page_token:
                break

    print(f"  {symbol}: fetched {len(all_bars)} bars")

    if not all_bars:
        return pd.DataFrame()

    df = pd.DataFrame(all_bars)
    column_map = {
        "t": "timestamp",
        "o": "open",
        "h": "high",
        "l": "low",
        "c": "close",
        "v": "volume",
    }
    df = df.rename(columns=column_map)
    df["symbol"] = symbol
    return df[["symbol", "timestamp", "open", "high", "low", "close", "volume"]]


def compute_features(df: pd.DataFrame) -> pd.DataFrame:
    """Compute the 15 features for a single-symbol DataFrame."""
    closes = df["close"].values.astype(np.float64)
    highs = df["high"].values.astype(np.float64)
    lows = df["low"].values.astype(np.float64)
    volumes = df["volume"].values.astype(np.float64)

    ema_20 = ema(closes, 20)
    ema_50 = ema(closes, 50)
    rsi_14 = rsi(closes, 14)
    macd_line, macd_sig, macd_hist = macd(closes)
    atr_14 = atr(highs, lows, closes, 14)

    # Volume ratio: current / SMA(20)
    vol_sma = pd.Series(volumes).rolling(20).mean().values
    vol_ratio = np.where(vol_sma > 0, volumes / vol_sma, 1.0)

    # Returns: (close[i] - close[i-1]) / close[i-1]  — must match engine.py
    prev_closes = np.roll(closes, 1)
    prev_closes[0] = closes[0]  # first bar → return = 0
    return_1 = (closes - prev_closes) / np.where(prev_closes > 0, prev_closes, 1.0)

    # Realized volatility: std of 1-bar returns over 20 periods
    realized_vol = pd.Series(return_1).rolling(20).std().values
    close_shifted_5 = np.roll(closes, 5)
    close_shifted_5[:5] = closes[:5]
    return_5 = (closes - close_shifted_5) / np.where(close_shifted_5 > 0, close_shifted_5, 1.0)

    df = df.copy()
    df["ema_20"] = ema_20
    df["ema_50"] = ema_50
    df["ema_cross"] = ema_20 - ema_50
    df["ema_20_dist"] = np.where(ema_20 != 0, (closes - ema_20) / ema_20, 0)
    df["ema_50_dist"] = np.where(ema_50 != 0, (closes - ema_50) / ema_50, 0)
    df["rsi_14"] = rsi_14
    df["macd_line"] = macd_line
    df["macd_signal"] = macd_sig
    df["macd_hist"] = macd_hist
    df["atr_14"] = atr_14
    df["atr_norm"] = np.where(closes != 0, atr_14 / closes, 0)
    df["volume_ratio"] = vol_ratio
    df["realized_vol"] = realized_vol
    df["return_1"] = return_1
    df["return_5"] = return_5

    return df


def compute_labels(df: pd.DataFrame) -> pd.Series:
    """Label = 1 if close[i + LABEL_HORIZON] > close[i], else 0."""
    future_close = df["close"].shift(-LABEL_HORIZON)
    return (future_close > df["close"]).astype(int)


def main() -> None:
    api_key = os.environ.get("ALPACA_API_KEY_ID", "")
    api_secret = os.environ.get("ALPACA_API_SECRET_KEY", "")

    if not api_key or not api_secret:
        print("ERROR: Set ALPACA_API_KEY_ID and ALPACA_API_SECRET_KEY environment variables.")
        print("  source .env  # or export them manually")
        sys.exit(1)

    print(f"Training signal model on {len(SYMBOLS)} symbols, {LOOKBACK_DAYS} days of 1-min bars")
    print(f"Label horizon: {LABEL_HORIZON} bars (minutes)")
    print()

    # 1. Fetch data
    dfs: list[pd.DataFrame] = []
    for symbol in SYMBOLS:
        df = fetch_bars(symbol, api_key, api_secret)
        if not df.empty:
            dfs.append(df)

    if not dfs:
        print("ERROR: No data fetched. Check your API credentials and network.")
        sys.exit(1)

    # 2. Compute features per symbol
    featured_dfs: list[pd.DataFrame] = []
    for df in dfs:
        featured = compute_features(df)
        featured["label"] = compute_labels(featured)
        featured_dfs.append(featured)

    all_data = pd.concat(featured_dfs, ignore_index=True)

    # 3. Drop rows with NaN features or labels
    all_data = all_data.dropna(subset=FEATURE_NAMES + ["label"])

    # Drop first 50 rows per symbol (warm-up period for indicators)
    all_data = all_data.groupby("symbol").apply(lambda g: g.iloc[50:]).reset_index(drop=True)

    print(f"\nTotal samples after cleanup: {len(all_data)}")
    print(f"Label distribution:\n{all_data['label'].value_counts().to_string()}")

    # 4. Time-ordered train/val split
    split_idx = int(len(all_data) * TRAIN_SPLIT)
    X_train = all_data[FEATURE_NAMES].iloc[:split_idx].values
    y_train = all_data["label"].iloc[:split_idx].values
    X_val = all_data[FEATURE_NAMES].iloc[split_idx:].values
    y_val = all_data["label"].iloc[split_idx:].values

    print(f"\nTrain: {len(X_train)} samples, Val: {len(X_val)} samples")

    # 5. Train LightGBM
    print("\nTraining LightGBM...")
    clf = LGBMClassifier(
        n_estimators=200,
        num_leaves=31,
        learning_rate=0.05,
        feature_fraction=0.9,
        bagging_fraction=0.8,
        bagging_freq=5,
        verbose=-1,
    )
    clf.fit(
        X_train,
        y_train,
        eval_set=[(X_val, y_val)],
        callbacks=[],
    )

    # 6. Evaluate
    y_pred = clf.predict(X_val)
    accuracy = accuracy_score(y_val, y_pred)
    print(f"\nValidation accuracy: {accuracy:.4f}")
    print(classification_report(y_val, y_pred, target_names=["DOWN/FLAT", "UP"]))

    if accuracy < 0.50:
        print("WARNING: Accuracy below 50% — model may not be better than random")

    # 7. Feature importance
    print("Feature importance (gain):")
    importances = clf.feature_importances_
    for name, imp in sorted(zip(FEATURE_NAMES, importances, strict=False), key=lambda x: -x[1]):
        print(f"  {name:20s} {imp:8.1f}")

    # 8. Save
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    version = datetime.now().strftime("%Y%m%d-%H%M%S")
    joblib.dump(
        {
            "model": clf,
            "version": version,
            "feature_names": FEATURE_NAMES,
            "accuracy": accuracy,
            "n_train": len(X_train),
            "n_val": len(X_val),
            "symbols": SYMBOLS,
            "label_horizon": LABEL_HORIZON,
        },
        OUTPUT_PATH,
    )
    print(f"\nModel saved to {OUTPUT_PATH}")
    print(f"Version: {version}")


if __name__ == "__main__":
    main()
