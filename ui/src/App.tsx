import { Routes, Route } from "react-router-dom";
import Layout from "@/components/Layout";
import ConnectionStatus from "@/components/ConnectionStatus";
import ComingSoon from "@/components/ComingSoon";
import AlertsBanner from "@/components/AlertsBanner";
import Dashboard from "@/pages/Dashboard";
import OrderEntry from "@/pages/OrderEntry";
import Rfq from "@/pages/Rfq";
import Options from "@/pages/Options";
import Strategies from "@/pages/Strategies";
import Analytics from "@/pages/Analytics";
import Reconciliation from "@/pages/Reconciliation";
import Allocations from "@/pages/Allocations";
import { useWebSocket } from "@/hooks/useWebSocket";
import { useAlertStore } from "@/stores/alertStore";
import { usePositionStore } from "@/stores/positionStore";
import { useOrderStore } from "@/stores/orderStore";
import type { OrderEvent, PositionUpdate, RiskAlert } from "@/types/api";

function AppWideStreams() {
  const applyPosition = usePositionStore((s) => s.applyUpdate);
  const applyOrder = useOrderStore((s) => s.applyEvent);
  const pushAlert = useAlertStore((s) => s.push);

  useWebSocket<PositionUpdate>({ endpoint: "/ws/positions", onMessage: applyPosition });
  useWebSocket<OrderEvent>({ endpoint: "/ws/orders", onMessage: applyOrder });
  useWebSocket<RiskAlert>({ endpoint: "/ws/alerts", onMessage: pushAlert });

  return null;
}

export default function App() {
  return (
    <>
      <AppWideStreams />
      <ConnectionStatus />
      <AlertsBanner />
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="orders" element={<OrderEntry />} />
          <Route path="market-data" element={<ComingSoon page="Market Data" />} />
          <Route path="rfq" element={<Rfq />} />
          <Route path="options" element={<Options />} />
          <Route path="strategies" element={<Strategies />} />
          <Route path="analytics" element={<Analytics />} />
          <Route path="reconciliation" element={<Reconciliation />} />
          <Route path="allocations" element={<Allocations />} />
        </Route>
      </Routes>
    </>
  );
}
