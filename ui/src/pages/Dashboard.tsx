import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useWebSocket } from "@/hooks/useWebSocket";
import { usePositionStore } from "@/stores/positionStore";
import type { PortfolioSummary, Position, PositionUpdate } from "@/types/api";
import SummaryCards from "@/components/SummaryCards";
import PositionsTable from "@/components/PositionsTable";
import DailyPnlChart from "@/components/DailyPnlChart";

export default function Dashboard() {
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const replaceAll = usePositionStore((s) => s.replaceAll);
  const applyUpdate = usePositionStore((s) => s.applyUpdate);

  const loadSnapshot = async (): Promise<void> => {
    try {
      const [s, p] = await Promise.all([
        api<PortfolioSummary>("/api/portfolio/summary"),
        api<Position[]>("/api/positions"),
      ]);
      setSummary(s);
      replaceAll(p);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  };

  useEffect(() => {
    void loadSnapshot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { state } = useWebSocket<PositionUpdate>({
    endpoint: "/ws/positions",
    onMessage: applyUpdate,
  });

  useEffect(() => {
    if (state === "open") void loadSnapshot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      {error && <div className="p-3 bg-red-50 text-red-700 rounded">{error}</div>}
      <SummaryCards summary={summary} />
      <DailyPnlChart />
      <PositionsTable />
    </div>
  );
}
