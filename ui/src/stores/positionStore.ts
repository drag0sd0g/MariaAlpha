import { create } from "zustand";
import type { Position, PositionUpdate } from "@/types/api";

interface PositionStore {
  positions: Map<string, Position>;
  replaceAll: (rows: Position[]) => void;
  applyUpdate: (u: PositionUpdate) => void;
}

export const usePositionStore = create<PositionStore>((set) => ({
  positions: new Map(),
  replaceAll: (rows) => set(() => ({ positions: new Map(rows.map((r) => [r.symbol, r])) })),
  applyUpdate: (u) =>
    set((s) => {
      const next = new Map(s.positions);
      next.set(u.symbol, {
        symbol: u.symbol,
        netQuantity: u.netQuantity,
        avgEntryPrice: u.avgEntryPrice,
        realizedPnl: u.realizedPnl,
        unrealizedPnl: u.unrealizedPnl,
        // PositionUpdate has no totalPnl; compute it.
        totalPnl: u.realizedPnl + u.unrealizedPnl,
        lastMarkPrice: u.lastMarkPrice,
        updatedAt: u.timestamp,
      });
      return { positions: next };
    }),
}));
