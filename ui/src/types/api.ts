
export type Side = "BUY" | "SELL";
export type OrderType = "MARKET" | "LIMIT" | "STOP" | "IOC" | "FOK" | "GTC" | "ICEBERG" | "PEGGED";
export type PegType = "MIDPOINT" | "PRIMARY" | "MARKET";
export type TimeInForce = "DAY" | "IOC" | "FOK" | "GTC";
export type OrderStatus =
  | "NEW"
  | "SUBMITTED"
  | "PARTIALLY_FILLED"
  | "FILLED"
  | "CANCELLED"
  | "REJECTED";

export interface PortfolioSummary {
  totalValue: number;
  cashBalance: number;
  grossExposure: number;
  netExposure: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  openPositions: number;
  asOf: string;
}

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

export interface Order {
  orderId: string;
  clientOrderId?: string;
  parentOrderId?: string;
  symbol: string;
  side: Side;
  orderType: OrderType;
  quantity: number;
  limitPrice?: number;
  stopPrice?: number;
  displayQuantity?: number;
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

export interface SubmitOrderRequest {
  symbol: string;
  side: Side;
  orderType: OrderType;
  quantity: number;
  limitPrice?: number;
  stopPrice?: number;
  displayQuantity?: number;
  tif?: TimeInForce;
  clientOrderId?: string;
  pegType?: PegType;
  pegOffsetBps?: number;
}

export interface PeggedProgress {
  parentOrderId: string;
  totalQuantity: number;
  filledQuantity: number;
  remainingQuantity: number;
  repegsTotal: number;
  lastReferencePrice?: number;
  lastSubmittedPrice?: number;
  activeChildOrderId?: string;
  parentComplete: boolean;
}

export interface IcebergProgress {
  parentOrderId: string;
  totalQuantity: number;
  displayQuantity: number;
  submittedQuantity: number;
  filledQuantity: number;
  slicesSubmitted: number;
  activeChildOrderId?: string;
}

export interface SubmitOrderResponse {
  orderId: string;
  status: OrderStatus;
  submittedAt: string;
}


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

export interface PositionUpdate {
  symbol: string;
  netQuantity: number;
  avgEntryPrice: number;
  realizedPnl: number;
  unrealizedPnl: number;
  lastMarkPrice: number;
  timestamp: string;
}

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

export interface RiskAlert {
  symbol: string;
  alertType: string;
  severity: string;
  message: string;
  timestamp: string;
}
