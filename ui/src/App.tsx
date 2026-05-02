import { Routes, Route } from "react-router-dom";
import Layout from "@/components/Layout";
import ConnectionStatus from "@/components/ConnectionStatus";
import ComingSoon from "@/components/ComingSoon";
import Dashboard from "@/pages/Dashboard";
import OrderEntry from "@/pages/OrderEntry";

export default function App() {
  return (
    <>
      <ConnectionStatus />
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<Dashboard />} />
          <Route path="orders" element={<OrderEntry />} />
          <Route path="market-data" element={<ComingSoon page="Market Data" />} />
          <Route path="rfq" element={<ComingSoon page="RFQ" />} />
          <Route path="strategies" element={<ComingSoon page="Strategy Control" />} />
          <Route path="analytics" element={<ComingSoon page="Analytics" />} />
          <Route path="reconciliation" element={<ComingSoon page="Reconciliation" />} />
        </Route>
      </Routes>
    </>
  );
}
