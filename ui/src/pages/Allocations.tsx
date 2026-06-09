import { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";

type AllocationMethod = "PRO_RATA" | "FIFO";
type Side = "BUY" | "SELL";

interface SubAccountRow {
  name: string;
  weight: number;
}

interface SubAccountRoster {
  defaultMethod: AllocationMethod;
  subAccounts: SubAccountRow[];
}

interface Allocation {
  allocationId: string;
  orderId: string;
  subAccount: string;
  symbol: string;
  side: Side;
  allocatedQuantity: string;
  allocatedAvgPrice: string;
  allocationMethod: AllocationMethod;
  parentFilledQuantity: string;
  parentAvgPrice: string;
  allocatedAt: string;
}

const inputClass =
  "w-full rounded border border-slate-300 px-2 py-1 focus:outline-none focus:ring-2 focus:ring-blue-400";

export default function Allocations() {
  const [roster, setRoster] = useState<SubAccountRoster | null>(null);
  const [orderId, setOrderId] = useState("");
  const [symbol, setSymbol] = useState("AAPL");
  const [side, setSide] = useState<Side>("BUY");
  const [quantity, setQuantity] = useState("1000");
  const [avgPrice, setAvgPrice] = useState("178.42");
  const [method, setMethod] = useState<AllocationMethod | "">("");
  const [allocations, setAllocations] = useState<Allocation[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const loadRoster = useCallback(async () => {
    try {
      const r = await api<SubAccountRoster>("/api/allocations/sub-accounts");
      setRoster(r);
    } catch (e) {
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    }
  }, []);

  useEffect(() => {
    void loadRoster();
  }, [loadRoster]);

  const run = async () => {
    setError(null);
    setAllocations(null);
    setBusy(true);
    try {
      const body: Record<string, unknown> = {
        orderId: orderId || crypto.randomUUID(),
        symbol,
        side,
        parentFilledQuantity: Number(quantity),
        parentAvgPrice: Number(avgPrice),
      };
      if (method !== "") body.method = method;
      const resp = await api<Allocation[]>("/api/allocations/run", {
        method: "POST",
        body: JSON.stringify(body),
      });
      setAllocations(resp);
    } catch (e) {
      setError(e instanceof ApiError ? `${String(e.status)}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Trade Allocations</h1>
      <p className="text-sm text-slate-600">
        Splits a filled parent order across the configured sub-accounts. PRO_RATA divides by weights
        with the remainder going to the heaviest account; FIFO fills in declaration order until the
        parent quantity is exhausted.
      </p>

      {roster && (
        <div className="rounded border border-slate-200 p-4" data-testid="alloc-roster">
          <div className="text-sm uppercase text-slate-500">
            Sub-account roster (default: {roster.defaultMethod})
          </div>
          <table className="mt-2 w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500">
                <th>Name</th>
                <th className="text-right">Weight</th>
              </tr>
            </thead>
            <tbody>
              {roster.subAccounts.map((s) => (
                <tr key={s.name} className="border-t border-slate-200">
                  <td className="py-1">{s.name}</td>
                  <td className="py-1 text-right font-mono">{s.weight.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div>
          <label className="block text-sm text-slate-700">Parent orderId (optional)</label>
          <input
            data-testid="alloc-order-id"
            className={inputClass}
            value={orderId}
            placeholder="auto-generated when blank"
            onChange={(e) => {
              setOrderId(e.target.value);
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Symbol</label>
          <input
            data-testid="alloc-symbol"
            className={inputClass}
            value={symbol}
            onChange={(e) => {
              setSymbol(e.target.value.toUpperCase());
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Side</label>
          <select
            data-testid="alloc-side"
            className={inputClass}
            value={side}
            onChange={(e) => {
              setSide(e.target.value as Side);
            }}
          >
            <option>BUY</option>
            <option>SELL</option>
          </select>
        </div>
        <div>
          <label className="block text-sm text-slate-700">Quantity</label>
          <input
            data-testid="alloc-quantity"
            type="number"
            className={inputClass}
            value={quantity}
            min={1}
            onChange={(e) => {
              setQuantity(e.target.value);
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Parent avg price</label>
          <input
            data-testid="alloc-price"
            type="number"
            step="0.01"
            className={inputClass}
            value={avgPrice}
            onChange={(e) => {
              setAvgPrice(e.target.value);
            }}
          />
        </div>
        <div>
          <label className="block text-sm text-slate-700">Method</label>
          <select
            data-testid="alloc-method"
            className={inputClass}
            value={method}
            onChange={(e) => {
              setMethod(e.target.value as AllocationMethod | "");
            }}
          >
            <option value="">(use default)</option>
            <option value="PRO_RATA">PRO_RATA</option>
            <option value="FIFO">FIFO</option>
          </select>
        </div>
      </div>

      <button
        data-testid="alloc-run"
        className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
        onClick={() => {
          void run();
        }}
        disabled={busy || !symbol || Number(quantity) <= 0}
      >
        {busy ? "…" : "Allocate"}
      </button>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="alloc-error">
          {error}
        </div>
      )}

      {allocations && (
        <div className="rounded border border-slate-200 p-4" data-testid="alloc-results">
          <div className="text-sm uppercase text-slate-500">Allocations ({allocations.length})</div>
          <table className="mt-2 w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500">
                <th>Sub-account</th>
                <th className="text-right">Quantity</th>
                <th className="text-right">Avg price</th>
                <th>Method</th>
              </tr>
            </thead>
            <tbody>
              {allocations.map((a) => (
                <tr key={a.allocationId} className="border-t border-slate-200">
                  <td className="py-1 font-medium">{a.subAccount}</td>
                  <td className="py-1 text-right font-mono">{a.allocatedQuantity}</td>
                  <td className="py-1 text-right font-mono">{a.allocatedAvgPrice}</td>
                  <td className="py-1 text-xs text-slate-500">{a.allocationMethod}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
