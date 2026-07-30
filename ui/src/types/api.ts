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

// --- Roadmap 4.6.1 — portfolio construction & risk (analytics-service) ---

export interface PortfolioPosition {
  symbol: string;
  quantity: number;
  avgEntryPrice: number;
  markPrice: number;
  markSource: string;
  notionalUsd: number;
  realizedPnl: number;
  unrealizedPnl: number;
  updatedAt: string | null;
}

export interface PortfolioStateResponse {
  positions: PortfolioPosition[];
  navUsd: number;
  baseNavUsd: number;
  grossExposureUsd: number;
  netExposureUsd: number;
  navSource: string;
  positionCount: number;
}

export interface OptimizeResponse {
  objective: string;
  symbols: string[];
  weights: Record<string, number>;
  expectedReturn: number;
  volatility: number;
  sharpe: number;
  riskContributions: Record<string, number>;
  diversificationRatio: number;
  effectiveN: number;
  converged: boolean;
  iterations: number;
  message: string;
  expectedReturnSource: string;
}

export interface FrontierPoint {
  expectedReturn: number;
  volatility: number;
  sharpe: number;
  weights: Record<string, number>;
}
export interface FrontierResponse {
  symbols: string[];
  points: FrontierPoint[];
  expectedReturnSource: string;
}

export interface VarResult {
  method: string;
  confidence: number;
  horizonDays: number;
  varUsd: number;
  expectedShortfallUsd: number;
  portfolioVolatilityUsd: number;
  observations: number | null;
  simulations: number | null;
  sufficient: boolean;
  notes: string[];
}

export interface ComponentVarRow {
  symbol: string;
  notionalUsd: number;
  standaloneVarUsd: number;
  marginalVarUsd: number;
  componentVarUsd: number;
  pctOfTotal: number;
}

export interface RiskVarResponse {
  asOf: string;
  symbols: string[];
  navUsd: number;
  grossExposureUsd: number;
  netExposureUsd: number;
  confidence: number;
  horizonDays: number;
  parametric: VarResult;
  historical: VarResult | null;
  monteCarlo: VarResult | null;
  components: ComponentVarRow[];
  diversificationRatio: number;
  sumOfAbsolutesVarUsd: number;
  varLimitUsd: number;
  breachesVarLimit: boolean;
}

export interface FactorsResponse {
  symbols: string[];
  factors: string[];
  exposures: Record<string, number>;
  varianceContributions: Record<string, number>;
  systematicVariance: number;
  idiosyncraticVariance: number;
  modelVariance: number;
  covarianceVariance: number;
  modelFit: number;
  systematicVariancePct: number;
  idiosyncraticVariancePct: number;
  modelVolatility: number;
  covarianceVolatility: number;
  pca: {
    varianceExplained: number[];
    cumulativeVarianceExplained: number[];
    portfolioLoadings: number[];
    topComponentSymbols: { symbol: string; loading: number }[];
  };
  notes: string[];
}

export interface StressScenarioResult {
  name: string;
  description: string;
  pnlUsd: number;
  pnlPctOfNav: number;
  worstSymbol: string;
  worstSymbolPnlUsd: number;
  breachesLimit: boolean;
}
export interface StressResponse {
  navUsd: number;
  lossLimitUsd: number;
  scenarios: StressScenarioResult[];
}

export interface TradeLeg {
  symbol: string;
  side: "BUY" | "SELL";
  currentShares: number;
  targetShares: number;
  deltaShares: number;
  notionalUsd: number;
  linearCostUsd: number;
  impactCostUsd: number;
  totalCostUsd: number;
}

export interface RebalanceResponse {
  symbols: string[];
  legs: TradeLeg[];
  suppressedLegs: TradeLeg[];
  currentWeights: Record<string, number>;
  targetWeights: Record<string, number>;
  navUsd: number;
  turnoverPct: number;
  estimatedCostUsd: number;
  expectedUtilityGain: number;
  converged: boolean;
  message: string;
  basketOrderRequest: {
    name: string;
    legs: { symbol: string; side: string; orderType: string; quantity: number; tif: string }[];
  };
  submitEnabled: boolean;
}
