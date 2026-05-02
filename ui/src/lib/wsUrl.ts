export type WsEndpoint = "/ws/market-data" | "/ws/positions" | "/ws/orders" | "/ws/alerts";

export function buildWsUrl(endpoint: WsEndpoint, query: Record<string, string> = {}): string {
  const apiKey = import.meta.env.VITE_MARIAALPHA_API_KEY ?? "";
  if (!apiKey) throw new Error("VITE_MARIAALPHA_API_KEY is not set");

  const baseRaw = import.meta.env.VITE_API_BASE_URL ?? "";
  // Convert http(s) base to ws(s); empty base → relative URL goes through Vite proxy.
  const wsBase = baseRaw
    .replace(/^http:\/\//, "ws://")
    .replace(/^https:\/\//, "wss://");

  const params = new URLSearchParams({ apiKey, ...query });
  const origin =
    wsBase || (location.protocol === "https:" ? "wss://" : "ws://") + location.host;
  return `${origin}${endpoint}?${params.toString()}`;
}
