"""Black-Litterman tests.

The strongest test in this file is ``test_equilibrium_reverse_optimises_to_market_weights``:
feeding Pi back through the unconstrained mean-variance first-order condition must return
w_mkt exactly. If reverse optimisation is wrong, everything downstream is decorative.
"""

from __future__ import annotations

import numpy as np
import pytest

from analytics.portfolio.black_litterman import (
    View,
    build_pick_matrix,
    equilibrium_returns,
    posterior,
    run,
)
from analytics.portfolio.optimizers import solve_spd

from .conftest import UNIVERSE

RISK_AVERSION = 2.5
TAU = 0.05


@pytest.fixture
def cov(reference) -> np.ndarray:
    return reference.prior_covariance(UNIVERSE)


@pytest.fixture
def market_weights(reference) -> np.ndarray:
    return reference.market_weights(UNIVERSE)


class TestEquilibrium:
    def test_equilibrium_reverse_optimises_to_market_weights(self, cov, market_weights) -> None:
        pi = equilibrium_returns(cov, market_weights, RISK_AVERSION)
        recovered = solve_spd(cov, pi) / RISK_AVERSION
        assert np.allclose(recovered, market_weights, atol=1e-12)

    def test_equilibrium_scales_linearly_with_risk_aversion(self, cov, market_weights) -> None:
        one = equilibrium_returns(cov, market_weights, 1.0)
        five = equilibrium_returns(cov, market_weights, 5.0)
        assert np.allclose(five, 5.0 * one, atol=1e-15)

    def test_higher_beta_names_carry_higher_equilibrium_returns(self, cov, market_weights) -> None:
        pi = dict(
            zip(UNIVERSE, equilibrium_returns(cov, market_weights, RISK_AVERSION), strict=False)
        )
        assert pi["NVDA"] > pi["MSFT"]


class TestPickMatrix:
    def test_absolute_view_produces_a_unit_row(self) -> None:
        pick, q, warnings = build_pick_matrix([View("nvda", {"NVDA": 1.0}, 0.15)], UNIVERSE)
        assert pick.shape == (1, len(UNIVERSE))
        assert pick[0, UNIVERSE.index("NVDA")] == 1.0
        assert q[0] == 0.15
        assert warnings == []

    def test_relative_view_row_sums_to_zero(self) -> None:
        pick, _, warnings = build_pick_matrix(
            [View("rel", {"AAPL": 1.0, "MSFT": -1.0}, 0.03)], UNIVERSE
        )
        assert pick[0].sum() == pytest.approx(0.0, abs=1e-15)
        assert warnings == []

    def test_warns_on_a_row_that_is_neither_absolute_nor_relative(self) -> None:
        _, _, warnings = build_pick_matrix(
            [View("odd", {"AAPL": 0.5, "MSFT": 0.9}, 0.02)], UNIVERSE
        )
        assert warnings and "sum to" in warnings[0]

    def test_rejects_an_unknown_symbol(self) -> None:
        with pytest.raises(ValueError, match="unknown symbol 'ZZZZ'"):
            build_pick_matrix([View("bad", {"ZZZZ": 1.0}, 0.1)], UNIVERSE)

    def test_rejects_an_empty_pick(self) -> None:
        with pytest.raises(ValueError, match="empty pick"):
            build_pick_matrix([View("empty", {}, 0.1)], UNIVERSE)

    def test_rejects_non_positive_confidence(self) -> None:
        with pytest.raises(ValueError, match="strictly positive confidence"):
            build_pick_matrix([View("x", {"AAPL": 1.0}, 0.1, confidence=0.0)], UNIVERSE)


class TestPosterior:
    def test_no_views_returns_the_prior_exactly(self, cov, market_weights) -> None:
        pi = equilibrium_returns(cov, market_weights, RISK_AVERSION)
        mean, _, omega, warnings = posterior(UNIVERSE, cov, pi, [], tau=TAU)
        assert np.array_equal(mean, pi)
        assert omega.size == 0
        assert warnings == []

    def test_woodbury_and_naive_forms_agree(self, cov, market_weights) -> None:
        pi = equilibrium_returns(cov, market_weights, RISK_AVERSION)
        views = [
            View("nvda", {"NVDA": 1.0}, 0.15, 0.8),
            View("rel", {"AAPL": 1.0, "MSFT": -1.0}, 0.03, 0.4),
        ]
        fast, fast_cov, _, _ = posterior(UNIVERSE, cov, pi, views, tau=TAU, use_woodbury=True)
        slow, slow_cov, _, _ = posterior(UNIVERSE, cov, pi, views, tau=TAU, use_woodbury=False)
        assert np.allclose(fast, slow, atol=1e-10)
        assert np.allclose(fast_cov, slow_cov, atol=1e-10)

    def test_posterior_covariance_is_positive_definite(self, cov, market_weights) -> None:
        pi = equilibrium_returns(cov, market_weights, RISK_AVERSION)
        _, posterior_cov, _, _ = posterior(
            UNIVERSE, cov, pi, [View("nvda", {"NVDA": 1.0}, 0.15)], tau=TAU
        )
        assert np.linalg.eigvalsh(posterior_cov).min() > 0

    def test_omega_is_proportional_to_view_variance_and_inverse_confidence(
        self, cov, market_weights
    ) -> None:
        pi = equilibrium_returns(cov, market_weights, RISK_AVERSION)
        _, _, loose, _ = posterior(
            UNIVERSE, cov, pi, [View("v", {"NVDA": 1.0}, 0.15, confidence=0.5)], tau=TAU
        )
        _, _, tight, _ = posterior(
            UNIVERSE, cov, pi, [View("v", {"NVDA": 1.0}, 0.15, confidence=2.0)], tau=TAU
        )
        assert loose[0] == pytest.approx(4.0 * tight[0], rel=1e-12)


