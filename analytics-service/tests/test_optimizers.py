"""Optimiser tests — checked against closed forms wherever one exists."""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.optimizers import (
    Constraints,
    FrontierPoint,
    Objective,
    diversification_ratio,
    effective_n,
    efficient_frontier,
    inverse_vol_weights,
    min_variance_closed_form,
    optimize_portfolio,
    portfolio_volatility,
    risk_contributions,
    solve_spd,
    tangency_closed_form,
)

SYMBOLS_2 = ("A", "B")


def two_asset_cov(s1: float = 0.20, s2: float = 0.30, rho: float = 0.25) -> np.ndarray:
    return np.array([[s1 * s1, rho * s1 * s2], [rho * s1 * s2, s2 * s2]])


def random_cov(n: int, seed: int = 5) -> np.ndarray:
    a = np.random.default_rng(seed).normal(size=(n, n))
    return a @ a.T / n + np.eye(n) * 0.05


class TestSolveSpd:
    def test_solves_a_well_conditioned_system(self) -> None:
        matrix = two_asset_cov()
        rhs = np.array([1.0, 2.0])
        x = solve_spd(matrix, rhs)
        assert np.allclose(matrix @ x, rhs, atol=1e-12)

    def test_jitters_a_singular_matrix_instead_of_raising(self) -> None:
        singular = np.array([[1.0, 1.0], [1.0, 1.0]])
        x = solve_spd(singular, np.array([1.0, 1.0]))
        assert np.isfinite(x).all()


class TestClosedForms:
    def test_min_variance_matches_the_two_asset_formula(self) -> None:
        s1, s2, rho = 0.20, 0.30, 0.25
        cov = two_asset_cov(s1, s2, rho)
        expected = (s2 * s2 - rho * s1 * s2) / (s1 * s1 + s2 * s2 - 2 * rho * s1 * s2)
        assert min_variance_closed_form(cov)[0] == pytest.approx(expected, abs=1e-12)

    def test_min_variance_weights_sum_to_one(self) -> None:
        weights = min_variance_closed_form(random_cov(6))
        assert weights.sum() == pytest.approx(1.0, abs=1e-12)

    def test_tangency_is_proportional_to_sigma_inverse_mu(self) -> None:
        cov = two_asset_cov()
        mu = np.array([0.08, 0.12])
        expected = solve_spd(cov, mu)
        expected = expected / expected.sum()
        assert np.allclose(tangency_closed_form(mu, cov), expected, atol=1e-12)

    def test_tangency_is_undefined_when_the_denominator_vanishes(self) -> None:
        cov = np.eye(2)
        with pytest.raises(ValueError, match="tangency portfolio is undefined"):
            tangency_closed_form(np.array([1.0, -1.0]), cov)

    def test_inverse_vol_weights_are_proportional_to_one_over_sigma(self) -> None:
        cov = np.diag([0.01, 0.04, 0.09])
        weights = inverse_vol_weights(cov)
        raw = 1.0 / np.array([0.1, 0.2, 0.3])
        assert np.allclose(weights, raw / raw.sum(), atol=1e-15)


class TestRiskDecomposition:
    def test_risk_contributions_sum_to_portfolio_volatility(self) -> None:
        cov = random_cov(6)
        weights = np.full(6, 1 / 6)
        assert risk_contributions(weights, cov).sum() == pytest.approx(
            portfolio_volatility(weights, cov), abs=1e-12
        )

    def test_zero_volatility_book_has_zero_contributions(self) -> None:
        assert np.allclose(risk_contributions(np.zeros(3), np.eye(3)), 0.0)

    def test_diversification_ratio_is_one_for_a_single_asset(self) -> None:
        assert diversification_ratio(np.array([1.0]), np.array([[0.04]])) == pytest.approx(1.0)

    def test_diversification_ratio_exceeds_one_below_perfect_correlation(self) -> None:
        cov = two_asset_cov(0.2, 0.2, 0.0)
        assert diversification_ratio(np.array([0.5, 0.5]), cov) > 1.0

    def test_diversification_ratio_is_one_at_perfect_correlation(self) -> None:
        cov = two_asset_cov(0.2, 0.2, 1.0)
        assert diversification_ratio(np.array([0.5, 0.5]), cov) == pytest.approx(1.0, abs=1e-9)

    def test_effective_n(self) -> None:
        assert effective_n(np.full(4, 0.25)) == pytest.approx(4.0)
        assert effective_n(np.array([1.0, 0.0, 0.0])) == pytest.approx(1.0)
        assert effective_n(np.zeros(3)) == 0.0


