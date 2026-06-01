import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";

interface StrategyState {
  symbol: string;
  activeStrategy?: string;
  mlSignal?: { direction: "NEUTRAL" | "LONG" | "SHORT"; confidence: number };
  mlRegime?: {
    regime:
      | "UNKNOWN"
      | "TRENDING_UP"
      | "TRENDING_DOWN"
      | "MEAN_REVERTING"
      | "HIGH_VOLATILITY"
      | "LOW_VOLATILITY";
    confidence: number;
  };
}

// Static seed list — covers the simulator universe; users can still
// type any other symbol into the "Bind new symbol" form.
const DEFAULT_SYMBOLS = ["AAPL", "MSFT", "GOOGL", "AMZN", "TSLA", "NVDA"];

const directionClass = (d: string): string => {
  switch (d) {
    case "LONG":
      return "text-green-700";
    case "SHORT":
      return "text-red-700";
    default:
      return "text-slate-600";
  }
};
const regimeClass = (r: string): string => {
  switch (r) {
    case "TRENDING_UP":
      return "bg-green-100 text-green-800";
    case "TRENDING_DOWN":
      return "bg-red-100 text-red-800";
    case "MEAN_REVERTING":
      return "bg-blue-100 text-blue-800";
    case "HIGH_VOLATILITY":
      return "bg-amber-100 text-amber-800";
    case "LOW_VOLATILITY":
      return "bg-slate-100 text-slate-800";
    default:
      return "bg-slate-100 text-slate-600";
  }
};

export default function Strategies() {
  const [available, setAvailable] = useState<string[]>([]);
  const [rows, setRows] = useState<StrategyState[]>([]);
  const [bindSymbol, setBindSymbol] = useState("");
  const [bindStrategy, setBindStrategy] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const fetchState = useCallback(async () => {
    try {
      const states = await api<StrategyState[]>("/api/strategies/state");
      // Merge with DEFAULT_SYMBOLS to always show simulator universe even if unrouted
      const known = new Set(states.map((s) => s.symbol));
      const merged = [
        ...states,
        ...DEFAULT_SYMBOLS.filter((s) => !known.has(s)).map((symbol) => ({ symbol })),
      ];
      merged.sort((a, b) => a.symbol.localeCompare(b.symbol));
      setRows(merged);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void api<string[]>("/api/strategies")
      .then((s) => {
        setAvailable(s);
        const first = s[0];
        if (first !== undefined && !bindStrategy) setBindStrategy(first);
      })
      .catch(() => undefined);
    void fetchState();
    const id = setInterval(() => void fetchState(), 5000);
    return () => {
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const setActive = async (symbol: string, strategyName: string): Promise<void> => {
    setError(null);
    setInfo(null);
    try {
      await api<unknown>(`/api/strategies/${encodeURIComponent(symbol)}`, {
        method: "PUT",
        body: JSON.stringify({ strategyName }),
      });
      setInfo(`Bound ${symbol} → ${strategyName}`);
      void fetchState();
    } catch (e) {
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    }
  };

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Strategy Control</h1>
      <p className="text-sm text-slate-600">
        Bindings between symbols and execution strategies (FR-11). The ML signal + regime columns
        are updated every 5 s from the ML Signal Service via the Strategy Engine.
      </p>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="strategies-error">
          {error}
        </div>
      )}
      {info && (
        <div className="rounded bg-green-50 p-3 text-green-700" data-testid="strategies-info">
          {info}
        </div>
      )}

      <div
        className="bg-white rounded shadow-sm p-4 flex flex-wrap items-end gap-3"
        data-testid="bind-form"
      >
        <div>
          <label className="block text-sm text-slate-700">Symbol</label>
          <input
            data-testid="bind-symbol"
            className="rounded border border-slate-300 px-2 py-1"
            value={bindSymbol}
            onChange={(e) => {
              setBindSymbol(e.target.value.toUpperCase());
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Strategy</label>
          <select
            data-testid="bind-strategy"
            className="rounded border border-slate-300 px-2 py-1"
            value={bindStrategy}
            onChange={(e) => {
              setBindStrategy(e.target.value);
            }}
          >
            {available.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <button
          data-testid="bind-submit"
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
          disabled={!bindSymbol || !bindStrategy}
          onClick={() => {
            void setActive(bindSymbol, bindStrategy);
          }}
        >
          Bind
        </button>
      </div>

      <div className="bg-white rounded shadow-sm overflow-hidden">
        <table className="min-w-full divide-y divide-slate-200" data-testid="strategies-table">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">Symbol</th>
              <th className="px-4 py-2 text-left">Active strategy</th>
              <th className="px-4 py-2 text-left">ML signal</th>
              <th className="px-4 py-2 text-left">Regime</th>
              <th className="px-4 py-2 text-left">Switch</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-sm">
            {rows.map((row) => (
              <tr key={row.symbol} data-testid={`row-${row.symbol}`}>
                <td className="px-4 py-2 font-medium">{row.symbol}</td>
                <td className="px-4 py-2">
                  {row.activeStrategy ?? <span className="text-slate-400">—</span>}
                </td>
                <td className={`px-4 py-2 ${directionClass(row.mlSignal?.direction ?? "")}`}>
                  {row.mlSignal ? (
                    <>
                      {row.mlSignal.direction}{" "}
                      <span className="text-slate-500 text-xs">
                        ({row.mlSignal.confidence.toFixed(2)})
                      </span>
                    </>
                  ) : (
                    <span className="text-slate-400">—</span>
                  )}
                </td>
                <td className="px-4 py-2">
                  {row.mlRegime ? (
                    <span
                      className={`rounded px-2 py-0.5 text-xs ${regimeClass(row.mlRegime.regime)}`}
                    >
                      {row.mlRegime.regime} ({row.mlRegime.confidence.toFixed(2)})
                    </span>
                  ) : (
                    <span className="text-slate-400">—</span>
                  )}
                </td>
                <td className="px-4 py-2">
                  <select
                    data-testid={`switch-${row.symbol}`}
                    className="rounded border border-slate-300 px-2 py-1 text-xs"
                    value={row.activeStrategy ?? ""}
                    onChange={(e) => {
                      void setActive(row.symbol, e.target.value);
                    }}
                  >
                    <option value="" disabled>
                      —
                    </option>
                    {available.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
