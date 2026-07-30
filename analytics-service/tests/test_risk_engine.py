"""Risk-engine tests.

The headline case is ``test_hedged_book_gets_large_diversification_credit``: it pins the exact
numbers from the roadmap-4.6.1 motivation, so a regression in the covariance path shows up as a
failed assertion rather than as a quietly wrong risk report.
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from analytics.risk.engine import (
    component_var,
    empirical_var_es,
    gaussian_es_multiplier,
    historical_var,
    monte_carlo_var,
    parametric_var,
    portfolio_sigma,
    z_score,
)

HEDGED = np.array([1_000_000.0, -1_000_000.0])
SAME_SIGNED = np.array([1_000_000.0, 1_000_000.0])


def cov_2(sd: float = 0.02, rho: float = 0.95) -> np.ndarray:
    return np.array([[sd * sd, rho * sd * sd], [rho * sd * sd, sd * sd]])


class TestQuantiles:
    @pytest.mark.parametrize(
        ("confidence", "expected"),
        [(0.90, 1.28155), (0.95, 1.64485), (0.99, 2.32635)],
    )
    def test_z_score_matches_published_values(self, confidence: float, expected: float) -> None:
        assert z_score(confidence) == pytest.approx(expected, abs=1e-4)

    @pytest.mark.parametrize("confidence", [0.0, 1.0, -0.1, 1.5])
    def test_rejects_confidence_outside_the_open_unit_interval(self, confidence: float) -> None:
        with pytest.raises(ValueError, match="strictly between 0 and 1"):
            z_score(confidence)

    def test_gaussian_es_multiplier_matches_the_textbook_ratio(self) -> None:
        # phi(z_0.95) / 0.05 = 2.0627; dividing by z gives the familiar ES/VaR = 1.2535.
        assert gaussian_es_multiplier(0.95) == pytest.approx(2.06271, abs=1e-4)
        assert gaussian_es_multiplier(0.95) / z_score(0.95) == pytest.approx(1.2535, abs=1e-3)


class TestPortfolioSigma:
    def test_single_asset_reduces_to_notional_times_volatility(self) -> None:
        assert portfolio_sigma(np.array([500_000.0]), np.array([[0.02**2]])) == pytest.approx(
            10_000.0, rel=1e-12
        )

    def test_empty_book_has_zero_sigma(self) -> None:
        assert portfolio_sigma(np.array([]), np.zeros((0, 0))) == 0.0

    def test_signs_matter_so_a_hedge_nets_down(self) -> None:
        cov = cov_2(rho=0.95)
        assert portfolio_sigma(HEDGED, cov) < portfolio_sigma(SAME_SIGNED, cov)


class TestParametricVar:
    def test_single_asset_matches_z_sigma_notional(self) -> None:
        result = parametric_var(np.array([1_000_000.0]), np.array([[0.02**2]]), 0.95, 1.0)
        assert result.var_usd == pytest.approx(z_score(0.95) * 20_000.0, rel=1e-12)

    def test_scales_with_the_square_root_of_the_horizon(self) -> None:
        one = parametric_var(SAME_SIGNED, cov_2(), 0.95, 1.0).var_usd
        four = parametric_var(SAME_SIGNED, cov_2(), 0.95, 4.0).var_usd
        assert four == pytest.approx(2.0 * one, rel=1e-12)

    def test_expected_shortfall_exceeds_var(self) -> None:
        result = parametric_var(SAME_SIGNED, cov_2(), 0.95, 1.0)
        assert result.expected_shortfall_usd > result.var_usd

    def test_payload_is_json_shaped(self) -> None:
        payload = parametric_var(SAME_SIGNED, cov_2(), 0.95, 1.0).to_payload()
        assert payload["method"] == "PARAMETRIC"
        assert payload["varUsd"] > 0
        assert payload["sufficient"] is True


class TestDiversificationCredit:
    def test_hedged_book_gets_large_diversification_credit(self) -> None:
        """The exact case from the 4.6.1 motivation: rho = 0.95, +/-$1M, 2% daily volatility."""
        cov = cov_2(sd=0.02, rho=0.95)
        result = parametric_var(HEDGED, cov, 0.95, 1.0)
        rows, sum_of_absolutes, ratio = component_var(("A", "B"), HEDGED, cov, 0.95, 1.0)

        assert result.var_usd == pytest.approx(10_403.0, abs=5.0)
        assert sum_of_absolutes == pytest.approx(65_794.0, abs=5.0)
        assert ratio == pytest.approx(6.3246, abs=1e-3)
        assert len(rows) == 2

    def test_perfect_correlation_and_equal_opposite_positions_gives_zero_var(self) -> None:
        cov = cov_2(sd=0.02, rho=1.0)
        assert parametric_var(HEDGED, cov, 0.95, 1.0).var_usd == pytest.approx(0.0, abs=1e-6)

    def test_same_signed_book_at_perfect_correlation_equals_sum_of_absolutes(self) -> None:
        cov = cov_2(sd=0.02, rho=1.0)
        result = parametric_var(SAME_SIGNED, cov, 0.95, 1.0)
        _, sum_of_absolutes, ratio = component_var(("A", "B"), SAME_SIGNED, cov, 0.95, 1.0)

        assert result.var_usd == pytest.approx(sum_of_absolutes, rel=1e-12)
        assert ratio == pytest.approx(1.0, abs=1e-12)

    @pytest.mark.parametrize("rho", [-0.5, 0.0, 0.3, 0.7, 0.99])
    def test_diversification_ratio_is_never_below_one(self, rho: float) -> None:
        _, _, ratio = component_var(("A", "B"), SAME_SIGNED, cov_2(rho=rho), 0.95, 1.0)
        assert ratio >= 1.0 - 1e-12

    def test_single_asset_book_has_ratio_exactly_one(self) -> None:
        _, _, ratio = component_var(
            ("A",), np.array([1_000_000.0]), np.array([[0.02**2]]), 0.95, 1.0
        )
        assert ratio == pytest.approx(1.0, abs=1e-12)


class TestComponentVar:
    def test_components_sum_exactly_to_portfolio_var(self) -> None:
        cov = cov_2(rho=0.4)
        notionals = np.array([2_000_000.0, -500_000.0])
        rows, _, _ = component_var(("A", "B"), notionals, cov, 0.99, 1.0)
        total = parametric_var(notionals, cov, 0.99, 1.0).var_usd
        assert sum(r.component_var_usd for r in rows) == pytest.approx(total, rel=1e-12)

    def test_percentages_sum_to_one(self) -> None:
        cov = cov_2(rho=0.4)
        notionals = np.array([2_000_000.0, -500_000.0])
        rows, _, _ = component_var(("A", "B"), notionals, cov, 0.95, 1.0)
        assert sum(r.pct_of_total for r in rows) == pytest.approx(1.0, rel=1e-12)

    def test_a_genuine_hedge_has_negative_component_var(self) -> None:
        """A small short against a large correlated long *reduces* total risk."""
        cov = cov_2(rho=0.9)
        notionals = np.array([2_000_000.0, -500_000.0])
        rows, _, _ = component_var(("LONG", "HEDGE"), notionals, cov, 0.95, 1.0)
        by_symbol = {r.symbol: r for r in rows}
        assert by_symbol["HEDGE"].component_var_usd < 0
        assert by_symbol["LONG"].component_var_usd > 0

    def test_standalone_var_ignores_correlation(self) -> None:
        rows, _, _ = component_var(("A", "B"), HEDGED, cov_2(rho=0.95), 0.95, 1.0)
        expected = z_score(0.95) * 1_000_000.0 * 0.02
        assert all(r.standalone_var_usd == pytest.approx(expected, rel=1e-12) for r in rows)

    def test_zero_book_degrades_gracefully(self) -> None:
        rows, sum_of_absolutes, ratio = component_var(("A", "B"), np.zeros(2), cov_2(), 0.95, 1.0)
        assert all(r.component_var_usd == 0.0 for r in rows)
        assert sum_of_absolutes == 0.0
        assert ratio == 1.0

    def test_perfectly_hedged_book_reports_an_unbounded_ratio(self) -> None:
        _, _, ratio = component_var(("A", "B"), HEDGED, cov_2(rho=1.0), 0.95, 1.0)
        assert math.isinf(ratio)

    def test_payload_is_json_shaped(self) -> None:
        rows, _, _ = component_var(("A", "B"), HEDGED, cov_2(), 0.95, 1.0)
        payload = rows[0].to_payload()
        assert set(payload) == {
            "symbol",
            "notionalUsd",
            "standaloneVarUsd",
            "marginalVarUsd",
            "componentVarUsd",
            "pctOfTotal",
        }


class TestEmpiricalVarEs:
    def test_matches_the_quantile_of_a_crafted_sample(self) -> None:
        pnl = np.arange(-100.0, 100.0)
        var, _ = empirical_var_es(pnl, 0.95)
        assert var == pytest.approx(-np.quantile(pnl, 0.05), rel=1e-12)

    def test_expected_shortfall_is_at_least_var(self) -> None:
        pnl = np.random.default_rng(3).normal(0, 1000, size=5000)
        var, es = empirical_var_es(pnl, 0.95)
        assert es >= var

    def test_empty_sample_returns_zeros(self) -> None:
        assert empirical_var_es(np.array([]), 0.95) == (0.0, 0.0)


class TestHistoricalVar:
    def test_reports_insufficiency_below_the_observation_floor(self) -> None:
        returns = np.random.default_rng(1).normal(0, 0.01, size=(8, 2))
        result = historical_var(SAME_SIGNED, returns, 0.95, 1.0, bars_per_day=390.0)
        assert not result.sufficient
        assert any("observations" in note for note in result.notes)

    def test_uses_non_overlapping_blocks_when_there_is_enough_history(self) -> None:
        returns = np.random.default_rng(2).normal(0, 0.01, size=(400, 2))
        result = historical_var(SAME_SIGNED, returns, 0.95, 1.0, bars_per_day=4.0)
        assert result.observations == 100
        assert any("non-overlapping" in note for note in result.notes)
        assert result.sufficient

    def test_scales_per_bar_pnl_when_blocks_are_unavailable(self) -> None:
        returns = np.random.default_rng(3).normal(0, 0.01, size=(60, 2))
        result = historical_var(SAME_SIGNED, returns, 0.95, 1.0, bars_per_day=390.0)
        assert any("sqrt(390)" in note for note in result.notes)

    def test_empty_history_returns_zero_and_flags_insufficiency(self) -> None:
        result = historical_var(SAME_SIGNED, np.zeros((0, 0)), 0.95, 1.0, 390.0)
        assert result.var_usd == 0.0
        assert not result.sufficient
        assert "no return history available" in result.notes

    def test_recovers_a_known_loss_distribution(self) -> None:
        # Every bar loses exactly 1% on asset A, nothing on B; a 1-bar horizon must report 1%.
        returns = np.zeros((200, 2))
        returns[:, 0] = -0.01
        result = historical_var(np.array([1_000_000.0, 0.0]), returns, 0.95, 1.0, bars_per_day=1.0)
        assert result.var_usd == pytest.approx(10_000.0, rel=1e-9)


class TestMonteCarloVar:
    def test_converges_to_the_parametric_answer_under_normal_draws(self) -> None:
        cov = cov_2(rho=0.3)
        analytic = parametric_var(SAME_SIGNED, cov, 0.95, 1.0).var_usd
        simulated = monte_carlo_var(
            SAME_SIGNED, cov, 0.95, 1.0, simulations=40_000, seed=11
        ).var_usd
        assert simulated == pytest.approx(analytic, rel=0.03)

    def test_is_deterministic_for_a_fixed_seed(self) -> None:
        first = monte_carlo_var(SAME_SIGNED, cov_2(), 0.95, 1.0, simulations=5_000, seed=7)
        second = monte_carlo_var(SAME_SIGNED, cov_2(), 0.95, 1.0, simulations=5_000, seed=7)
        assert first.var_usd == second.var_usd

    def test_student_t_fattens_the_tail_at_high_confidence(self) -> None:
        cov = cov_2(rho=0.3)
        normal = monte_carlo_var(
            SAME_SIGNED, cov, 0.99, 1.0, simulations=80_000, distribution="normal", seed=5
        )
        student = monte_carlo_var(
            SAME_SIGNED, cov, 0.99, 1.0, simulations=80_000, distribution="t", df=5.0, seed=5
        )
        assert student.var_usd > normal.var_usd
        assert any("Student-t" in note for note in student.notes)

    def test_expected_shortfall_is_at_least_var(self) -> None:
        result = monte_carlo_var(SAME_SIGNED, cov_2(), 0.95, 1.0, simulations=20_000, seed=2)
        assert result.expected_shortfall_usd >= result.var_usd

    def test_rejects_student_t_with_infinite_variance(self) -> None:
        with pytest.raises(ValueError, match="degrees of freedom must exceed 2"):
            monte_carlo_var(SAME_SIGNED, cov_2(), 0.95, 1.0, distribution="t", df=1.5)

    def test_jitters_a_singular_covariance_instead_of_failing(self) -> None:
        singular = np.array([[0.0004, 0.0004], [0.0004, 0.0004]])
        result = monte_carlo_var(SAME_SIGNED, singular, 0.95, 1.0, simulations=2_000, seed=1)
        assert math.isfinite(result.var_usd)

    def test_empty_portfolio_returns_zero(self) -> None:
        result = monte_carlo_var(np.array([]), np.zeros((0, 0)), 0.95, 1.0)
        assert result.var_usd == 0.0
        assert "empty portfolio" in result.notes
