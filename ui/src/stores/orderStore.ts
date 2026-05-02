import { create } from "zustand";
import type { Order, OrderEvent } from "@/types/api";

interface OrderStore {
  orders: Map<string, Order>;
  replaceAll: (rows: Order[]) => void;
  applyEvent: (e: OrderEvent) => void;
  remove: (id: string) => void;
}

export const useOrderStore = create<OrderStore>((set) => ({
  orders: new Map(),
  replaceAll: (rows) => set(() => ({ orders: new Map(rows.map((r) => [r.orderId, r])) })),
  applyEvent: (e) =>
    set((s) => {
      const next = new Map(s.orders);
      const existing = next.get(e.orderId);
      const merged: Order = existing
        ? { ...existing, status: e.status }
        : e.order
          ? snapshotToOrder(e.order)
          : minimalShell(e.orderId, e.status, e.timestamp);
      if (e.order) Object.assign(merged, snapshotToOrder(e.order));
      if (e.fill) {
        merged.filledQuantity = (merged.filledQuantity ?? 0) + e.fill.fillQuantity;
        merged.avgFillPrice = e.fill.fillPrice;
      }
      merged.updatedAt = e.timestamp;
      next.set(e.orderId, merged);
      return { orders: next };
    }),
  remove: (id) =>
    set((s) => {
      const next = new Map(s.orders);
      next.delete(id);
      return { orders: next };
    }),
}));

function snapshotToOrder(snap: NonNullable<OrderEvent["order"]>): Order {
  return {
    orderId: snap.orderId,
    symbol: snap.symbol,
    side: snap.side,
    orderType: snap.orderType,
    quantity: snap.quantity,
    ...(snap.limitPrice !== undefined ? { limitPrice: snap.limitPrice } : {}),
    ...(snap.stopPrice !== undefined ? { stopPrice: snap.stopPrice } : {}),
    status: "NEW",
    strategy: snap.strategyName,
    filledQuantity: snap.filledQuantity,
    avgFillPrice: snap.avgFillPrice,
    ...(snap.exchangeOrderId !== undefined ? { exchangeOrderId: snap.exchangeOrderId } : {}),
    createdAt: "",
    updatedAt: "",
  };
}

function minimalShell(orderId: string, status: Order["status"], ts: string): Order {
  return {
    orderId,
    status,
    symbol: "?",
    side: "BUY",
    orderType: "MARKET",
    quantity: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}
