import { useEffect, useState } from "react";
import { fetchIcebergProgress } from "@/lib/api.icebergProgress";
import type { IcebergProgress } from "@/types/api";

interface Props {
  parentOrderId: string;
  pollMs?: number;
}

export default function IcebergProgressBadge({ parentOrderId, pollMs = 1500 }: Props) {
  const [progress, setProgress] = useState<IcebergProgress | null>(null);

  useEffect(() => {
    let active = true;
    const fetch = async (): Promise<void> => {
      try {
        const p = await fetchIcebergProgress(parentOrderId);
        if (active) setProgress(p);
      } catch {
        if (active) setProgress(null);
      }
    };
    void fetch();
    const id = window.setInterval(() => void fetch(), pollMs);
    return () => {
      active = false;
      window.clearInterval(id);
    };
  }, [parentOrderId, pollMs]);

  if (!progress) return null;
  const pct = Math.min(100, Math.round((progress.filledQuantity / progress.totalQuantity) * 100));
  return (
    <span
      title={`${String(progress.slicesSubmitted)} slices submitted; active child: ${progress.activeChildOrderId ?? "none"}`}
      className="inline-flex items-center gap-1 text-xs bg-indigo-50 text-indigo-700 rounded px-2 py-0.5"
    >
      <span>
        {progress.filledQuantity.toLocaleString()} / {progress.totalQuantity.toLocaleString()}
      </span>
      <span className="text-indigo-400">({pct}%)</span>
    </span>
  );
}
