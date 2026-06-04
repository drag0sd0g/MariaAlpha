import { useState } from "react";
import { api, ApiError } from "@/lib/api";

interface RfqBreakdown {
  inventoryNetQuantity: number;
  inventoryNotionalUsd: number;
  inventorySkewBps: number;
  realizedVolBps: number;
  volWideningBps: number;
  advParticipationFraction: number;
  advWideningBps: number;
  baseHalfSpreadBps: number;
  totalHalfSpreadBps: number;
  advShares: number;
}

interface RfqQuote {
  quoteId: string;
  symbol: string;
  quantity: number;
  marketMid: number;
  adjustedMid: number;
  bid: number;
  ask: number;
  breakdown: RfqBreakdown;
  issuedAt: string;
  expiresAt: string;
  validForMs: number;
}

type Side = "BUY" | "SELL";

const inputClass =
  "w-full rounded border border-slate-300 px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-400";

function fmtBps(v: number) {
  return `${v.toFixed(3)} bps`;
}
function fmt(v: number, digits = 4) {
  return v.toFixed(digits);
}

export default function Rfq() {
  const [symbol, setSymbol] = useState("AAPL");
  const [quantity, setQuantity] = useState(100);
  const [quote, setQuote] = useState<RfqQuote | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [acceptStatus, setAcceptStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const requestQuote = async () => {
    setError(null);
    setAcceptStatus(null);
    setBusy(true);
    try {
      const q = await api<RfqQuote>("/api/rfq/quote", {
        method: "POST",
        body: JSON.stringify({ symbol, quantity }),
      });
      setQuote(q);
    } catch (e) {
      setQuote(null);
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  const accept = async (side: Side) => {
    if (!quote) return;
    setError(null);
    setAcceptStatus(null);
    setBusy(true);
    const price = side === "BUY" ? quote.ask : quote.bid;
    try {
      const resp = await api<{ status: string }>("/api/rfq/accept", {
        method: "POST",
        body: JSON.stringify({ quoteId: quote.quoteId, side, price }),
      });
      setAcceptStatus(
        `Order signal published — ${side} ${String(quote.quantity)} ${quote.symbol} @ ${String(price)} (${resp.status})`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">RFQ Pricing</h1>
      <p className="text-sm text-slate-600">
        Two-way quote built from the current book, skewed for desk inventory and widened for
        volatility and order size relative to ADV. The quote is valid for the trader to accept
        either side at the published price.
      </p>

      <div className="flex flex-wrap gap-3 items-end">
        <div>
          <label className="block text-sm text-slate-700">Symbol</label>
          <input
            data-testid="rfq-symbol"
            className={inputClass}
            value={symbol}
            onChange={(e) => {
              setSymbol(e.target.value.toUpperCase());
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Quantity</label>
          <input
            data-testid="rfq-quantity"
            type="number"
            className={inputClass}
            value={quantity}
            min={1}
            onChange={(e) => {
              setQuantity(Number(e.target.value));
            }}
          />
        </div>
        <button
          data-testid="rfq-request"
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
          onClick={() => {
            void requestQuote();
          }}
          disabled={busy || !symbol || quantity <= 0}
        >
          {busy ? "…" : "Request quote"}
        </button>
      </div>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="rfq-error">
          {error}
        </div>
      )}
      {acceptStatus && (
        <div className="rounded bg-green-50 p-3 text-green-700" data-testid="rfq-accepted">
          {acceptStatus}
        </div>
      )}

      {quote && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div className="rounded border border-slate-200 p-4">
            <div className="text-sm uppercase text-slate-500">Two-way quote</div>
            <div className="mt-2 grid grid-cols-2 gap-2">
              <div>
                <div className="text-xs text-slate-500">Bid (sell to us)</div>
                <div className="text-2xl font-mono text-red-700" data-testid="rfq-bid">
                  {fmt(quote.bid)}
                </div>
                <button
                  data-testid="rfq-accept-sell"
                  className="mt-2 rounded bg-red-600 px-3 py-1 text-white disabled:opacity-50"
                  onClick={() => {
                    void accept("SELL");
                  }}
                  disabled={busy}
                >
                  SELL {String(quote.quantity)} @ {fmt(quote.bid)}
                </button>
              </div>
              <div>
                <div className="text-xs text-slate-500">Ask (buy from us)</div>
                <div className="text-2xl font-mono text-green-700" data-testid="rfq-ask">
                  {fmt(quote.ask)}
                </div>
                <button
                  data-testid="rfq-accept-buy"
                  className="mt-2 rounded bg-green-600 px-3 py-1 text-white disabled:opacity-50"
                  onClick={() => {
                    void accept("BUY");
                  }}
                  disabled={busy}
                >
                  BUY {String(quote.quantity)} @ {fmt(quote.ask)}
                </button>
              </div>
            </div>
            <div className="mt-3 text-xs text-slate-500">
              Market mid {fmt(quote.marketMid)} → adjusted mid {fmt(quote.adjustedMid)}. Valid for{" "}
              {Math.round(quote.validForMs / 1000)}s.
            </div>
          </div>

          <div className="rounded border border-slate-200 p-4" data-testid="rfq-breakdown">
            <div className="text-sm uppercase text-slate-500">Spread breakdown</div>
            <table className="mt-2 w-full text-sm">
              <tbody>
                <tr>
                  <td className="text-slate-600">Base half-spread</td>
                  <td className="text-right font-mono">
                    {fmtBps(quote.breakdown.baseHalfSpreadBps)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Inventory skew on mid</td>
                  <td className="text-right font-mono">
                    {fmtBps(quote.breakdown.inventorySkewBps)}
                  </td>
                </tr>
                <tr>
                  <td className="text-slate-600">Realized vol</td>
                  <td className="text-right font-mono">{fmtBps(quote.breakdown.realizedVolBps)}</td>
                </tr>
                <tr>
                  <td className="text-slate-600">+ Vol widening</td>
                  <td className="text-right font-mono">{fmtBps(quote.breakdown.volWideningBps)}</td>
                </tr>
                <tr>
                  <td className="text-slate-600">
                    + ADV widening ({(quote.breakdown.advParticipationFraction * 100).toFixed(4)}%
                    of {String(quote.breakdown.advShares)}sh ADV)
                  </td>
                  <td className="text-right font-mono">{fmtBps(quote.breakdown.advWideningBps)}</td>
                </tr>
                <tr className="border-t border-slate-200">
                  <td className="font-semibold">Total half-spread</td>
                  <td className="text-right font-mono font-semibold" data-testid="rfq-total-bps">
                    {fmtBps(quote.breakdown.totalHalfSpreadBps)}
                  </td>
                </tr>
                <tr className="text-xs text-slate-500">
                  <td>Current inventory: {String(quote.breakdown.inventoryNetQuantity)} sh</td>
                  <td className="text-right">
                    ≈ ${fmt(quote.breakdown.inventoryNotionalUsd, 0)} notional
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
