import { useEffect, useState } from "react";
import { useConnectionStore } from "@/stores/connectionStore";

const DEBOUNCE_MS = 5_000;

export default function ConnectionStatus() {
  const states = useConnectionStore((s) => s.states);
  const entries = Object.entries(states) as Array<
    [string, "connecting" | "open" | "closed" | "error"]
  >;
  const reconnecting = entries.filter(([, st]) => st === "connecting").map(([e]) => e);
  const failingNow = entries.filter(([, st]) => st === "error" || st === "closed").map(([e]) => e);

  const [showFail, setShowFail] = useState(false);
  useEffect(() => {
    if (failingNow.length === 0) {
      setShowFail(false);
      return;
    }
    const t = setTimeout(() => setShowFail(true), DEBOUNCE_MS);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(failingNow)]);

  if (failingNow.length > 0 && showFail) {
    return (
      <div className="fixed top-0 inset-x-0 bg-red-600 text-white text-sm py-2 px-4 text-center z-50">
        Disconnected — live data is paused. Attempting to reconnect…
      </div>
    );
  }
  if (reconnecting.length > 0) {
    return (
      <div className="fixed top-0 inset-x-0 bg-amber-500 text-white text-sm py-2 px-4 text-center z-50">
        Reconnecting…
      </div>
    );
  }
  return null;
}
