import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { api } from "@/lib/api";
import type {
  ComponentVarRow,
  FactorsResponse,
  FrontierResponse,
  OptimizeResponse,
  PortfolioStateResponse,
  RebalanceResponse,
  RiskVarResponse,
  StressResponse,
} from "@/types/api";

type Tab = "optimizer" | "frontier" | "risk" | "factors" | "stress" | "rebalance";

const TABS: { id: Tab; label: string }[] = [
  { id: "optimizer", label: "Optimizer" },
  { id: "frontier", label: "Frontier" },
  { id: "risk", label: "Risk" },
  { id: "factors", label: "Factors" },
  { id: "stress", label: "Stress" },
  { id: "rebalance", label: "Rebalance" },
];

const OBJECTIVES = [
  "MIN_VARIANCE",
  "MEAN_VARIANCE",
  "MAX_SHARPE",
  "RISK_PARITY",
  "EQUAL_WEIGHT",
  "INVERSE_VOL",
] as const;

const fmtUsd = (v: number): string =>
  v.toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 0 });
const fmtPct = (v: number): string => `${(v * 100).toFixed(2)}%`;
const pnlClass = (v: number): string =>
  v > 0 ? "text-green-700" : v < 0 ? "text-red-700" : "text-slate-700";

// `note?: string | undefined` rather than `note?: string`: the project runs with
// exactOptionalPropertyTypes, under which an omitted prop and an explicit `undefined` are
// distinct, and the risk tiles pass `undefined` when a VaR method is unavailable.
function StatTile({
  label,
  value,
  note,
}: {
  label: string;
  value: string;
  note?: string | undefined;
}) {
  return (
    <div className="bg-white border border-slate-200 rounded-lg px-4 py-3 min-w-44 flex-1">
      <div className="text-xs uppercase tracking-wide text-slate-500">{label}</div>
      <div className="text-xl font-semibold tabular-nums mt-0.5">{value}</div>
      {note ? <div className="text-xs text-slate-400 mt-0.5">{note}</div> : null}
    </div>
  );
}

