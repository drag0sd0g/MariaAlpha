import { fmtMoney, fmtPnl } from "@/lib/format";
import type { PortfolioSummary } from "@/types/api";

export default function SummaryCards({ summary }: { summary: PortfolioSummary | null }) {
  const pnl = fmtPnl(summary?.totalPnl);
  return (
    <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
      <Card label="Total Value" value={fmtMoney(summary?.totalValue)} />
      <Card label="Cash" value={fmtMoney(summary?.cashBalance)} />
      <Card label="Net Exposure" value={fmtMoney(summary?.netExposure)} />
      <Card label="Total P&L" value={pnl.text} valueCls={pnl.cls} />
    </div>
  );
}

function Card({
  label,
  value,
  valueCls = "",
}: {
  label: string;
  value: string;
  valueCls?: string;
}) {
  return (
    <div className="bg-white rounded shadow-sm p-4">
      <div className="text-xs text-slate-500 uppercase tracking-wide">{label}</div>
      <div className={`text-2xl font-semibold mt-1 num ${valueCls}`}>{value}</div>
    </div>
  );
}
