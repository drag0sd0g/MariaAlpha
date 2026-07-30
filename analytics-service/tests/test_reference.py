"""Reference-data loader tests, including the shipped ``config/portfolio.yml``."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest

from analytics.portfolio.reference import (
    DEFAULT_ANNUALIZED_VOLATILITY,
    DEFAULT_BETA,
    DEFAULT_SECTOR,
    PortfolioReference,
)

from .conftest import REPO_CONFIG, UNIVERSE


class TestLoad:
    def test_loads_the_shipped_configuration(self) -> None:
        ref = PortfolioReference.load(REPO_CONFIG / "portfolio.yml")
        assert ref.universe == UNIVERSE
        assert len(ref.symbols) == 6
        assert ref.ref("NVDA").sector == "TECH"
        assert ref.ref("TSLA").beta == pytest.approx(1.80)
        assert ref.source_path is not None

    def test_shipped_volatilities_match_the_execution_engine_reference_data(self) -> None:
        """These mirror ``execution-engine.risk.reference-data``; drift breaks the VaR fallback."""
        ref = PortfolioReference.load(REPO_CONFIG / "portfolio.yml")
        expected = {
            "AAPL": 0.28,
            "MSFT": 0.24,
            "GOOGL": 0.27,
            "AMZN": 0.32,
            "TSLA": 0.55,
            "NVDA": 0.48,
        }
        for symbol, vol in expected.items():
            assert ref.ref(symbol).annualized_volatility == pytest.approx(vol)

    def test_missing_file_falls_back_without_raising(self, tmp_path: Path) -> None:
        ref = PortfolioReference.load(tmp_path / "nope.yml")
        assert ref.source_path is None
        assert ref.universe == ()

    def test_none_path_falls_back(self) -> None:
        assert PortfolioReference.load(None).source_path is None

    def test_malformed_yaml_falls_back(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.yml"
        bad.write_text("portfolio: [oops: :", encoding="utf-8")
        assert PortfolioReference.load(bad).source_path is None

    def test_non_mapping_portfolio_block_falls_back(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.yml"
        bad.write_text("portfolio: 42\n", encoding="utf-8")
        assert PortfolioReference.load(bad).source_path is None

    def test_entries_without_a_symbol_are_skipped(self, tmp_path: Path) -> None:
        path = tmp_path / "p.yml"
        path.write_text(
            "portfolio:\n  symbols:\n    - sector: TECH\n    - symbol: AAPL\n      sector: TECH\n",
            encoding="utf-8",
        )
        ref = PortfolioReference.load(path)
        assert list(ref.symbols) == ["AAPL"]

    def test_universe_defaults_to_the_declared_symbols(self, tmp_path: Path) -> None:
        path = tmp_path / "p.yml"
        path.write_text(
            "portfolio:\n  symbols:\n    - symbol: AAPL\n    - symbol: MSFT\n", encoding="utf-8"
        )
        assert PortfolioReference.load(path).universe == ("AAPL", "MSFT")


class TestAccessors:
    def test_unmapped_symbol_gets_conservative_defaults(self) -> None:
        ref = PortfolioReference()
        unknown = ref.ref("ZZZZ")
        assert unknown.sector == DEFAULT_SECTOR
        assert unknown.beta == DEFAULT_BETA
        assert unknown.annualized_volatility == DEFAULT_ANNUALIZED_VOLATILITY
        assert not ref.is_mapped("ZZZZ")

    def test_prior_volatilities_are_floored_above_zero(self) -> None:
        ref = PortfolioReference()
        assert (ref.prior_volatilities(("A", "B")) > 0).all()

    def test_market_weights_sum_to_one(self, reference) -> None:
        assert reference.market_weights(UNIVERSE).sum() == pytest.approx(1.0, abs=1e-15)

    def test_market_weights_are_proportional_to_market_cap(self, reference) -> None:
        weights = dict(zip(UNIVERSE, reference.market_weights(UNIVERSE), strict=False))
        assert weights["AAPL"] > weights["TSLA"]

    def test_market_weights_fall_back_to_equal_when_caps_are_unusable(self) -> None:
        ref = PortfolioReference()
        weights = ref.market_weights(("A", "B", "C", "D"))
        assert np.allclose(weights, 0.25, atol=1e-15)

    def test_betas_and_sectors_are_returned_in_request_order(self, reference) -> None:
        assert list(reference.sectors(("TSLA", "AAPL"))) == ["AUTOMOTIVE", "TECH"]
        assert np.allclose(reference.betas(("TSLA", "AAPL")), [1.80, 1.20])


class TestPriorCorrelation:
    def test_diagonal_is_exactly_one(self, reference) -> None:
        corr = reference.prior_correlation(UNIVERSE)
        assert np.allclose(np.diag(corr), 1.0, atol=1e-15)

    def test_is_symmetric(self, reference) -> None:
        corr = reference.prior_correlation(UNIVERSE)
        assert np.allclose(corr, corr.T, atol=1e-15)

    def test_is_positive_definite(self, reference) -> None:
        """The prior doubles as the shrinkage target, so it has to be usable on its own."""
        corr = reference.prior_correlation(UNIVERSE)
        assert np.linalg.eigvalsh(corr).min() > 0

    def test_same_sector_pairs_are_more_correlated_than_cross_sector_pairs(self, reference) -> None:
        corr = reference.prior_correlation(UNIVERSE)
        aapl, msft, tsla = 0, 1, 4
        assert corr[aapl, msft] > corr[aapl, tsla]

    def test_empty_universe_gives_an_empty_matrix(self, reference) -> None:
        assert reference.prior_correlation(()).shape == (0, 0)

    def test_prior_covariance_reproduces_the_configured_volatilities(self, reference) -> None:
        cov = reference.prior_covariance(UNIVERSE)
        assert np.allclose(
            np.sqrt(np.diag(cov)), reference.prior_volatilities(UNIVERSE), rtol=1e-12
        )

    def test_prior_covariance_is_positive_definite(self, reference) -> None:
        assert np.linalg.eigvalsh(reference.prior_covariance(UNIVERSE)).min() > 0
