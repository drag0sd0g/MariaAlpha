import { useShallow } from "zustand/react/shallow";
import { useAlertStore } from "@/stores/alertStore";

const severityClass = (s: string): string => {
  switch (s.toUpperCase()) {
    case "CRITICAL":
      return "bg-red-100 border-red-300 text-red-900";
    case "HIGH":
      return "bg-orange-100 border-orange-300 text-orange-900";
    case "MEDIUM":
    case "WARN":
    case "WARNING":
      return "bg-amber-100 border-amber-300 text-amber-900";
    default:
      return "bg-slate-100 border-slate-300 text-slate-900";
  }
};

/**
 * Floating alert stack rendered above all pages. Subscribed to the app-wide alert store
 * populated by the `/ws/alerts` WebSocket consumer in `App.tsx`.
 */
export default function AlertsBanner() {
  const activeAlerts = useAlertStore(
    useShallow((s) => s.alerts.filter((a) => !a.dismissed).slice(0, 5)),
  );
  const dismiss = useAlertStore((s) => s.dismiss);

  if (activeAlerts.length === 0) return null;

  return (
    <div
      data-testid="alerts-banner"
      className="fixed top-2 right-2 z-50 flex flex-col gap-2 max-w-md"
    >
      {activeAlerts.map((a) => (
        <div
          key={a.receivedAt}
          data-testid="alert-card"
          className={`rounded border p-3 shadow-sm ${severityClass(a.severity)}`}
        >
          <div className="flex items-start justify-between gap-2">
            <div>
              <div className="text-xs uppercase tracking-wide opacity-70">
                {a.severity} · {a.alertType}
                {a.symbol ? ` · ${a.symbol}` : ""}
              </div>
              <div className="text-sm mt-1">{a.message}</div>
              <div className="text-[10px] opacity-60 mt-1">
                {new Date(a.timestamp).toLocaleTimeString()}
              </div>
            </div>
            <button
              type="button"
              data-testid="alert-dismiss"
              className="text-xs opacity-60 hover:opacity-100"
              onClick={() => {
                dismiss(a.receivedAt);
              }}
              aria-label="Dismiss"
            >
              ✕
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}
