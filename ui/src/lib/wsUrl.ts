export type WsEndpoint = "/ws/market-data" | "/ws/positions" | "/ws/orders" | "/ws/alerts";

type MaConfig = { apiKey?: string; apiBaseUrl?: string };

export function buildWsUrl(endpoint: WsEndpoint, query: Record<string, string> = {}): string {
  const runtimeConfig: MaConfig =
    (typeof window !== "undefined" && (window as unknown as { MA_CONFIG?: MaConfig }).MA_CONFIG) ||
    {};
  const apiKey = runtimeConfig.apiKey ?? import.meta.env.VITE_MARIAALPHA_API_KEY ?? "";
  if (!apiKey)
    throw new Error("API key is not set (window.MA_CONFIG.apiKey or VITE_MARIAALPHA_API_KEY)");

  const baseRaw = runtimeConfig.apiBaseUrl ?? import.meta.env.VITE_API_BASE_URL ?? "";
  const wsBase = baseRaw.replace(/^http:\/\//, "ws://").replace(/^https:\/\//, "wss://");

  const params = new URLSearchParams({ apiKey, ...query });
  const origin = wsBase || (location.protocol === "https:" ? "wss://" : "ws://") + location.host;
  return `${origin}${endpoint}?${params.toString()}`;
}
