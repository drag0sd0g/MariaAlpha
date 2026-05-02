import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useWebSocket } from "@/hooks/useWebSocket";
import { useOrderStore } from "@/stores/orderStore";
import type { Order, OrderEvent } from "@/types/api";
import OrderForm from "@/components/OrderForm";
import ActiveOrdersTable from "@/components/ActiveOrdersTable";
import FillHistoryTable from "@/components/FillHistoryTable";

export default function OrderEntry() {
  const replaceAll = useOrderStore((s) => s.replaceAll);
  const applyEvent = useOrderStore((s) => s.applyEvent);
  const [error, setError] = useState<string | null>(null);

  const loadSnapshot = async (): Promise<void> => {
    try {
      const orders = await api<Order[]>("/api/orders?limit=100");
      replaceAll(orders);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "load failed");
    }
  };

  useEffect(() => {
    void loadSnapshot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { state } = useWebSocket<OrderEvent>({
    endpoint: "/ws/orders",
    onMessage: applyEvent,
  });

  useEffect(() => {
    if (state === "open") void loadSnapshot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-semibold">Order Entry</h1>
      {error && <div className="p-3 bg-red-50 text-red-700 rounded">{error}</div>}
      <OrderForm onSubmitted={() => void loadSnapshot()} />
      <ActiveOrdersTable />
      <FillHistoryTable />
    </div>
  );
}
