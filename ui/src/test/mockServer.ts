import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";

export const server = setupServer(
  http.get("/api/portfolio/summary", () =>
    HttpResponse.json({
      totalValue: 0,
      cashBalance: 0,
      grossExposure: 0,
      netExposure: 0,
      realizedPnl: 0,
      unrealizedPnl: 0,
      totalPnl: 0,
      openPositions: 0,
      asOf: new Date().toISOString(),
    }),
  ),
  http.get("/api/positions", () => HttpResponse.json([])),
  http.get("/api/orders", () => HttpResponse.json([])),
);
