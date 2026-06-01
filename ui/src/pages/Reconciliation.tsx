import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";

interface ReconBreak {
  breakId: string;
  reconDate: string;
  orderId: string;
  breakType: string;
  severity: string;
  resolution?: string;
}

interface ReconSummary {
  reconDate: string;
  totalBreaks: number;
  bySeverity: Record<string, number>;
  byBreakType: Record<string, number>;
}

const todayIso = (): string => new Date().toISOString().slice(0, 10);

const severityClass = (s: string): string => {
  switch (s.toUpperCase()) {
    case "CRITICAL":
      return "bg-red-100 text-red-800";
    case "HIGH":
      return "bg-orange-100 text-orange-800";
    case "MEDIUM":
      return "bg-amber-100 text-amber-800";
    case "LOW":
      return "bg-slate-100 text-slate-700";
    default:
      return "bg-slate-100 text-slate-700";
  }
};

export default function Reconciliation() {
  const [reconDate, setReconDate] = useState<string>(todayIso());
  const [runs, setRuns] = useState<string[]>([]);
  const [breaks, setBreaks] = useState<ReconBreak[]>([]);
  const [summary, setSummary] = useState<ReconSummary | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (): Promise<void> => {
    try {
      const [b, s] = await Promise.all([
        api<ReconBreak[]>(`/api/recon/breaks?date=${reconDate}`),
        api<ReconSummary>(`/api/recon/summary?date=${reconDate}`),
      ]);
      setBreaks(b);
      setSummary(s);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [reconDate]);

  const loadRuns = useCallback(async (): Promise<void> => {
    try {
      const r = await api<string[]>("/api/recon/runs");
      setRuns(r);
    } catch (e) {
      // non-fatal — leave runs empty
      console.warn("recon runs failed", e);
    }
  }, []);

  useEffect(() => {
    void loadRuns();
  }, [loadRuns]);

  useEffect(() => {
    void load();
  }, [load]);

  const matched = useMemo(() => (summary ? Math.max(0, 100 - summary.totalBreaks) : 0), [summary]);

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Reconciliation</h1>
      <p className="text-sm text-slate-600">
        End-of-day reconciliation breaks between internal fills and Alpaca account activities (FR-29
        / FR-30). The engine itself ships in 2.6.1 — these views light up the moment a run lands.
      </p>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label className="block text-sm text-slate-700">Recon date</label>
          <input
            data-testid="recon-date"
            type="date"
            className="rounded border border-slate-300 px-2 py-1"
            value={reconDate}
            onChange={(e) => {
              setReconDate(e.target.value);
            }}
          />
        </div>
        {runs.length > 0 && (
          <div>
            <label className="block text-sm text-slate-700">Recent runs</label>
            <select
              data-testid="recon-runs"
              className="rounded border border-slate-300 px-2 py-1"
              value={reconDate}
              onChange={(e) => {
                setReconDate(e.target.value);
              }}
            >
              {runs.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          </div>
        )}
        <button
          data-testid="recon-refresh"
          className="rounded bg-blue-600 px-3 py-1 text-white text-sm"
          onClick={() => {
            void load();
            void loadRuns();
          }}
        >
          Refresh
        </button>
      </div>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="recon-error">
          {error}
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3" data-testid="recon-summary">
        <Card
          title="Total breaks"
          value={summary?.totalBreaks ?? 0}
          accent={summary && summary.totalBreaks > 0 ? "bad" : "good"}
        />
        <Card title="Implied matched (vs 100)" value={matched} accent="good" />
        <Card
          title="Critical"
          value={summary?.bySeverity.CRITICAL ?? 0}
          accent={summary?.bySeverity.CRITICAL ? "bad" : "neutral"}
        />
        <Card
          title="High"
          value={summary?.bySeverity.HIGH ?? 0}
          accent={summary?.bySeverity.HIGH ? "warn" : "neutral"}
        />
      </div>

      <div className="bg-white rounded shadow-sm overflow-hidden">
        <table className="min-w-full divide-y divide-slate-200" data-testid="recon-breaks-table">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">Break ID</th>
              <th className="px-4 py-2 text-left">Order</th>
              <th className="px-4 py-2 text-left">Type</th>
              <th className="px-4 py-2 text-left">Severity</th>
              <th className="px-4 py-2 text-left">Resolution</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-sm">
            {breaks.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-12 text-center text-slate-500">
                  No reconciliation breaks recorded for {reconDate}.
                </td>
              </tr>
            )}
            {breaks.map((b) => (
              <tr key={b.breakId}>
                <td className="px-4 py-2 font-mono text-xs">{b.breakId.slice(0, 8)}</td>
                <td className="px-4 py-2 font-mono text-xs">{b.orderId.slice(0, 8)}</td>
                <td className="px-4 py-2">{b.breakType}</td>
                <td className="px-4 py-2">
                  <span className={`rounded px-2 py-0.5 text-xs ${severityClass(b.severity)}`}>
                    {b.severity}
                  </span>
                </td>
                <td className="px-4 py-2 text-slate-600">{b.resolution ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {summary && Object.keys(summary.byBreakType).length > 0 && (
        <div className="bg-white rounded shadow-sm p-4">
          <div className="text-sm font-medium text-slate-700">Breaks by type</div>
          <table className="mt-2 text-sm w-full max-w-md" data-testid="recon-bytype-table">
            <tbody>
              {Object.entries(summary.byBreakType).map(([k, v]) => (
                <tr key={k}>
                  <td className="py-1 text-slate-600">{k}</td>
                  <td className="py-1 text-right num">{v}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Card({
  title,
  value,
  accent,
}: {
  title: string;
  value: number;
  accent: "good" | "bad" | "warn" | "neutral";
}) {
  const cls =
    accent === "good"
      ? "text-green-700"
      : accent === "bad"
        ? "text-red-700"
        : accent === "warn"
          ? "text-amber-700"
          : "text-slate-700";
  return (
    <div className="bg-white rounded shadow-sm p-4" data-testid={`card-${title}`}>
      <div className="text-xs uppercase text-slate-500">{title}</div>
      <div className={`text-2xl font-semibold ${cls}`}>{value}</div>
    </div>
  );
}
