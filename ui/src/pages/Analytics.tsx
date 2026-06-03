import { useCallback, useEffect, useMemo, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer,
  Legend,
  CartesianGrid,
} from "recharts";
import { api } from "@/lib/api";
import { useAlertStore } from "@/stores/alertStore";

interface TcaRow {
  tcaId: string;
  orderId: string;
  symbol: string;
  strategy?: string;
  side: "BUY" | "SELL";
  quantity: number;
  slippageBps: number;
  implShortfallBps: number;
  vwapBenchmarkBps: number;
  spreadCostBps: number;
  computedAt: string;
}

interface PnlAttributionRow {
  strategy: string;
  date: string;
  orders: number;
  spreadUsd: number;
  timingUsd: number;
  marketUsd: number;
  commissionUsd: number;
  residualUsd: number;
  realizedPnlUsd: number;
}
interface PnlAttributionResponse {
  daily: PnlAttributionRow[];
}

interface ToxicityRow {
  strategy: string;
  horizonSeconds: number;
  meanMarkoutBps: number;
  stdevMarkoutBps: number;
  observations: number;
  toxic: boolean;
}
interface ToxicityResponse {
  rows: ToxicityRow[];
  thresholdBps: number;
  horizonsSeconds: number[];
}

interface AxeRow {
  axeId: string;
  clientId: string;
  symbol: string;
  side: "BUY" | "SELL";
  quantity: number;
  remaining: number;
  limitPrice: number | null;
  publishedAt: number;
  expiresAt: number;
  confidence: number;
  refreshCount: number;
}
interface AxesResponse {
  axes: AxeRow[];
  stats: { activeAxes: number; matchedTotalShares: number };
}

type Tab = "tca" | "pnl" | "toxicity" | "axes" | "alerts";

const TABS: { id: Tab; label: string }[] = [
  { id: "tca", label: "TCA" },
  { id: "pnl", label: "PnL attribution" },
  { id: "toxicity", label: "Flow toxicity" },
  { id: "axes", label: "Axes" },
  { id: "alerts", label: "Risk alerts" },
];

const fmtBps = (v: number | null | undefined): string => (v == null ? "—" : `${v.toFixed(2)} bps`);
const fmtUsd = (v: number): string =>
  v.toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 2 });
const pnlClass = (v: number): string =>
  v > 0 ? "text-green-700" : v < 0 ? "text-red-700" : "text-slate-700";

