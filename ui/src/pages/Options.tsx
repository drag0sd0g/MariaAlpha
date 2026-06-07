import { useState } from "react";
import { api, ApiError } from "@/lib/api";

type OptionType = "CALL" | "PUT";

interface Greeks {
  delta: number;
  gamma: number;
  vega: number;
  theta: number;
  rho: number;
}

interface PricingResponse {
  symbol: string;
  type: OptionType;
  price: number;
  greeks: Greeks;
}

interface ImpliedVolResponse {
  symbol: string;
  type: OptionType;
  impliedVolatility: number;
  iterations: number;
  method: "NEWTON" | "BISECTION";
  residual: number;
}

const inputClass =
  "w-full rounded border border-slate-300 px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-400";

function fmt(v: number, digits = 4) {
  return v.toFixed(digits);
}

function fmtSigned(v: number, digits = 4) {
  return (v >= 0 ? "+" : "") + v.toFixed(digits);
}

export default function Options() {
  const [symbol, setSymbol] = useState("AAPL");
  const [spot, setSpot] = useState(100);
  const [strike, setStrike] = useState(100);
  const [timeToExpiry, setTimeToExpiry] = useState(0.5);
  const [volatility, setVolatility] = useState(0.25);
  const [riskFreeRate, setRiskFreeRate] = useState(0.04);
  const [dividendYield, setDividendYield] = useState(0);
  const [type, setType] = useState<OptionType>("CALL");
  const [marketPrice, setMarketPrice] = useState(0);

  const [pricing, setPricing] = useState<PricingResponse | null>(null);
  const [iv, setIv] = useState<ImpliedVolResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const buildBody = () => ({
    symbol,
    spot,
    strike,
    timeToExpiryYears: timeToExpiry,
    volatility,
    riskFreeRate,
    dividendYield,
    type,
  });

  const requestPrice = async () => {
    setError(null);
    setBusy(true);
    try {
      const resp = await api<PricingResponse>("/api/options/price", {
        method: "POST",
        body: JSON.stringify(buildBody()),
      });
      setPricing(resp);
    } catch (e) {
      setPricing(null);
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const solveIv = async () => {
    setError(null);
    setBusy(true);
    try {
      const body = {
        symbol,
        spot,
        strike,
        timeToExpiryYears: timeToExpiry,
        riskFreeRate,
        dividendYield,
        type,
        marketPrice,
      };
      const resp = await api<ImpliedVolResponse>("/api/options/implied-volatility", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setIv(resp);
    } catch (e) {
      setIv(null);
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Options Pricing</h1>
      <p className="text-sm text-slate-600">
        Black-Scholes-Merton fair value plus first-order Greeks (Δ, Γ, vega, θ, ρ) for a European
        option on a single underlying with a continuous dividend yield. Inputs are quoted as
        decimals (e.g. {String(0.25)} for 25% vol, {String(0.04)} for a 4% rate).
      </p>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div>
          <label className="block text-sm text-slate-700">Symbol</label>
          <input
            data-testid="opt-symbol"
            className={inputClass}
            value={symbol}
            onChange={(e) => {
              setSymbol(e.target.value.toUpperCase());
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Type</label>
          <select
            data-testid="opt-type"
            className={inputClass}
            value={type}
            onChange={(e) => {
              setType(e.target.value as OptionType);
            }}
          >
            <option value="CALL">CALL</option>
            <option value="PUT">PUT</option>
          </select>
        </div>
        <div>
          <label className="block text-sm text-slate-700">Spot (S)</label>
          <input
            data-testid="opt-spot"
            type="number"
            className={inputClass}
            value={spot}
            min={0}
            step="any"
            onChange={(e) => {
              setSpot(Number(e.target.value));
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Strike (K)</label>
          <input
            data-testid="opt-strike"
            type="number"
            className={inputClass}
            value={strike}
            min={0}
            step="any"
            onChange={(e) => {
              setStrike(Number(e.target.value));
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">T (years)</label>
          <input
            data-testid="opt-expiry"
            type="number"
            className={inputClass}
            value={timeToExpiry}
            min={0}
            step="any"
            onChange={(e) => {
              setTimeToExpiry(Number(e.target.value));
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">σ (vol)</label>
          <input
            data-testid="opt-vol"
            type="number"
            className={inputClass}
            value={volatility}
            min={0}
            step="any"
            onChange={(e) => {
              setVolatility(Number(e.target.value));
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">r (rate)</label>
          <input
            data-testid="opt-rate"
            type="number"
            className={inputClass}
            value={riskFreeRate}
            step="any"
            onChange={(e) => {
              setRiskFreeRate(Number(e.target.value));
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">q (div yield)</label>
          <input
            data-testid="opt-div"
            type="number"
            className={inputClass}
            value={dividendYield}
            min={0}
            step="any"
            onChange={(e) => {
              setDividendYield(Number(e.target.value));
            }}
          />
        </div>
      </div>

      <div className="flex flex-wrap gap-3 items-end">
        <button
          data-testid="opt-price-btn"
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
          onClick={() => {
            void requestPrice();
          }}
          disabled={busy}
        >
          {busy ? "…" : "Price + Greeks"}
        </button>
        <div className="flex items-end gap-2">
          <div>
            <label className="block text-sm text-slate-700">Market price (for IV)</label>
            <input
              data-testid="opt-market-price"
              type="number"
              className={inputClass}
              value={marketPrice}
              min={0}
              step="any"
              onChange={(e) => {
                setMarketPrice(Number(e.target.value));
              }}
            />
          </div>
          <button
            data-testid="opt-iv-btn"
            className="rounded bg-emerald-600 px-4 py-2 text-white disabled:opacity-50"
            onClick={() => {
              void solveIv();
            }}
            disabled={busy || marketPrice <= 0}
          >
            Implied vol
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="opt-error">
          {error}
        </div>
      )}

      {pricing && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="rounded border border-slate-200 p-4">
            <div className="text-sm uppercase text-slate-500">Fair value</div>
            <div className="mt-2 text-3xl font-mono" data-testid="opt-price">
              ${fmt(pricing.price, 4)}
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {pricing.type} {pricing.symbol} on Black-Scholes-Merton with continuous q.
            </div>
          </div>
          <div className="rounded border border-slate-200 p-4" data-testid="opt-greeks">
            <div className="text-sm uppercase text-slate-500">Greeks</div>
            <table className="mt-2 w-full text-sm">
              <tbody>
                <tr>
                  <td className="text-slate-600">Delta (Δ)</td>
                  <td className="text-right font-mono" data-testid="opt-delta">
                    {fmtSigned(pricing.greeks.delta)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Gamma (Γ)</td>
                  <td className="text-right font-mono" data-testid="opt-gamma">
                    {fmtSigned(pricing.greeks.gamma)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Vega (per 1% σ)</td>
                  <td className="text-right font-mono" data-testid="opt-vega">
                    {fmtSigned(pricing.greeks.vega)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Theta (per day)</td>
                  <td className="text-right font-mono" data-testid="opt-theta">
                    {fmtSigned(pricing.greeks.theta)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Rho (per 1% r)</td>
                  <td className="text-right font-mono" data-testid="opt-rho">
                    {fmtSigned(pricing.greeks.rho)}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      )}

      {iv && (
        <div className="rounded border border-slate-200 p-4" data-testid="opt-iv">
          <div className="text-sm uppercase text-slate-500">Implied volatility</div>
          <div className="mt-2 text-2xl font-mono" data-testid="opt-iv-value">
            {(iv.impliedVolatility * 100).toFixed(2)}%
          </div>
          <div className="mt-1 text-xs text-slate-500">
            Converged in {String(iv.iterations)} iterations via {iv.method} (residual{" "}
            {iv.residual.toExponential(2)}).
          </div>
        </div>
      )}
    </div>
  );
}
