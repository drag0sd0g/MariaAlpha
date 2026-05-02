import { useState } from "react";
import type { OrderType, Side, SubmitOrderRequest } from "@/types/api";
import { api } from "@/lib/api";

interface Props {
  onSubmitted: () => void;
}

export default function OrderForm({ onSubmitted }: Props) {
  const [symbol, setSymbol] = useState("");
  const [side, setSide] = useState<Side>("BUY");
  const [orderType, setOrderType] = useState<OrderType>("LIMIT");
  const [quantity, setQuantity] = useState("100");
  const [limitPrice, setLimitPrice] = useState("");
  const [stopPrice, setStopPrice] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const needsLimit = orderType === "LIMIT";
  const needsStop = orderType === "STOP";

  const validate = (): SubmitOrderRequest | string => {
    if (!symbol.trim()) return "Symbol is required";
    const q = Number(quantity);
    if (!Number.isFinite(q) || q < 1) return "Quantity must be a positive integer";
    const lp = limitPrice ? Number(limitPrice) : undefined;
    if (needsLimit && (!lp || lp <= 0)) return "LIMIT orders need a price > 0";
    const sp = stopPrice ? Number(stopPrice) : undefined;
    if (needsStop && (!sp || sp <= 0)) return "STOP orders need a stop price > 0";
    return {
      symbol: symbol.toUpperCase().trim(),
      side,
      orderType,
      quantity: q,
      ...(lp !== undefined ? { limitPrice: lp } : {}),
      ...(sp !== undefined ? { stopPrice: sp } : {}),
    };
  };

  const submit = async (e: React.FormEvent): Promise<void> => {
    e.preventDefault();
    setError(null);
    const r = validate();
    if (typeof r === "string") {
      setError(r);
      return;
    }
    setSubmitting(true);
    try {
      await api("/api/execution/orders", { method: "POST", body: JSON.stringify(r) });
      setQuantity("100");
      setLimitPrice("");
      setStopPrice("");
      onSubmitted();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Order submission failed");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={(e) => void submit(e)} className="bg-white rounded shadow-sm p-6 space-y-4">
      <h2 className="text-lg font-semibold">Submit Order</h2>
      {error && <div className="p-2 bg-red-50 text-red-700 text-sm rounded">{error}</div>}
      <div className="grid grid-cols-2 gap-4">
        <Field label="Symbol">
          <input
            aria-label="Symbol"
            value={symbol}
            onChange={(e) => setSymbol(e.target.value)}
            placeholder="AAPL"
            className="w-full border rounded px-2 py-1 uppercase"
          />
        </Field>
        <Field label="Side">
          <select
            aria-label="Side"
            value={side}
            onChange={(e) => setSide(e.target.value as Side)}
            className="w-full border rounded px-2 py-1"
          >
            <option>BUY</option>
            <option>SELL</option>
          </select>
        </Field>
        <Field label="Order Type">
          <select
            aria-label="Order Type"
            value={orderType}
            onChange={(e) => setOrderType(e.target.value as OrderType)}
            className="w-full border rounded px-2 py-1"
          >
            <option>MARKET</option>
            <option>LIMIT</option>
            <option>STOP</option>
          </select>
        </Field>
        <Field label="Quantity">
          <input
            aria-label="Quantity"
            type="number"
            min={1}
            value={quantity}
            onChange={(e) => setQuantity(e.target.value)}
            className="w-full border rounded px-2 py-1 num"
          />
        </Field>
        {needsLimit && (
          <Field label="Limit Price">
            <input
              aria-label="Limit Price"
              type="number"
              step="0.01"
              value={limitPrice}
              onChange={(e) => setLimitPrice(e.target.value)}
              className="w-full border rounded px-2 py-1 num"
            />
          </Field>
        )}
        {needsStop && (
          <Field label="Stop Price">
            <input
              aria-label="Stop Price"
              type="number"
              step="0.01"
              value={stopPrice}
              onChange={(e) => setStopPrice(e.target.value)}
              className="w-full border rounded px-2 py-1 num"
            />
          </Field>
        )}
      </div>
      <button
        type="submit"
        disabled={submitting}
        className="bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white px-4 py-2 rounded"
      >
        {submitting ? "Submitting…" : "Submit"}
      </button>
    </form>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="text-slate-600">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
