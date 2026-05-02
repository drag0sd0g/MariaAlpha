import { useShallow } from "zustand/react/shallow";
import { usePositionStore } from "@/stores/positionStore";
import { fmtMoney, fmtPnl, fmtQty } from "@/lib/format";

export default function PositionsTable() {
  const positions = usePositionStore(
    useShallow((s) =>
      Array.from(s.positions.values()).sort(
        (a, b) => Math.abs(b.netQuantity) - Math.abs(a.netQuantity),
      ),
    ),
  );

  if (positions.length === 0) {
    return (
      <div className="bg-white rounded shadow-sm p-8 text-center text-slate-500">
        No open positions.
      </div>
    );
  }

  return (
    <div className="bg-white rounded shadow-sm overflow-hidden">
      <table className="min-w-full divide-y divide-slate-200">
        <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <Th>Symbol</Th>
            <Th right>Qty</Th>
            <Th right>Avg Entry</Th>
            <Th right>Mark</Th>
            <Th right>Unrealized</Th>
            <Th right>Realized</Th>
            <Th right>Total P&L</Th>
            <Th>Updated</Th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 text-sm">
          {positions.map((p) => {
            const tot = fmtPnl(p.totalPnl);
            const unr = fmtPnl(p.unrealizedPnl);
            const rea = fmtPnl(p.realizedPnl);
            return (
              <tr key={p.symbol}>
                <td className="px-4 py-2 font-medium">{p.symbol}</td>
                <td className="px-4 py-2 text-right num">{fmtQty(p.netQuantity)}</td>
                <td className="px-4 py-2 text-right num">{fmtMoney(p.avgEntryPrice)}</td>
                <td className="px-4 py-2 text-right num">{fmtMoney(p.lastMarkPrice)}</td>
                <td className={`px-4 py-2 text-right num ${unr.cls}`}>{unr.text}</td>
                <td className={`px-4 py-2 text-right num ${rea.cls}`}>{rea.text}</td>
                <td className={`px-4 py-2 text-right num ${tot.cls}`}>{tot.text}</td>
                <td className="px-4 py-2 text-slate-500 text-xs">
                  {new Date(p.updatedAt).toLocaleTimeString()}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function Th({ children, right = false }: { children: React.ReactNode; right?: boolean }) {
  return <th className={`px-4 py-2 ${right ? "text-right" : "text-left"}`}>{children}</th>;
}
