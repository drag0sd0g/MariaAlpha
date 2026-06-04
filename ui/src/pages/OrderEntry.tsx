import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { useConnectionStore } from "@/stores/connectionStore";
import { useOrderStore } from "@/stores/orderStore";
import type { Order } from "@/types/api";
import OrderForm from "@/components/OrderForm";
import ActiveOrdersTable from "@/components/ActiveOrdersTable";
import FillHistoryTable from "@/components/FillHistoryTable";

export default function OrderEntry() {
  const replaceAll = useOrderStore((s) => s.replaceAll);
  const [error, setError] = useState<string | null>(null);
  // App-wide /ws/orders connection lives in AppWideStreams;
  // we re-fetch the REST snapshot on each reconnect.
  const ordersWsState = useConnectionStore((s) => s.states["/ws/orders"]);

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

  useEffect(() => {
    if (ordersWsState === "open") void loadSnapshot();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ordersWsState]);

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