export default function Portfolio() {
  const [tab, setTab] = useState<Tab>("optimizer");
  const [objective, setObjective] = useState<(typeof OBJECTIVES)[number]>("RISK_PARITY");
  const [maxWeight, setMaxWeight] = useState(0.35);
  const [riskAversion, setRiskAversion] = useState(3);
  const [error, setError] = useState<string | null>(null);

  const [state, setState] = useState<PortfolioStateResponse | null>(null);
  const [optimize, setOptimize] = useState<OptimizeResponse | null>(null);
  const [frontier, setFrontier] = useState<FrontierResponse | null>(null);
  const [risk, setRisk] = useState<RiskVarResponse | null>(null);
  const [components, setComponents] = useState<ComponentVarRow[]>([]);
  const [factors, setFactors] = useState<FactorsResponse | null>(null);
  const [stress, setStress] = useState<StressResponse | null>(null);
  const [rebalance, setRebalance] = useState<RebalanceResponse | null>(null);

  const constraints = useMemo(() => ({ max_weight: maxWeight }), [maxWeight]);

  const guard = useCallback(async (run: () => Promise<void>): Promise<void> => {
    try {
      await run();
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const loadState = useCallback(
    () =>
      guard(async () => {
        setState(await api<PortfolioStateResponse>("/api/analytics/portfolio/state"));
      }),
    [guard],
  );

  const loadOptimize = useCallback(
    () =>
      guard(async () => {
        setOptimize(
          await api<OptimizeResponse>("/api/analytics/portfolio/optimize", {
            method: "POST",
            body: JSON.stringify({ objective, constraints, risk_aversion: riskAversion }),
          }),
        );
      }),
    [guard, objective, constraints, riskAversion],
  );

  const loadFrontier = useCallback(
    () =>
      guard(async () => {
        setFrontier(
          await api<FrontierResponse>("/api/analytics/portfolio/efficient-frontier", {
            method: "POST",
            body: JSON.stringify({ points: 25, constraints }),
          }),
        );
      }),
    [guard, constraints],
  );

  const loadRisk = useCallback(
    () =>
      guard(async () => {
        const [varBody, componentBody] = await Promise.all([
          api<RiskVarResponse>("/api/analytics/risk/var"),
          api<{ rows: ComponentVarRow[] }>("/api/analytics/risk/components"),
        ]);
        setRisk(varBody);
        setComponents(componentBody.rows);
      }),
    [guard],
  );

  const loadFactors = useCallback(
    () =>
      guard(async () => {
        setFactors(await api<FactorsResponse>("/api/analytics/portfolio/factors"));
      }),
    [guard],
  );

  const loadStress = useCallback(
    () =>
      guard(async () => {
        setStress(await api<StressResponse>("/api/analytics/risk/stress"));
      }),
    [guard],
  );

  const loadRebalance = useCallback(
    () =>
      guard(async () => {
        setRebalance(
          await api<RebalanceResponse>("/api/analytics/portfolio/rebalance", {
            method: "POST",
            body: JSON.stringify({ objective, constraints, risk_aversion: riskAversion }),
          }),
        );
      }),
    [guard, objective, constraints, riskAversion],
  );

  useEffect(() => {
    void loadState();
  }, [loadState]);

  useEffect(() => {
    if (tab === "optimizer") void loadOptimize();
    else if (tab === "frontier") void loadFrontier();
    else if (tab === "risk") void loadRisk();
    else if (tab === "factors") void loadFactors();
    else if (tab === "stress") void loadStress();
    else void loadRebalance();
  }, [tab, loadOptimize, loadFrontier, loadRisk, loadFactors, loadStress, loadRebalance]);

  const currentWeights = useMemo(() => {
    if (!state || state.navUsd <= 0) return {};
    return Object.fromEntries(state.positions.map((p) => [p.symbol, p.notionalUsd / state.navUsd]));
  }, [state]);

  const weightChart = useMemo(() => {
    if (!optimize) return [];
    return Object.entries(optimize.weights).map(([symbol, target]) => ({
      symbol,
      Target: target,
      Current: currentWeights[symbol] ?? 0,
    }));
  }, [optimize, currentWeights]);

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Portfolio</h1>
      <p className="text-sm text-slate-600">
        Mean-variance and risk-parity construction, Black-Litterman equilibrium returns, factor
        exposures, stress scenarios, and a covariance-based firm-wide risk engine. Targets feed
        straight into the basket-trading service.
      </p>

      <div className="flex items-center gap-2 border-b border-slate-200">
        {TABS.map((t) => (
          <button
            key={t.id}
            data-testid={`tab-${t.id}`}
            className={`px-3 py-2 text-sm border-b-2 -mb-px ${
              tab === t.id
                ? "border-blue-600 text-blue-600 font-semibold"
                : "border-transparent text-slate-600 hover:text-slate-900"
            }`}
            onClick={() => {
              setTab(t.id);
            }}
          >
            {t.label}
          </button>
        ))}
      </div>

      {error ? (
        <div
          data-testid="portfolio-error"
          className="bg-red-50 border border-red-200 text-red-800 text-sm rounded px-3 py-2"
        >
          {error}
        </div>
      ) : null}

      {state ? (
        <div className="flex flex-wrap gap-3">
          <StatTile label="NAV" value={fmtUsd(state.navUsd)} note={state.navSource} />
          <StatTile label="Gross exposure" value={fmtUsd(state.grossExposureUsd)} />
          <StatTile label="Net exposure" value={fmtUsd(state.netExposureUsd)} />
          <StatTile label="Positions" value={String(state.positionCount)} />
        </div>
      ) : null}

      {(tab === "optimizer" || tab === "frontier" || tab === "rebalance") && (
        <div className="flex flex-wrap items-end gap-3 bg-white border border-slate-200 rounded-lg p-3">
          <label className="text-sm">
            <span className="block text-xs text-slate-500 mb-1">Objective</span>
            <select
              data-testid="objective-select"
              className="border border-slate-300 rounded px-2 py-1 text-sm"
              value={objective}
              onChange={(e) => {
                setObjective(e.target.value as (typeof OBJECTIVES)[number]);
              }}
            >
              {OBJECTIVES.map((o) => (
                <option key={o} value={o}>
                  {o}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="block text-xs text-slate-500 mb-1">Max weight</span>
            <input
              data-testid="max-weight-input"
              type="number"
              step="0.05"
              min="0.05"
              max="1"
              className="border border-slate-300 rounded px-2 py-1 text-sm w-24"
              value={maxWeight}
              onChange={(e) => {
                setMaxWeight(Number(e.target.value));
              }}
            />
          </label>
          <label className="text-sm">
            <span className="block text-xs text-slate-500 mb-1">Risk aversion</span>
            <input
              data-testid="risk-aversion-input"
              type="number"
              step="0.5"
              min="0.5"
              className="border border-slate-300 rounded px-2 py-1 text-sm w-24"
              value={riskAversion}
              onChange={(e) => {
                setRiskAversion(Number(e.target.value));
              }}
            />
          </label>
        </div>
      )}

      {tab === "optimizer" && optimize ? (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <StatTile label="Expected return" value={fmtPct(optimize.expectedReturn)} />
            <StatTile label="Volatility" value={fmtPct(optimize.volatility)} />
            <StatTile label="Sharpe" value={optimize.sharpe.toFixed(3)} />
            <StatTile
              label="Effective N"
              value={optimize.effectiveN.toFixed(2)}
              note="1 / sum of squared weights"
            />
            <StatTile
              label="Diversification"
              value={`${optimize.diversificationRatio.toFixed(2)}x`}
            />
          </div>
          {!optimize.converged ? (
            <div
              data-testid="not-converged"
              className="bg-amber-50 border border-amber-200 text-amber-800 text-sm rounded px-3 py-2"
            >
              Solver did not converge: {optimize.message}
            </div>
          ) : null}
          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <h2 className="text-sm font-semibold mb-2">Current vs target weights</h2>
            <ResponsiveContainer width="100%" height={280}>
              <BarChart data={weightChart}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="symbol" />
                <YAxis tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`} />
                <Tooltip formatter={(v: number) => fmtPct(v)} />
                <Legend />
                <Bar dataKey="Current" fill="#94a3b8" />
                <Bar dataKey="Target" fill="#2563eb" />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <h2 className="text-sm font-semibold mb-2">Risk contributions</h2>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs uppercase text-slate-500 border-b border-slate-200">
                  <th className="text-left py-1">Symbol</th>
                  <th className="text-right py-1">Weight</th>
                  <th className="text-right py-1">Risk contribution</th>
                </tr>
              </thead>
              <tbody data-testid="risk-contributions">
                {Object.entries(optimize.riskContributions).map(([symbol, rc]) => (
                  <tr key={symbol} className="border-b border-slate-100">
                    <td className="py-1">{symbol}</td>
                    <td className="text-right tabular-nums">
                      {fmtPct(optimize.weights[symbol] ?? 0)}
                    </td>
                    <td className="text-right tabular-nums">{rc.toFixed(6)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}

      {tab === "frontier" && frontier ? (
        <div className="bg-white border border-slate-200 rounded-lg p-3">
          <h2 className="text-sm font-semibold mb-2">Efficient frontier</h2>
          <ResponsiveContainer width="100%" height={320}>
            <ScatterChart>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis
                type="number"
                dataKey="volatility"
                name="Volatility"
                tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`}
              />
              <YAxis
                type="number"
                dataKey="expectedReturn"
                name="Expected return"
                tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`}
              />
              <Tooltip formatter={(v: number) => fmtPct(v)} />
              <Scatter data={frontier.points} fill="#2563eb" />
            </ScatterChart>
          </ResponsiveContainer>
        </div>
      ) : null}

      {tab === "risk" && risk ? (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <StatTile
              label="Parametric VaR"
              value={fmtUsd(risk.parametric.varUsd)}
              note={`${(risk.confidence * 100).toFixed(0)}% / ${String(risk.horizonDays)}d`}
            />
            <StatTile
              label="Historical VaR"
              value={risk.historical ? fmtUsd(risk.historical.varUsd) : "—"}
              note={
                risk.historical
                  ? `${String(risk.historical.observations ?? 0)} obs${
                      risk.historical.sufficient ? "" : " — insufficient"
                    }`
                  : undefined
              }
            />
            <StatTile
              label="Monte-Carlo VaR"
              value={risk.monteCarlo ? fmtUsd(risk.monteCarlo.varUsd) : "—"}
              note={
                risk.monteCarlo ? `${String(risk.monteCarlo.simulations ?? 0)} paths` : undefined
              }
            />
            <StatTile
              label="Expected shortfall"
              value={fmtUsd(risk.parametric.expectedShortfallUsd)}
              note="Gaussian, parametric"
            />
          </div>

          <div
            data-testid="diversification-callout"
            className="bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-3 text-sm"
          >
            <strong>Diversification credit: {risk.diversificationRatio.toFixed(2)}x</strong>
            <div className="text-slate-700 mt-1">
              The pre-4.6.1 sum-of-absolutes aggregation would report{" "}
              <strong>{fmtUsd(risk.sumOfAbsolutesVarUsd)}</strong> for this book; the covariance
              model reports <strong>{fmtUsd(risk.parametric.varUsd)}</strong>. The difference is the
              diversification the old model refused to credit.
            </div>
          </div>

          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <h2 className="text-sm font-semibold mb-1">Component VaR (Euler allocation)</h2>
            <p className="text-xs text-slate-500 mb-2">
              Components sum exactly to portfolio VaR. A negative component means the position is
              reducing total risk — a genuine hedge.
            </p>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs uppercase text-slate-500 border-b border-slate-200">
                  <th className="text-left py-1">Symbol</th>
                  <th className="text-right py-1">Notional</th>
                  <th className="text-right py-1">Standalone VaR</th>
                  <th className="text-right py-1">Component VaR</th>
                  <th className="text-right py-1">% of total</th>
                </tr>
              </thead>
              <tbody data-testid="component-var">
                {components.map((row) => (
                  <tr key={row.symbol} className="border-b border-slate-100">
                    <td className="py-1">{row.symbol}</td>
                    <td className={`text-right tabular-nums ${pnlClass(row.notionalUsd)}`}>
                      {fmtUsd(row.notionalUsd)}
                    </td>
                    <td className="text-right tabular-nums">{fmtUsd(row.standaloneVarUsd)}</td>
                    <td className={`text-right tabular-nums ${pnlClass(-row.componentVarUsd)}`}>
                      {fmtUsd(row.componentVarUsd)}
                    </td>
                    <td className="text-right tabular-nums">{fmtPct(row.pctOfTotal)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}

      {tab === "factors" && factors ? (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <StatTile label="Systematic" value={fmtPct(factors.systematicVariancePct)} />
            <StatTile label="Idiosyncratic" value={fmtPct(factors.idiosyncraticVariancePct)} />
            <StatTile
              label="Model fit"
              value={`${factors.modelFit.toFixed(2)}x`}
              note="model variance / covariance variance"
            />
            <StatTile label="Volatility" value={fmtPct(factors.covarianceVolatility)} />
          </div>
          {factors.notes.length > 0 ? (
            <div
              data-testid="factor-notes"
              className="bg-amber-50 border border-amber-200 text-amber-900 text-sm rounded px-3 py-2 space-y-1"
            >
              {factors.notes.map((note) => (
                <div key={note}>{note}</div>
              ))}
            </div>
          ) : null}
          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <h2 className="text-sm font-semibold mb-2">Factor exposures</h2>
            <ResponsiveContainer width="100%" height={260}>
              <BarChart
                data={Object.entries(factors.exposures).map(([factor, value]) => ({
                  factor,
                  value,
                }))}
              >
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="factor" tick={{ fontSize: 11 }} />
                <YAxis />
                <Tooltip />
                <Bar dataKey="value">
                  {Object.entries(factors.exposures).map(([factor, value]) => (
                    <Cell key={factor} fill={value >= 0 ? "#2563eb" : "#dc2626"} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <h2 className="text-sm font-semibold mb-2">PCA — variance explained</h2>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart
                data={factors.pca.varianceExplained.map((v, i) => ({
                  component: `PC${String(i + 1)}`,
                  value: v,
                }))}
              >
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="component" />
                <YAxis tickFormatter={(v: number) => `${(v * 100).toFixed(0)}%`} />
                <Tooltip formatter={(v: number) => fmtPct(v)} />
                <Bar dataKey="value" fill="#7c3aed" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      ) : null}

      {tab === "stress" && stress ? (
        <div className="bg-white border border-slate-200 rounded-lg p-3">
          <h2 className="text-sm font-semibold mb-2">
            Stress scenarios — loss limit {fmtUsd(stress.lossLimitUsd)}
          </h2>
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs uppercase text-slate-500 border-b border-slate-200">
                <th className="text-left py-1">Scenario</th>
                <th className="text-right py-1">P&amp;L</th>
                <th className="text-right py-1">% of NAV</th>
                <th className="text-left py-1 pl-4">Worst symbol</th>
                <th className="text-right py-1"></th>
              </tr>
            </thead>
            <tbody data-testid="stress-rows">
              {stress.scenarios.map((s) => (
                <tr key={s.name} className="border-b border-slate-100">
                  <td className="py-1" title={s.description}>
                    {s.name}
                  </td>
                  <td className={`text-right tabular-nums ${pnlClass(s.pnlUsd)}`}>
                    {fmtUsd(s.pnlUsd)}
                  </td>
                  <td className="text-right tabular-nums">{fmtPct(s.pnlPctOfNav)}</td>
                  <td className="pl-4">{s.worstSymbol}</td>
                  <td className="text-right">
                    {s.breachesLimit ? (
                      <span className="bg-red-100 text-red-800 text-xs font-semibold px-1.5 py-0.5 rounded">
                        BREACH
                      </span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {tab === "rebalance" && rebalance ? (
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <StatTile label="Turnover" value={fmtPct(rebalance.turnoverPct)} />
            <StatTile label="Estimated cost" value={fmtUsd(rebalance.estimatedCostUsd)} />
            <StatTile label="Legs" value={String(rebalance.legs.length)} />
            <StatTile label="Suppressed" value={String(rebalance.suppressedLegs.length)} />
          </div>
          <div className="bg-white border border-slate-200 rounded-lg p-3">
            <div className="flex items-center justify-between mb-2">
              <h2 className="text-sm font-semibold">Trade blotter</h2>
              <button
                data-testid="send-basket"
                disabled={!rebalance.submitEnabled}
                title={
                  rebalance.submitEnabled
                    ? "Submit this basket to the execution engine"
                    : "Submission is disabled — set ANALYTICS_REBALANCE_SUBMIT_ENABLED=true"
                }
                className="text-sm px-3 py-1 rounded bg-blue-600 text-white disabled:bg-slate-300 disabled:text-slate-500"
              >
                Send as basket
              </button>
            </div>
            <table className="w-full text-sm">
              <thead>
                <tr className="text-xs uppercase text-slate-500 border-b border-slate-200">
                  <th className="text-left py-1">Symbol</th>
                  <th className="text-left py-1">Side</th>
                  <th className="text-right py-1">Shares</th>
                  <th className="text-right py-1">Notional</th>
                  <th className="text-right py-1">Est. cost</th>
                </tr>
              </thead>
              <tbody data-testid="rebalance-legs">
                {rebalance.legs.map((leg) => (
                  <tr key={leg.symbol} className="border-b border-slate-100">
                    <td className="py-1">{leg.symbol}</td>
                    <td className={leg.side === "BUY" ? "text-green-700" : "text-red-700"}>
                      {leg.side}
                    </td>
                    <td className="text-right tabular-nums">{leg.deltaShares}</td>
                    <td className="text-right tabular-nums">{fmtUsd(leg.notionalUsd)}</td>
                    <td className="text-right tabular-nums">{fmtUsd(leg.totalCostUsd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {rebalance.suppressedLegs.length > 0 ? (
              <details className="mt-3 text-xs text-slate-600">
                <summary className="cursor-pointer">
                  {rebalance.suppressedLegs.length} leg(s) suppressed by the no-trade band
                </summary>
                <ul className="mt-1 pl-4 list-disc">
                  {rebalance.suppressedLegs.map((leg) => (
                    <li key={leg.symbol}>
                      {leg.symbol} — {leg.deltaShares} shares, {fmtUsd(leg.notionalUsd)}
                    </li>
                  ))}
                </ul>
              </details>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
