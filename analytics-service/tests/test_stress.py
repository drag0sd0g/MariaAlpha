"""Stress-scenario tests — shock construction, revaluation, breach detection, YAML loading."""

from __future__ import annotations

from pathlib import Path

import pytest

from analytics.portfolio.state import PortfolioState
from analytics.risk.stress import (
    Scenario,
    apply_scenario,
    apply_scenarios,
    load_loss_limit,
    load_scenarios,
    shock_vector,
)

from .conftest import REPO_CONFIG


@pytest.fixture
def book() -> PortfolioState:
    state = PortfolioState(base_nav=1_000_000.0)
    return state


def explicit_book(rows: list[dict[str, object]]) -> object:
    return PortfolioState(base_nav=1_000_000.0).from_explicit(rows)


class TestLoadScenarios:
    def test_loads_the_shipped_configuration(self) -> None:
        scenarios = load_scenarios(REPO_CONFIG / "stress-scenarios.yml")
        names = {s.name for s in scenarios}
        assert {"MARKET_DOWN_10", "TECH_SELLOFF", "COVID_20200316", "AI_UNWIND"} <= names

    def test_parses_factor_and_explicit_kinds(self) -> None:
        scenarios = {s.name: s for s in load_scenarios(REPO_CONFIG / "stress-scenarios.yml")}
        assert scenarios["MARKET_DOWN_10"].kind == "FACTOR"
        assert scenarios["MARKET_DOWN_10"].market_shock == pytest.approx(-0.10)
        assert scenarios["MARKET_DOWN_10"].correlation_override == pytest.approx(0.90)
        assert scenarios["TECH_SELLOFF"].kind == "EXPLICIT"
        assert scenarios["TECH_SELLOFF"].sector_shocks == {"TECH": -0.15}

    def test_missing_file_yields_no_scenarios(self, tmp_path: Path) -> None:
        assert load_scenarios(tmp_path / "nope.yml") == ()

    def test_none_path_yields_no_scenarios(self) -> None:
        assert load_scenarios(None) == ()

    def test_malformed_yaml_yields_no_scenarios(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.yml"
        bad.write_text("stress: [this: is: not: valid", encoding="utf-8")
        assert load_scenarios(bad) == ()

    def test_scenario_without_a_name_is_rejected(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.yml"
        bad.write_text("stress:\n  scenarios:\n    - kind: FACTOR\n", encoding="utf-8")
        assert load_scenarios(bad) == ()

    def test_unknown_kind_is_rejected(self, tmp_path: Path) -> None:
        bad = tmp_path / "bad.yml"
        bad.write_text(
            "stress:\n  scenarios:\n    - name: X\n      kind: SIDEWAYS\n", encoding="utf-8"
        )
        assert load_scenarios(bad) == ()

    def test_loads_the_loss_limit(self) -> None:
        assert load_loss_limit(REPO_CONFIG / "stress-scenarios.yml", 0.0) == 1_500_000.0

    def test_loss_limit_falls_back_to_the_default(self, tmp_path: Path) -> None:
        assert load_loss_limit(tmp_path / "nope.yml", 42.0) == 42.0
        assert load_loss_limit(None, 42.0) == 42.0


class TestShockVector:
    def test_explicit_symbol_shock_wins_over_the_sector(self, reference) -> None:
        scenario = Scenario(
            name="X",
            kind="EXPLICIT",
            symbol_shocks={"NVDA": -0.25},
            sector_shocks={"TECH": -0.15},
            default_shock=-0.01,
        )
        shocks = shock_vector(scenario, ("NVDA", "MSFT", "TSLA"), reference, None)
        assert shocks["NVDA"] == pytest.approx(-0.25)
        assert shocks["MSFT"] == pytest.approx(-0.15)
        assert shocks["TSLA"] == pytest.approx(-0.01)

    def test_factor_shock_scales_with_beta(self, reference) -> None:
        scenario = Scenario(name="M", kind="FACTOR", market_shock=-0.10, vol_multiplier=0.0)
        shocks = shock_vector(scenario, ("MSFT", "TSLA"), reference, None)
        # vol_multiplier = 0 removes the idiosyncratic kicker, leaving beta * market exactly.
        assert shocks["MSFT"] == pytest.approx(0.95 * -0.10, rel=1e-9)
        assert shocks["TSLA"] == pytest.approx(1.80 * -0.10, rel=1e-9)

    def test_factor_shock_adds_an_adverse_idiosyncratic_kicker(self, reference) -> None:
        base = shock_vector(
            Scenario(name="M", kind="FACTOR", market_shock=-0.10, vol_multiplier=0.0),
            ("MSFT",),
            reference,
            None,
        )
        stressed = shock_vector(
            Scenario(name="M", kind="FACTOR", market_shock=-0.10, vol_multiplier=2.0),
            ("MSFT",),
            reference,
            None,
        )
        assert stressed["MSFT"] < base["MSFT"]

    def test_short_positions_get_the_idiosyncratic_kicker_against_them(self, reference) -> None:
        import numpy as np

        scenario = Scenario(name="M", kind="FACTOR", market_shock=0.0, vol_multiplier=1.0)
        long_shock = shock_vector(scenario, ("MSFT",), reference, None, np.array([1.0]))
        short_shock = shock_vector(scenario, ("MSFT",), reference, None, np.array([-1.0]))
        assert long_shock["MSFT"] < 0
        assert short_shock["MSFT"] > 0


class TestApplyScenario:
    def test_beta_propagated_loss_matches_the_hand_calculation(self, reference) -> None:
        book = explicit_book([{"symbol": "TSLA", "quantity": 4000, "price": 250.0}])
        scenario = Scenario(name="M", kind="FACTOR", market_shock=-0.10, vol_multiplier=0.0)
        result = apply_scenario(scenario, book, reference)
        # 1,000,000 notional * beta 1.80 * -10%
        assert result.pnl_usd == pytest.approx(-180_000.0, rel=1e-9)

    def test_explicit_shock_is_applied_directly(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 2000, "price": 500.0}])
        scenario = Scenario(name="X", kind="EXPLICIT", symbol_shocks={"NVDA": -0.25})
        result = apply_scenario(scenario, book, reference)
        assert result.pnl_usd == pytest.approx(-250_000.0, rel=1e-12)

    def test_a_short_position_profits_from_a_market_selloff(self, reference) -> None:
        book = explicit_book([{"symbol": "MSFT", "quantity": -1000, "price": 400.0}])
        scenario = Scenario(name="M", kind="FACTOR", market_shock=-0.10, vol_multiplier=0.0)
        assert apply_scenario(scenario, book, reference).pnl_usd > 0

    def test_worst_symbol_is_the_largest_loser(self, reference) -> None:
        book = explicit_book(
            [
                {"symbol": "NVDA", "quantity": 2000, "price": 500.0},
                {"symbol": "MSFT", "quantity": 100, "price": 400.0},
            ]
        )
        scenario = Scenario(name="X", kind="EXPLICIT", symbol_shocks={"NVDA": -0.25, "MSFT": -0.10})
        result = apply_scenario(scenario, book, reference)
        assert result.worst_symbol == "NVDA"
        assert result.worst_symbol_pnl_usd == pytest.approx(-250_000.0, rel=1e-12)

    def test_breach_is_flagged_against_the_limit(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 20_000, "price": 500.0}])
        scenario = Scenario(name="X", kind="EXPLICIT", symbol_shocks={"NVDA": -0.25})
        assert apply_scenario(scenario, book, reference, loss_limit_usd=1_000_000.0).breaches_limit
        assert not apply_scenario(
            scenario, book, reference, loss_limit_usd=10_000_000.0
        ).breaches_limit

    def test_zero_limit_disables_breach_detection(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 20_000, "price": 500.0}])
        scenario = Scenario(name="X", kind="EXPLICIT", symbol_shocks={"NVDA": -0.90})
        assert not apply_scenario(scenario, book, reference, loss_limit_usd=0.0).breaches_limit

    def test_empty_book_yields_zero_pnl(self, reference) -> None:
        book = explicit_book([])
        result = apply_scenario(
            Scenario(name="M", kind="FACTOR", market_shock=-0.10), book, reference
        )
        assert result.pnl_usd == 0.0
        assert result.worst_symbol == ""

    def test_pnl_pct_of_nav(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 2000, "price": 500.0}])
        result = apply_scenario(
            Scenario(name="X", kind="EXPLICIT", symbol_shocks={"NVDA": -0.10}),
            book,
            reference,
        )
        assert result.pnl_pct_of_nav == pytest.approx(-100_000.0 / book.nav_usd, rel=1e-12)

    def test_payload_is_json_shaped(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 100, "price": 500.0}])
        payload = apply_scenario(
            Scenario(name="X", description="d", kind="EXPLICIT", symbol_shocks={"NVDA": -0.1}),
            book,
            reference,
        ).to_payload()
        assert payload["name"] == "X"
        assert "shocks" in payload and payload["shocks"]["NVDA"] == pytest.approx(-0.1)


class TestApplyScenarios:
    def test_results_are_sorted_worst_first(self, reference) -> None:
        book = explicit_book([{"symbol": "NVDA", "quantity": 2000, "price": 500.0}])
        scenarios = (
            Scenario(name="MILD", kind="EXPLICIT", symbol_shocks={"NVDA": -0.02}),
            Scenario(name="SEVERE", kind="EXPLICIT", symbol_shocks={"NVDA": -0.40}),
            Scenario(name="RALLY", kind="EXPLICIT", symbol_shocks={"NVDA": 0.10}),
        )
        results = apply_scenarios(scenarios, book, reference)
        assert [r.name for r in results] == ["SEVERE", "MILD", "RALLY"]

    def test_shipped_scenarios_all_evaluate(self, reference) -> None:
        book = explicit_book(
            [
                {"symbol": "NVDA", "quantity": 2000, "price": 500.0},
                {"symbol": "MSFT", "quantity": -1000, "price": 400.0},
            ]
        )
        scenarios = load_scenarios(REPO_CONFIG / "stress-scenarios.yml")
        results = apply_scenarios(scenarios, book, reference, loss_limit_usd=1_500_000.0)
        assert len(results) == len(scenarios)
        assert all(r.name for r in results)
