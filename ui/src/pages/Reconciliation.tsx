import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";

interface ReconBreak {
  breakId: string;
  reconDate: string;
  orderId?: string;
  breakType: string;
  severity: string;
  resolution?: string;
  symbol?: string;
  description?: string;
  internalQty?: string;
  externalQty?: string;
  internalPrice?: string;
  externalPrice?: string;
  notional?: string;
  createdAt?: string;
}

interface ReconRun {
  runId: string;
  reconDate: string;
  status: string;
  source: string;
  startedAt: string;
  finishedAt?: string;
  internalFillsCount?: number;
  externalFillsCount?: number;
  breaksCount?: number;
  errorMessage?: string;
}

interface ReconSummary {
  reconDate: string;
  totalBreaks: number;
  bySeverity: Record<string, number>;
  byBreakType: Record<string, number>;
  run?: ReconRun;
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

const runStatusClass = (s?: string): string => {
  switch ((s ?? "").toUpperCase()) {
    case "SUCCESS":
      return "bg-green-100 text-green-800";
    case "FAILED":
      return "bg-red-100 text-red-800";
    case "IN_PROGRESS":
      return "bg-blue-100 text-blue-800";
    default:
      return "bg-slate-100 text-slate-700";
  }
};

export default function Reconciliation() {
  const [reconDate, setReconDate] = useState<string>(todayIso());
  const [runs, setRuns] = useState<ReconRun[]>([]);
  const [breaks, setBreaks] = useState<ReconBreak[]>([]);
  const [summary, setSummary] = useState<ReconSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [triggering, setTriggering] = useState(false);

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
      const r = await api<ReconRun[]>("/api/recon/runs");
      setRuns(r);
    } catch (e) {
      console.warn("recon runs failed", e);
    }
  }, []);

  const triggerRun = useCallback(async (): Promise<void> => {
    setTriggering(true);
    try {
      await api<ReconRun>(`/api/recon/run?date=${reconDate}`, { method: "POST" });
      await Promise.all([load(), loadRuns()]);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setTriggering(false);
    }
  }, [reconDate, load, loadRuns]);

  useEffect(() => {
    void loadRuns();
  }, [loadRuns]);

  useEffect(() => {
    void load();
  }, [load]);

  const matched = useMemo<number | "—">(() => {
    if (summary?.run?.externalFillsCount == null) return "—";
    return Math.max(0, summary.run.externalFillsCount - summary.totalBreaks);
  }, [summary]);

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Reconciliation</h1>
      <p className="text-sm text-slate-600">
        End-of-day reconciliation between internal fills and the venue's activity report
        (FR-29/FR-30). Trigger an ad-hoc run for any date, or wait for the nightly scheduler.
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
                <option key={r.runId} value={r.reconDate}>
                  {r.reconDate} ({r.status})
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
        <button
          data-testid="recon-trigger"
          className="rounded bg-emerald-600 px-3 py-1 text-white text-sm disabled:opacity-50"
          disabled={triggering}
          onClick={() => {
            void triggerRun();
          }}
        >
          {triggering ? "Running…" : "Run reconciliation"}
        </button>
      </div>

      {error && (
        <div className="rounded bg-red-50 p-3 text-red-700" data-testid="recon-error">
          {error}
        </div>
      )}

      {summary?.run && (
        <div className="rounded bg-white p-4 shadow-sm" data-testid="recon-run">
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span
              className={`rounded px-2 py-0.5 text-xs ${runStatusClass(summary.run.status)}`}
              data-testid="recon-run-status"
            >
              {summary.run.status}
            </span>
            <span className="text-slate-600">source: {summary.run.source}</span>
            {summary.run.startedAt && (
              <span className="text-slate-600">started: {summary.run.startedAt}</span>
            )}
            {summary.run.finishedAt && (
              <span className="text-slate-600">finished: {summary.run.finishedAt}</span>
            )}
            {summary.run.internalFillsCount != null && (
              <span className="text-slate-600">
                internal fills: {summary.run.internalFillsCount}
              </span>
            )}
            {summary.run.externalFillsCount != null && (
              <span className="text-slate-600">
                external fills: {summary.run.externalFillsCount}
              </span>
            )}
          </div>
          {summary.run.errorMessage && (
            <div className="mt-2 text-sm text-red-700" data-testid="recon-run-error">
              {summary.run.errorMessage}
            </div>
          )}
        </div>
      )}

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3" data-testid="recon-summary">
        <Card
          title="Total breaks"
          value={summary?.totalBreaks ?? 0}
          accent={summary && summary.totalBreaks > 0 ? "bad" : "good"}
        />
        <Card title="Matched" value={matched} accent="good" />
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
              <th className="px-4 py-2 text-left">Symbol</th>
              <th className="px-4 py-2 text-left">Type</th>
              <th className="px-4 py-2 text-left">Severity</th>
              <th className="px-4 py-2 text-left">Description</th>
              <th className="px-4 py-2 text-left">Resolution</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 text-sm">
            {breaks.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-slate-500">
                  No reconciliation breaks recorded for {reconDate}.
                </td>
              </tr>
            )}
            {breaks.map((b) => (
              <tr key={b.breakId}>
                <td className="px-4 py-2 font-mono text-xs">{b.breakId.slice(0, 8)}</td>
                <td className="px-4 py-2 font-mono text-xs">
                  {b.orderId ? b.orderId.slice(0, 8) : "—"}
                </td>
                <td className="px-4 py-2">{b.symbol ?? "—"}</td>
                <td className="px-4 py-2">{b.breakType}</td>
                <td className="px-4 py-2">
                  <span className={`rounded px-2 py-0.5 text-xs ${severityClass(b.severity)}`}>
                    {b.severity}
                  </span>
                </td>
                <td className="px-4 py-2 text-slate-600">{b.description ?? "—"}</td>
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
  value: number | string;
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
