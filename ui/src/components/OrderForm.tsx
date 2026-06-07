import { useState } from "react";
import type { OrderType, PegType, Side, SubmitOrderRequest, TimeInForce } from "@/types/api";
import { api } from "@/lib/api";

interface Props {
  onSubmitted: () => void;
}

const ORDER_TYPES: OrderType[] = [
  "MARKET",
  "LIMIT",
  "STOP",
  "IOC",
  "FOK",
  "GTC",
  "ICEBERG",
  "PEGGED",
];
const PEG_TYPES: PegType[] = ["MIDPOINT", "PRIMARY", "MARKET"];

export default function OrderForm({ onSubmitted }: Props) {
  const [symbol, setSymbol] = useState("");
  const [side, setSide] = useState<Side>("BUY");
  const [orderType, setOrderType] = useState<OrderType>("LIMIT");
  const [quantity, setQuantity] = useState("100");
  const [limitPrice, setLimitPrice] = useState("");
  const [stopPrice, setStopPrice] = useState("");
  const [displayQuantity, setDisplayQuantity] = useState("");
  const [pegType, setPegType] = useState<PegType>("MIDPOINT");
  const [pegOffsetBps, setPegOffsetBps] = useState("0");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const needsLimit =
    orderType === "LIMIT" ||
    orderType === "IOC" ||
    orderType === "FOK" ||
    orderType === "GTC" ||
    orderType === "ICEBERG";
  const needsStop = orderType === "STOP";
  const needsDisplay = orderType === "ICEBERG";
  const needsPeg = orderType === "PEGGED";
  const intrinsicTif: TimeInForce | undefined =
    orderType === "IOC"
      ? "IOC"
      : orderType === "FOK"
        ? "FOK"
        : orderType === "GTC"
          ? "GTC"
          : undefined;

  const validate = (): SubmitOrderRequest | string => {
    if (!symbol.trim()) return "Symbol is required";
    const q = Number(quantity);
    if (!Number.isFinite(q) || q < 1) return "Quantity must be a positive integer";
    const lp = limitPrice ? Number(limitPrice) : undefined;
    if (needsLimit && (!lp || lp <= 0)) return `${orderType} orders need a limit price > 0`;
    const sp = stopPrice ? Number(stopPrice) : undefined;
    if (needsStop && (!sp || sp <= 0)) return "STOP orders need a stop price > 0";
    const dq = displayQuantity ? Number(displayQuantity) : undefined;
    if (needsDisplay) {
      if (!dq || dq <= 0) return "ICEBERG orders need a display quantity > 0";
      if (dq >= q) return "displayQuantity must be strictly less than quantity";
    }
    const offsetBps = needsPeg && pegOffsetBps !== "" ? Number(pegOffsetBps) : undefined;
    if (needsPeg && offsetBps !== undefined && !Number.isFinite(offsetBps)) {
      return "pegOffsetBps must be a number";
    }
    return {
      symbol: symbol.toUpperCase().trim(),
      side,
      orderType,
      quantity: q,
      ...(lp !== undefined ? { limitPrice: lp } : {}),
      ...(sp !== undefined ? { stopPrice: sp } : {}),
      ...(dq !== undefined ? { displayQuantity: dq } : {}),
      ...(intrinsicTif !== undefined ? { tif: intrinsicTif } : {}),
      ...(needsPeg ? { pegType, pegOffsetBps: offsetBps ?? 0 } : {}),
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
      setDisplayQuantity("");
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
            onChange={(e) => {
              setSymbol(e.target.value);
            }}
            placeholder="AAPL"
            className="w-full border rounded px-2 py-1 uppercase"
          />
        </Field>
        <Field label="Side">
          <select
            aria-label="Side"
            value={side}
            onChange={(e) => {
              setSide(e.target.value as Side);
            }}
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
            onChange={(e) => {
              setOrderType(e.target.value as OrderType);
            }}
            className="w-full border rounded px-2 py-1"
          >
            {ORDER_TYPES.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
        </Field>
        <Field label="Quantity">
          <input
            aria-label="Quantity"
            type="number"
            min={1}
            value={quantity}
            onChange={(e) => {
              setQuantity(e.target.value);
            }}
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
              onChange={(e) => {
                setLimitPrice(e.target.value);
              }}
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
              onChange={(e) => {
                setStopPrice(e.target.value);
              }}
              className="w-full border rounded px-2 py-1 num"
            />
          </Field>
        )}
        {needsDisplay && (
          <Field label="Display Quantity">
            <input
              aria-label="Display Quantity"
              type="number"
              min={1}
              value={displayQuantity}
              onChange={(e) => {
                setDisplayQuantity(e.target.value);
              }}
              className="w-full border rounded px-2 py-1 num"
            />
          </Field>
        )}
        {needsPeg && (
          <>
            <Field label="Peg Type">
              <select
                aria-label="Peg Type"
                value={pegType}
                onChange={(e) => {
                  setPegType(e.target.value as PegType);
                }}
                className="w-full border rounded px-2 py-1"
              >
                {PEG_TYPES.map((t) => (
                  <option key={t}>{t}</option>
                ))}
              </select>
            </Field>
            <Field label="Peg Offset (bps)">
              <input
                aria-label="Peg Offset (bps)"
                type="number"
                value={pegOffsetBps}
                onChange={(e) => {
                  setPegOffsetBps(e.target.value);
                }}
                className="w-full border rounded px-2 py-1 num"
              />
            </Field>
            <Field label="Price Cap (optional)">
              <input
                aria-label="Price Cap (optional)"
                type="number"
                step="0.01"
                value={limitPrice}
                onChange={(e) => {
                  setLimitPrice(e.target.value);
                }}
                placeholder="Max for BUY / Min for SELL"
                className="w-full border rounded px-2 py-1 num"
              />
            </Field>
          </>
        )}
      </div>
      {intrinsicTif && (
        <div className="text-xs text-slate-500">
          Time-in-force: {intrinsicTif} (intrinsic to {orderType})
        </div>
      )}
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
