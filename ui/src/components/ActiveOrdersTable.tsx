import { useShallow } from "zustand/react/shallow";
import { useOrderStore } from "@/stores/orderStore";
import { api } from "@/lib/api";
import { fmtMoney, fmtQty } from "@/lib/format";

const ACTIVE_STATUSES = new Set(["NEW", "SUBMITTED", "PARTIALLY_FILLED"]);

export default function ActiveOrdersTable() {
  const orders = useOrderStore(
    useShallow((s) =>
      Array.from(s.orders.values())
        .filter((o) => ACTIVE_STATUSES.has(o.status))
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
    ),
  );

  const cancel = async (orderId: string): Promise<void> => {
    try {
      await api(`/api/execution/orders/${orderId}`, { method: "DELETE" });
    } catch (err) {
      console.warn("cancel failed", err);
    }
  };

  if (orders.length === 0) {
    return (
      <div className="bg-white rounded shadow-sm p-6 text-center text-slate-500">
        No active orders.
      </div>
    );
  }

  return (
    <div className="bg-white rounded shadow-sm overflow-hidden">
      <h2 className="text-lg font-semibold px-6 py-3 border-b">Active Orders</h2>
      <table className="min-w-full text-sm divide-y divide-slate-100">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <Th>Created</Th>
            <Th>Symbol</Th>
            <Th>Side</Th>
            <Th right>Qty</Th>
            <Th>Type</Th>
            <Th right>Limit</Th>
            <Th>Status</Th>
            <Th right>Filled</Th>
            <Th right>Avg Fill</Th>
            <Th />
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-50">
          {orders.map((o) => (
            <tr key={o.orderId}>
              <td className="px-4 py-2 text-xs text-slate-500">
                {new Date(o.createdAt).toLocaleTimeString()}
              </td>
              <td className="px-4 py-2 font-medium">{o.symbol}</td>
              <td className={`px-4 py-2 ${o.side === "BUY" ? "text-green-700" : "text-red-700"}`}>
                {o.side}
              </td>
              <td className="px-4 py-2 text-right num">{fmtQty(o.quantity)}</td>
              <td className="px-4 py-2">{o.orderType}</td>
              <td className="px-4 py-2 text-right num">{fmtMoney(o.limitPrice)}</td>
              <td className="px-4 py-2">
                <StatusPill status={o.status} />
              </td>
              <td className="px-4 py-2 text-right num">{fmtQty(o.filledQuantity ?? 0)}</td>
              <td className="px-4 py-2 text-right num">{fmtMoney(o.avgFillPrice)}</td>
              <td className="px-4 py-2 text-right">
                <button
                  onClick={() => void cancel(o.orderId)}
                  className="text-xs text-slate-600 hover:text-red-700 hover:underline"
                >
                  Cancel
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StatusPill({ status }: { status: string }) {
  const cls =
    status === "FILLED"
      ? "bg-green-100 text-green-800"
      : status === "CANCELLED" || status === "REJECTED"
        ? "bg-slate-100 text-slate-700"
        : "bg-blue-100 text-blue-800";
  return <span className={`px-2 py-0.5 rounded text-xs ${cls}`}>{status}</span>;
}

function Th({ children, right = false }: { children?: React.ReactNode; right?: boolean }) {
  return <th className={`px-4 py-2 ${right ? "text-right" : "text-left"}`}>{children}</th>;
}
