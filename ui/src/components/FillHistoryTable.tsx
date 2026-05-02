import { useEffect, useState } from "react";
import { useOrderStore } from "@/stores/orderStore";
import { api } from "@/lib/api";
import type { Fill, Order } from "@/types/api";
import { fmtMoney, fmtQty } from "@/lib/format";

export default function FillHistoryTable() {
  // Return a stable primitive key so useSyncExternalStore doesn't trigger an infinite re-render
  // loop when Array.from always produces a new reference.
  const orderIdsKey = useOrderStore((s) => Array.from(s.orders.keys()).sort().join(","));
  const orderIds = orderIdsKey.length > 0 ? orderIdsKey.split(",") : [];
  const [fills, setFills] = useState<Fill[]>([]);

  useEffect(() => {
    let cancelled: boolean = false;
    void (async () => {
      const settled = await Promise.allSettled(
        orderIds.map((id) => api<Order>(`/api/orders/${id}`)),
      );
      // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition
      if (cancelled) return;
      const collected = settled
        .flatMap((r) => (r.status === "fulfilled" ? (r.value.fills ?? []) : []))
        .sort((a, b) => b.filledAt.localeCompare(a.filledAt))
        .slice(0, 50);
      setFills(collected);
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderIdsKey]);

  if (fills.length === 0) {
    return (
      <div className="bg-white rounded shadow-sm p-6 text-center text-slate-500">
        No fills yet.
      </div>
    );
  }

  return (
    <div className="bg-white rounded shadow-sm overflow-hidden">
      <h2 className="text-lg font-semibold px-6 py-3 border-b">Recent Fills</h2>
      <table className="min-w-full text-sm divide-y divide-slate-100">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-4 py-2 text-left">Time</th>
            <th className="px-4 py-2 text-left">Symbol</th>
            <th className="px-4 py-2 text-left">Side</th>
            <th className="px-4 py-2 text-right">Qty</th>
            <th className="px-4 py-2 text-right">Price</th>
            <th className="px-4 py-2 text-left">Venue</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-50">
          {fills.map((f) => (
            <tr key={f.fillId}>
              <td className="px-4 py-2 text-xs text-slate-500">
                {new Date(f.filledAt).toLocaleTimeString()}
              </td>
              <td className="px-4 py-2 font-medium">{f.symbol}</td>
              <td
                className={`px-4 py-2 ${f.side === "BUY" ? "text-green-700" : "text-red-700"}`}
              >
                {f.side}
              </td>
              <td className="px-4 py-2 text-right num">{fmtQty(f.fillQuantity)}</td>
              <td className="px-4 py-2 text-right num">{fmtMoney(f.fillPrice)}</td>
              <td className="px-4 py-2 text-slate-500">{f.venue ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