class TestOptimizePortfolio:
    @pytest.mark.parametrize(
        "objective",
        [
            Objective.MIN_VARIANCE,
            Objective.MEAN_VARIANCE,
            Objective.MAX_SHARPE,
            Objective.RISK_PARITY,
            Objective.EQUAL_WEIGHT,
            Objective.INVERSE_VOL,
        ],
    )
    def test_weights_sum_to_one_for_every_objective(self, objective: Objective) -> None:
        cov = random_cov(5)
        mu = np.array([0.06, 0.08, 0.10, 0.09, 0.12])
        result = optimize_portfolio(
            objective,
            [f"S{i}" for i in range(5)],
            cov,
            mu=mu,
            constraints=Constraints(max_weight=1.0),
        )
        assert result.weights.sum() == pytest.approx(1.0, abs=1e-6)

    @pytest.mark.parametrize(
        "objective",
        [Objective.MIN_VARIANCE, Objective.MEAN_VARIANCE, Objective.RISK_PARITY],
    )
    def test_box_constraints_are_respected(self, objective: Objective) -> None:
        cov = random_cov(5)
        mu = np.array([0.06, 0.08, 0.10, 0.09, 0.30])
        result = optimize_portfolio(
            objective,
            [f"S{i}" for i in range(5)],
            cov,
            mu=mu,
            constraints=Constraints(min_weight=0.0, max_weight=0.30),
        )
        assert result.weights.min() >= -1e-8
        assert result.weights.max() <= 0.30 + 1e-6

    def test_min_variance_solver_matches_the_closed_form(self) -> None:
        cov = two_asset_cov()
        result = optimize_portfolio(
            Objective.MIN_VARIANCE, SYMBOLS_2, cov, constraints=Constraints(max_weight=1.0)
        )
        assert np.allclose(result.weights, min_variance_closed_form(cov), atol=1e-8)

    def test_unconstrained_mean_variance_recovers_sigma_inverse_mu(self) -> None:
        cov = two_asset_cov()
        mu = np.array([0.08, 0.12])
        aversion = 3.0
        result = optimize_portfolio(
            Objective.MEAN_VARIANCE,
            SYMBOLS_2,
            cov,
            mu=mu,
            constraints=Constraints(min_weight=-5.0, max_weight=5.0, allow_shorts=True),
            risk_aversion=aversion,
        )
        # With the budget constraint active the solution is the min-variance portfolio plus a
        # multiple of Sigma^-1 (mu - c 1); check it satisfies the first-order condition.
        gradient = mu - aversion * (cov @ result.weights)
        assert np.allclose(gradient, gradient.mean(), atol=1e-6)

    def test_max_sharpe_matches_the_closed_form_tangency_when_unconstrained(self) -> None:
        cov = two_asset_cov()
        mu = np.array([0.08, 0.12])
        result = optimize_portfolio(
            Objective.MAX_SHARPE, SYMBOLS_2, cov, mu=mu, constraints=Constraints(max_weight=1.0)
        )
        assert np.allclose(result.weights, tangency_closed_form(mu, cov), atol=1e-6)

    def test_max_sharpe_beats_both_single_asset_sharpes(self) -> None:
        cov = two_asset_cov(0.2, 0.3, 0.1)
        mu = np.array([0.08, 0.12])
        result = optimize_portfolio(
            Objective.MAX_SHARPE, SYMBOLS_2, cov, mu=mu, constraints=Constraints(max_weight=1.0)
        )
        assert result.sharpe > max(0.08 / 0.2, 0.12 / 0.3)

    def test_max_sharpe_falls_back_when_no_return_is_positive(self) -> None:
        cov = two_asset_cov()
        result = optimize_portfolio(
            Objective.MAX_SHARPE,
            SYMBOLS_2,
            cov,
            mu=np.array([-0.02, -0.05]),
            constraints=Constraints(max_weight=1.0),
        )
        assert "non-positive" in result.message
        assert result.weights.sum() == pytest.approx(1.0, abs=1e-6)

    def test_risk_parity_equalises_risk_contributions(self) -> None:
        cov = random_cov(6)
        result = optimize_portfolio(
            Objective.RISK_PARITY,
            [f"S{i}" for i in range(6)],
            cov,
            constraints=Constraints(max_weight=1.0),
        )
        contributions = result.risk_contributions
        spread = contributions.max() - contributions.min()
        assert spread < 1e-6 * result.volatility

    def test_risk_parity_gives_equal_weights_for_identical_uncorrelated_assets(self) -> None:
        cov = np.eye(4) * 0.04
        result = optimize_portfolio(
            Objective.RISK_PARITY,
            [f"S{i}" for i in range(4)],
            cov,
            constraints=Constraints(max_weight=1.0),
        )
        assert np.allclose(result.weights, 0.25, atol=1e-6)

    def test_risk_parity_reports_when_the_box_destroys_equal_contributions(self) -> None:
        cov = random_cov(5)
        result = optimize_portfolio(
            Objective.RISK_PARITY,
            [f"S{i}" for i in range(5)],
            cov,
            constraints=Constraints(max_weight=0.30),
        )
        assert result.weights.max() <= 0.30 + 1e-9
        assert result.weights.sum() == pytest.approx(1.0, abs=1e-9)
        assert "box constraints bind" in result.message

    def test_risk_parity_equals_inverse_vol_when_correlations_are_identity(self) -> None:
        cov = np.diag([0.01, 0.04, 0.09, 0.16])
        symbols = [f"S{i}" for i in range(4)]
        parity = optimize_portfolio(
            Objective.RISK_PARITY, symbols, cov, constraints=Constraints(max_weight=1.0)
        )
        inverse = optimize_portfolio(
            Objective.INVERSE_VOL, symbols, cov, constraints=Constraints(max_weight=1.0)
        )
        assert np.allclose(parity.weights, inverse.weights, atol=1e-6)

    def test_risk_budget_shifts_contributions_proportionally(self) -> None:
        cov = random_cov(3, seed=11)
        symbols = ["A", "B", "C"]
        budget = {"A": 0.6, "B": 0.2, "C": 0.2}
        result = optimize_portfolio(
            Objective.RISK_PARITY,
            symbols,
            cov,
            constraints=Constraints(max_weight=1.0, risk_budget=budget),
        )
        shares = result.risk_contributions / result.risk_contributions.sum()
        assert shares[0] == pytest.approx(0.6, abs=1e-4)
        assert shares[1] == pytest.approx(0.2, abs=1e-4)

    def test_equal_weight_is_exact(self) -> None:
        result = optimize_portfolio(Objective.EQUAL_WEIGHT, ["A", "B", "C", "D"], random_cov(4))
        assert np.allclose(result.weights, 0.25, atol=1e-15)
        assert result.effective_n == pytest.approx(4.0)

    def test_sector_cap_is_enforced(self) -> None:
        cov = np.diag([0.01, 0.01, 0.25, 0.25])
        symbols = ["T1", "T2", "E1", "E2"]
        sectors = ["TECH", "TECH", "ENERGY", "ENERGY"]
        result = optimize_portfolio(
            Objective.MIN_VARIANCE,
            symbols,
            cov,
            constraints=Constraints(max_weight=1.0, max_sector_weight={"TECH": 0.5}),
            sectors=sectors,
        )
        assert result.weights[0] + result.weights[1] <= 0.5 + 1e-6

    def test_reports_the_solver_message_and_convergence(self) -> None:
        result = optimize_portfolio(
            Objective.MIN_VARIANCE,
            SYMBOLS_2,
            two_asset_cov(),
            constraints=Constraints(max_weight=1.0),
        )
        assert result.converged
        assert result.message

    def test_singular_covariance_does_not_raise(self) -> None:
        singular = np.ones((3, 3)) * 0.04
        result = optimize_portfolio(
            Objective.MIN_VARIANCE,
            ["A", "B", "C"],
            singular,
            constraints=Constraints(max_weight=1.0),
        )
        assert np.isfinite(result.weights).all()

    def test_rejects_an_empty_universe(self) -> None:
        with pytest.raises(ValueError, match="at least one symbol"):
            optimize_portfolio(Objective.EQUAL_WEIGHT, [], np.zeros((0, 0)))

    def test_rejects_a_mismatched_covariance(self) -> None:
        with pytest.raises(ValueError, match="covariance must be 2x2"):
            optimize_portfolio(Objective.EQUAL_WEIGHT, SYMBOLS_2, np.eye(3))

    def test_rejects_mismatched_expected_returns(self) -> None:
        with pytest.raises(ValueError, match="expected returns must have length 2"):
            optimize_portfolio(
                Objective.MEAN_VARIANCE, SYMBOLS_2, two_asset_cov(), mu=np.array([0.1, 0.2, 0.3])
            )

    def test_max_weight_too_small_to_be_fully_invested_is_rejected(self) -> None:
        with pytest.raises(ValueError, match="cannot reach a fully invested portfolio"):
            optimize_portfolio(
                Objective.MIN_VARIANCE,
                ["A", "B", "C", "D"],
                random_cov(4),
                constraints=Constraints(max_weight=0.2),
            )

    def test_min_weight_above_max_weight_is_rejected(self) -> None:
        with pytest.raises(ValueError, match="min_weight exceeds max_weight"):
            Constraints(min_weight=0.8, max_weight=0.2).bounds(3)

    def test_payload_is_json_shaped(self) -> None:
        result = optimize_portfolio(
            Objective.EQUAL_WEIGHT, SYMBOLS_2, two_asset_cov(), mu=np.array([0.1, 0.2])
        )
        payload = result.to_payload()
        assert payload["objective"] == "EQUAL_WEIGHT"
        assert set(payload["weights"]) == set(SYMBOLS_2)
        assert "effectiveN" in payload and "diversificationRatio" in payload