class TestRun:
    def test_view_moves_the_posterior_toward_the_view(self, cov, market_weights) -> None:
        idx = UNIVERSE.index("NVDA")
        baseline = run(UNIVERSE, cov, market_weights, [], risk_aversion=RISK_AVERSION)
        target = baseline.equilibrium_returns[idx] + 0.20
        with_view = run(
            UNIVERSE,
            cov,
            market_weights,
            [View("bull", {"NVDA": 1.0}, float(target), 1.0)],
            risk_aversion=RISK_AVERSION,
        )
        assert with_view.posterior_returns[idx] > baseline.equilibrium_returns[idx]
        assert with_view.posterior_returns[idx] < target

    def test_posterior_moves_monotonically_with_confidence(self, cov, market_weights) -> None:
        idx = UNIVERSE.index("NVDA")
        posteriors = [
            run(
                UNIVERSE,
                cov,
                market_weights,
                [View("bull", {"NVDA": 1.0}, 0.40, confidence=c)],
                risk_aversion=RISK_AVERSION,
            ).posterior_returns[idx]
            for c in (0.1, 0.5, 2.0, 20.0)
        ]
        assert posteriors == sorted(posteriors)

    def test_he_litterman_weight_change_lies_in_the_span_of_the_views(
        self, cov, market_weights
    ) -> None:
        """The theorem: the optimal weight shift is a linear combination of the view portfolios.

        A relative AAPL-vs-MSFT view may only tilt AAPL against MSFT; nothing else in the book
        moves. This is the property people usually mis-state as "the cap-weighted mean is
        unchanged", which is not true.
        """
        result = run(
            UNIVERSE,
            cov,
            market_weights,
            [View("rel", {"AAPL": 1.0, "MSFT": -1.0}, 0.05)],
            risk_aversion=RISK_AVERSION,
        )
        posterior_weights = solve_spd(cov, result.posterior_returns) / RISK_AVERSION
        delta = posterior_weights - market_weights

        pick = np.zeros((1, len(UNIVERSE)))
        pick[0, UNIVERSE.index("AAPL")] = 1.0
        pick[0, UNIVERSE.index("MSFT")] = -1.0
        projection = pick.T @ np.linalg.solve(pick @ pick.T, pick @ delta)

        assert np.allclose(delta, projection, atol=1e-12)
        untouched = [i for i, s in enumerate(UNIVERSE) if s not in ("AAPL", "MSFT")]
        assert np.allclose(delta[untouched], 0.0, atol=1e-12)

    def test_view_impact_is_posterior_minus_equilibrium(self, cov, market_weights) -> None:
        result = run(
            UNIVERSE,
            cov,
            market_weights,
            [View("bull", {"NVDA": 1.0}, 0.30)],
            risk_aversion=RISK_AVERSION,
        )
        assert np.allclose(
            result.view_impact,
            result.posterior_returns - result.equilibrium_returns,
            atol=1e-15,
        )

    def test_correlation_propagates_a_single_name_view_to_its_peers(
        self, cov, market_weights
    ) -> None:
        result = run(
            UNIVERSE,
            cov,
            market_weights,
            [View("bull", {"NVDA": 1.0}, 0.60)],
            risk_aversion=RISK_AVERSION,
        )
        impact = dict(zip(UNIVERSE, result.view_impact, strict=False))
        assert impact["NVDA"] > 0
        assert impact["MSFT"] > 0, "a correlated peer must inherit part of the view"
        assert impact["NVDA"] > impact["MSFT"]

    def test_rejects_a_mismatched_covariance(self, market_weights) -> None:
        with pytest.raises(ValueError, match="covariance must be 6x6"):
            run(UNIVERSE, np.eye(3), market_weights)

    def test_rejects_mismatched_market_weights(self, cov) -> None:
        with pytest.raises(ValueError, match="market weights must have length 6"):
            run(UNIVERSE, cov, np.array([0.5, 0.5]))

    def test_rejects_non_positive_tau(self, cov, market_weights) -> None:
        with pytest.raises(ValueError, match="tau must be positive"):
            run(UNIVERSE, cov, market_weights, tau=0.0)

    def test_payload_is_json_shaped(self, cov, market_weights) -> None:
        payload = run(
            UNIVERSE,
            cov,
            market_weights,
            [View("bull", {"NVDA": 1.0}, 0.15)],
            risk_aversion=RISK_AVERSION,
        ).to_payload()
        assert set(payload["posteriorReturns"]) == set(UNIVERSE)
        assert payload["omegaDiagonal"]["bull"] > 0
        assert payload["tau"] == pytest.approx(0.05)
