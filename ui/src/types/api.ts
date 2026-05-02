// REST response types — match Java DTOs in order-manager and post-trade.

export type Side = "BUY" | "SELL";
export type OrderType = "MARKET" | "LIMIT" | "STOP";
export type OrderStatus =
  | "NEW"
  | "SUBMITTED"
  | "PARTIALLY_FILLED"
  | "FILLED"
  | "CANCELLED"
  | "REJECTED";

// PortfolioSummaryResponse.java
export interface PortfolioSummary {
  totalValue: number;
  cashBalance: number;
  grossExposure: number;
  netExposure: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  openPositions: number;
  asOf: string; // ISO 8601
}

// PositionResponse.java
export interface Position {
  symbol: string;
  netQuantity: number;
  avgEntryPrice: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  lastMarkPrice: number;
  updatedAt: string;
}

// OrderResponse.java (without fills in list endpoint, with fills on /api/orders/{id})
export interface Order {
  orderId: string; // UUID
  clientOrderId?: string;
  symbol: string;
  side: Side;
  orderType: OrderType;
  quantity: number;
  limitPrice?: number;
  stopPrice?: number;
  status: OrderStatus;
  strategy?: string;
  filledQuantity?: number;
  avgFillPrice?: number;
  exchangeOrderId?: string;
  venue?: string;
  createdAt: string;
  updatedAt: string;
  fills?: Fill[];
}

// FillResponse.java
export interface Fill {
  fillId: string;
  orderId?: string;
  exchangeFillId: string;
  symbol: string;
  side: Side;
  fillPrice: number;
  fillQuantity: number;
  commission?: number;
  venue?: string;
  filledAt: string;
}

// SubmitOrderRequest / SubmitOrderResponse — see execution-engine ManualOrderService.
export interface SubmitOrderRequest {
  symbol: string;
  side: Side;
  orderType: OrderType;
  quantity: number;
  limitPrice?: number;
  stopPrice?: number;
  clientOrderId?: string;
}

export interface SubmitOrderResponse {
  orderId: string;
  status: OrderStatus;
  acceptedAt: string;
}

// WS payload types — match Java records in market-data-gateway / order-manager / execution-engine.

// MarketTick.java
export type MarketTickEventType = "TRADE" | "QUOTE" | "BAR";
export type MarketTickSource = "ALPACA" | "SIMULATED" | "IBKR";
export interface MarketTick {
  symbol: string;
  timestamp: string;
  eventType: MarketTickEventType;
  price: number;
  size: number;
  bidPrice: number;
  askPrice: number;
  bidSize: number;
  askSize: number;
  cumulativeVolume: number;
  source: MarketTickSource;
  stale: boolean;
}

// PositionSnapshot.java (note: schema differs from PositionResponse — no totalPnl)
export interface PositionUpdate {
  symbol: string;
  netQuantity: number;
  avgEntryPrice: number;
  realizedPnl: number;
  unrealizedPnl: number;
  lastMarkPrice: number;
  timestamp: string;
}

// OrderEvent.java
export interface OrderEvent {
  orderId: string;
  status: OrderStatus;
  order?: OrderSnapshot;
  fill?: WsFill;
  reason?: string;
  timestamp: string;
}
export interface OrderSnapshot {
  orderId: string;
  symbol: string;
  side: Side;
  quantity: number;
  orderType: OrderType;
  limitPrice?: number;
  stopPrice?: number;
  strategyName: string;
  filledQuantity: number;
  avgFillPrice: number;
  exchangeOrderId?: string;
}
export interface WsFill {
  fillId: string;
  orderId: string;
  fillPrice: number;
  fillQuantity: number;
  venue: string;
  filledAt: string;
}

// RiskAlert.java
export interface RiskAlert {
  symbol: string;
  alertType: string;
  severity: string;
  message: string;
  timestamp: string;
}