class TestEfficientFrontier:
    def test_returns_the_requested_number_of_points(self) -> None:
        points = efficient_frontier(
            ["A", "B"],
            np.array([0.08, 0.12]),
            two_asset_cov(),
            constraints=Constraints(max_weight=1.0),
            points=7,
        )
        assert len(points) == 7
        assert all(isinstance(p, FrontierPoint) for p in points)

    def test_volatility_is_non_decreasing_along_the_frontier(self) -> None:
        points = efficient_frontier(
            [f"S{i}" for i in range(5)],
            np.array([0.06, 0.08, 0.10, 0.09, 0.14]),
            random_cov(5),
            constraints=Constraints(max_weight=1.0),
            points=15,
        )
        vols = [p.volatility for p in points]
        assert vols == sorted(vols)

    def test_higher_risk_points_carry_higher_expected_return(self) -> None:
        points = efficient_frontier(
            ["A", "B"],
            np.array([0.08, 0.14]),
            two_asset_cov(),
            constraints=Constraints(max_weight=1.0),
            points=12,
        )
        assert points[-1].expected_return > points[0].expected_return

    def test_rejects_fewer_than_two_points(self) -> None:
        with pytest.raises(ValueError, match="points must be at least 2"):
            efficient_frontier(["A"], np.array([0.1]), np.array([[0.04]]), points=1)

    def test_payload_shape(self) -> None:
        points = efficient_frontier(["A", "B"], np.array([0.08, 0.12]), two_asset_cov(), points=3)
        payload = points[0].to_payload(["A", "B"])
        assert set(payload) == {"expectedReturn", "volatility", "sharpe", "weights"}