export default function Analytics() {
  const [tab, setTab] = useState<Tab>("tca");
  const [tcaRows, setTcaRows] = useState<TcaRow[]>([]);
  const [pnl, setPnl] = useState<PnlAttributionRow[]>([]);
  const [toxicity, setToxicity] = useState<ToxicityResponse | null>(null);
  const [axes, setAxes] = useState<AxesResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [filterSymbol, setFilterSymbol] = useState("");
  const [filterStrategy, setFilterStrategy] = useState("");

  const alerts = useAlertStore(useShallow((s) => s.alerts));

  const loadTca = useCallback(async (): Promise<void> => {
    const params = new URLSearchParams({ limit: "50" });
    if (filterSymbol) params.set("symbol", filterSymbol);
    if (filterStrategy) params.set("strategy", filterStrategy);
    try {
      const rows = await api<TcaRow[]>(`/api/tca?${params.toString()}`);
      setTcaRows(rows);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [filterSymbol, filterStrategy]);

  const loadPnl = useCallback(async (): Promise<void> => {
    try {
      const body = await api<PnlAttributionResponse>(
        filterStrategy
          ? `/api/analytics/pnl/attribution?strategy=${encodeURIComponent(filterStrategy)}`
          : "/api/analytics/pnl/attribution",
      );
      setPnl(body.daily);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [filterStrategy]);

  const loadToxicity = useCallback(async (): Promise<void> => {
    try {
      const body = await api<ToxicityResponse>("/api/analytics/flow/toxicity");
      setToxicity(body);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const loadAxes = useCallback(async (): Promise<void> => {
    const params = new URLSearchParams();
    if (filterSymbol) params.set("symbol", filterSymbol);
    const url = params.size > 0 ? `/api/analytics/axes?${params.toString()}` : "/api/analytics/axes";
    try {
      const body = await api<AxesResponse>(url);
      setAxes(body);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [filterSymbol]);

  useEffect(() => {
    if (tab === "tca") void loadTca();
    else if (tab === "pnl") void loadPnl();
    else if (tab === "toxicity") void loadToxicity();
    else if (tab === "axes") void loadAxes();
  }, [tab, loadTca, loadPnl, loadToxicity, loadAxes]);

  const chartData = useMemo(
    () =>
      pnl.map((r) => ({
        label: `${r.strategy} ${r.date}`,
        Spread: r.spreadUsd,
        Timing: r.timingUsd,
        Market: r.marketUsd,
        Commission: r.commissionUsd,
        Residual: r.residualUsd,
      })),
    [pnl],
  );

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Analytics</h1>
      <p className="text-sm text-slate-600">
        Transaction-cost analysis (TCA, Phase 1.7.1), PnL attribution (analytics 2.2.5), flow
        toxicity (2.2.4), axe matching (2.2.6) and live risk alerts streamed off Kafka (2.5.5).
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
            {t.id === "alerts" && alerts.length > 0 ? (
              <span
                data-testid="alerts-badge"
                className="ml-2 inline-flex items-center justify-center rounded-full bg-red-100 text-red-700 px-2 py-0.5 text-xs"
              >
                {alerts.length}
              </span>
            ) : null}
          </button>
        ))}
      </div>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="analytics-error">
          {error}
        </div>
      )}

      {(tab === "tca" || tab === "pnl" || tab === "axes") && (
        <div className="flex gap-3 items-end" data-testid="filters">
          <div>
            <label className="block text-sm text-slate-700">Symbol</label>
            <input
              data-testid="filter-symbol"
              className="rounded border border-slate-300 px-2 py-1"
              value={filterSymbol}
              onChange={(e) => {
                setFilterSymbol(e.target.value.toUpperCase());
              }}
            />
          </div>
          {tab !== "axes" && (
            <div>
              <label className="block text-sm text-slate-700">Strategy</label>
              <input
                data-testid="filter-strategy"
                className="rounded border border-slate-300 px-2 py-1"
                value={filterStrategy}
                onChange={(e) => {
                  setFilterStrategy(e.target.value.toUpperCase());
                }}
              />
            </div>
          )}
          <button
            data-testid="filter-apply"
            className="rounded bg-blue-600 px-3 py-1 text-white text-sm"
            onClick={() => {
              if (tab === "tca") void loadTca();
              if (tab === "pnl") void loadPnl();
              if (tab === "axes") void loadAxes();
            }}
          >
            Apply
          </button>
        </div>
      )}

      {tab === "tca" && (
        <div className="bg-white rounded shadow-sm overflow-hidden">
          <table className="min-w-full divide-y divide-slate-200" data-testid="tca-table">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-2 text-left">Order</th>
                <th className="px-4 py-2 text-left">Symbol</th>
                <th className="px-4 py-2 text-left">Strategy</th>
                <th className="px-4 py-2 text-left">Side</th>
                <th className="px-4 py-2 text-right">Qty</th>
                <th className="px-4 py-2 text-right">Slippage</th>
                <th className="px-4 py-2 text-right">IS</th>
                <th className="px-4 py-2 text-right">VWAP bench</th>
                <th className="px-4 py-2 text-right">Spread cost</th>
                <th className="px-4 py-2 text-left">Computed</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 text-sm">
              {tcaRows.length === 0 && (
                <tr>
                  <td colSpan={10} className="px-4 py-6 text-center text-slate-500">
                    No TCA records yet.
                  </td>
                </tr>
              )}
              {tcaRows.map((r) => (
                <tr key={r.tcaId}>
                  <td className="px-4 py-2 font-mono text-xs">{r.orderId.slice(0, 8)}</td>
                  <td className="px-4 py-2">{r.symbol}</td>
                  <td className="px-4 py-2">{r.strategy ?? "—"}</td>
                  <td className="px-4 py-2">{r.side}</td>
                  <td className="px-4 py-2 text-right num">{r.quantity}</td>
                  <td className="px-4 py-2 text-right num">{fmtBps(r.slippageBps)}</td>
                  <td className="px-4 py-2 text-right num">{fmtBps(r.implShortfallBps)}</td>
                  <td className="px-4 py-2 text-right num">{fmtBps(r.vwapBenchmarkBps)}</td>
                  <td className="px-4 py-2 text-right num">{fmtBps(r.spreadCostBps)}</td>
                  <td className="px-4 py-2 text-xs text-slate-500">
                    {new Date(r.computedAt).toLocaleString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {tab === "pnl" && (
        <div className="space-y-4">
          <div className="bg-white rounded shadow-sm p-4" data-testid="pnl-chart">
            {chartData.length === 0 ? (
              <div className="py-12 text-center text-slate-500">No PnL attribution data yet.</div>
            ) : (
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="label" tick={{ fontSize: 10 }} />
                  <YAxis />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="Spread" stackId="a" fill="#2563eb" />
                  <Bar dataKey="Timing" stackId="a" fill="#7c3aed" />
                  <Bar dataKey="Market" stackId="a" fill="#16a34a" />
                  <Bar dataKey="Commission" stackId="a" fill="#dc2626" />
                  <Bar dataKey="Residual" stackId="a" fill="#94a3b8" />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
          <div className="bg-white rounded shadow-sm overflow-hidden">
            <table className="min-w-full divide-y divide-slate-200" data-testid="pnl-table">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left">Date</th>
                  <th className="px-4 py-2 text-left">Strategy</th>
                  <th className="px-4 py-2 text-right">Orders</th>
                  <th className="px-4 py-2 text-right">Spread</th>
                  <th className="px-4 py-2 text-right">Timing</th>
                  <th className="px-4 py-2 text-right">Market</th>
                  <th className="px-4 py-2 text-right">Commission</th>
                  <th className="px-4 py-2 text-right">Residual</th>
                  <th className="px-4 py-2 text-right">Realized P&L</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {pnl.length === 0 && (
                  <tr>
                    <td colSpan={9} className="px-4 py-6 text-center text-slate-500">
                      Awaiting TCA events to attribute.
                    </td>
                  </tr>
                )}
                {pnl.map((r, i) => (
                  <tr key={`${r.strategy}-${r.date}-${String(i)}`}>
                    <td className="px-4 py-2">{r.date}</td>
                    <td className="px-4 py-2">{r.strategy}</td>
                    <td className="px-4 py-2 text-right num">{r.orders}</td>
                    <td className={`px-4 py-2 text-right num ${pnlClass(r.spreadUsd)}`}>
                      {fmtUsd(r.spreadUsd)}
                    </td>
                    <td className={`px-4 py-2 text-right num ${pnlClass(r.timingUsd)}`}>
                      {fmtUsd(r.timingUsd)}
                    </td>
                    <td className={`px-4 py-2 text-right num ${pnlClass(r.marketUsd)}`}>
                      {fmtUsd(r.marketUsd)}
                    </td>
                    <td className={`px-4 py-2 text-right num ${pnlClass(r.commissionUsd)}`}>
                      {fmtUsd(r.commissionUsd)}
                    </td>
                    <td className={`px-4 py-2 text-right num ${pnlClass(r.residualUsd)}`}>
                      {fmtUsd(r.residualUsd)}
                    </td>
                    <td
                      className={`px-4 py-2 text-right num font-medium ${pnlClass(r.realizedPnlUsd)}`}
                    >
                      {fmtUsd(r.realizedPnlUsd)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {tab === "toxicity" && (
        <div className="bg-white rounded shadow-sm overflow-hidden" data-testid="toxicity-section">
          {toxicity ? (
            <>
              <div className="px-4 py-2 text-xs text-slate-500 bg-slate-50 border-b border-slate-200">
                Alert threshold {toxicity.thresholdBps} bps · Horizons{" "}
                {toxicity.horizonsSeconds.join(", ")}s
              </div>
              <table className="min-w-full divide-y divide-slate-200">
                <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                  <tr>
                    <th className="px-4 py-2 text-left">Strategy</th>
                    <th className="px-4 py-2 text-right">Horizon</th>
                    <th className="px-4 py-2 text-right">Mean markout</th>
                    <th className="px-4 py-2 text-right">Stdev</th>
                    <th className="px-4 py-2 text-right">Observations</th>
                    <th className="px-4 py-2 text-left">Toxic</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 text-sm">
                  {toxicity.rows.length === 0 && (
                    <tr>
                      <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                        No toxicity samples collected yet.
                      </td>
                    </tr>
                  )}
                  {toxicity.rows.map((r, i) => (
                    <tr key={`${r.strategy}-${String(r.horizonSeconds)}-${String(i)}`}>
                      <td className="px-4 py-2">{r.strategy}</td>
                      <td className="px-4 py-2 text-right num">{r.horizonSeconds}s</td>
                      <td className={`px-4 py-2 text-right num ${pnlClass(-r.meanMarkoutBps)}`}>
                        {fmtBps(r.meanMarkoutBps)}
                      </td>
                      <td className="px-4 py-2 text-right num">{fmtBps(r.stdevMarkoutBps)}</td>
                      <td className="px-4 py-2 text-right num">{r.observations}</td>
                      <td className="px-4 py-2">
                        {r.toxic ? (
                          <span className="rounded bg-red-100 text-red-700 px-2 py-0.5 text-xs">
                            TOXIC
                          </span>
                        ) : (
                          <span className="text-slate-400 text-xs">ok</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : (
            <div className="py-12 text-center text-slate-500">Loading…</div>
          )}
        </div>
      )}

      {tab === "axes" && (
        <div className="bg-white rounded shadow-sm overflow-hidden" data-testid="axes-section">
          {axes ? (
            <>
              <div className="px-4 py-2 text-xs text-slate-500 bg-slate-50 border-b border-slate-200">
                Active axes: {axes.stats.activeAxes} · Matched lifetime:{" "}
                {axes.stats.matchedTotalShares.toLocaleString()} shares
              </div>
              <table className="min-w-full divide-y divide-slate-200">
                <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                  <tr>
                    <th className="px-4 py-2 text-left">Axe</th>
                    <th className="px-4 py-2 text-left">Client</th>
                    <th className="px-4 py-2 text-left">Symbol</th>
                    <th className="px-4 py-2 text-left">Side</th>
                    <th className="px-4 py-2 text-right">Quantity</th>
                    <th className="px-4 py-2 text-right">Remaining</th>
                    <th className="px-4 py-2 text-right">Limit</th>
                    <th className="px-4 py-2 text-right">Confidence</th>
                    <th className="px-4 py-2 text-right">Refreshes</th>
                    <th className="px-4 py-2 text-left">Expires</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 text-sm">
                  {axes.axes.length === 0 && (
                    <tr>
                      <td colSpan={10} className="px-4 py-6 text-center text-slate-500">
                        No active axes. POST to <code>/api/analytics/axes</code> to publish one.
                      </td>
                    </tr>
                  )}
                  {axes.axes.map((a) => (
                    <tr key={a.axeId}>
                      <td className="px-4 py-2 font-mono text-xs">{a.axeId}</td>
                      <td className="px-4 py-2">{a.clientId}</td>
                      <td className="px-4 py-2">{a.symbol}</td>
                      <td className="px-4 py-2">{a.side}</td>
                      <td className="px-4 py-2 text-right num">{a.quantity.toLocaleString()}</td>
                      <td className="px-4 py-2 text-right num">{a.remaining.toLocaleString()}</td>
                      <td className="px-4 py-2 text-right num">
                        {a.limitPrice == null ? "—" : a.limitPrice.toFixed(2)}
                      </td>
                      <td className="px-4 py-2 text-right num">{a.confidence.toFixed(2)}</td>
                      <td className="px-4 py-2 text-right num">{a.refreshCount}</td>
                      <td className="px-4 py-2 text-xs text-slate-500">
                        {new Date(a.expiresAt * 1000).toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          ) : (
            <div className="py-12 text-center text-slate-500">Loading…</div>
          )}
        </div>
      )}

      {tab === "alerts" && (
        <div className="bg-white rounded shadow-sm overflow-hidden" data-testid="alerts-section">
          {alerts.length === 0 ? (
            <div className="py-12 text-center text-slate-500">
              No risk alerts received. Stream live on <code>/ws/alerts</code>.
            </div>
          ) : (
            <table className="min-w-full divide-y divide-slate-200">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-4 py-2 text-left">When</th>
                  <th className="px-4 py-2 text-left">Severity</th>
                  <th className="px-4 py-2 text-left">Type</th>
                  <th className="px-4 py-2 text-left">Symbol</th>
                  <th className="px-4 py-2 text-left">Message</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-sm">
                {alerts.map((a) => (
                  <tr key={a.receivedAt}>
                    <td className="px-4 py-2 text-xs text-slate-500">
                      {new Date(a.timestamp).toLocaleString()}
                    </td>
                    <td className="px-4 py-2 font-medium">{a.severity}</td>
                    <td className="px-4 py-2">{a.alertType}</td>
                    <td className="px-4 py-2">{a.symbol || "—"}</td>
                    <td className="px-4 py-2">{a.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
