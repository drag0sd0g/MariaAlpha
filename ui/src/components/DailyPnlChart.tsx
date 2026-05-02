import { useEffect, useRef, useState } from "react";
import {
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { usePositionStore } from "@/stores/positionStore";

interface Sample {
  t: number;
  pnl: number;
}
const SAMPLE_INTERVAL_MS = 1_000;
const MAX_SAMPLES = 600; // 10 minutes at 1Hz

export default function DailyPnlChart() {
  const samplesRef = useRef<Sample[]>([]);
  const [, setTick] = useState(0);

  useEffect(() => {
    const id = setInterval(() => {
      const positions = Array.from(usePositionStore.getState().positions.values());
      const totalPnl = positions.reduce((s, p) => s + p.totalPnl, 0);
      samplesRef.current.push({ t: Date.now(), pnl: totalPnl });
      if (samplesRef.current.length > MAX_SAMPLES) samplesRef.current.shift();
      setTick((n) => n + 1);
    }, SAMPLE_INTERVAL_MS);
    return () => clearInterval(id);
  }, []);

  return (
    <div className="bg-white rounded shadow-sm p-4">
      <div className="text-xs text-slate-500 uppercase tracking-wide mb-2">Daily P&L</div>
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={samplesRef.current}>
            <XAxis
              dataKey="t"
              tickFormatter={(t: number) => new Date(t).toLocaleTimeString()}
            />
            <YAxis tickFormatter={(v: number) => `$${v.toFixed(0)}`} />
            <Tooltip
              labelFormatter={(t: number) => new Date(t).toLocaleTimeString()}
              formatter={(v: number) => [`$${v.toFixed(2)}`, "P&L"]}
            />
            <ReferenceLine y={0} stroke="#94a3b8" strokeDasharray="3 3" />
            <Line
              type="monotone"
              dataKey="pnl"
              stroke="#2563eb"
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
