export class ApiError extends Error {
  constructor(
    public status: number,
    public path: string,
    message: string,
  ) {
    super(message);
  }
}

// Runtime config (window.MA_CONFIG) is rendered into /config.js by the UI Helm
// chart's init container. The Vite env vars are used as fallback for `npm run dev`.
type MaConfig = { apiKey?: string; apiBaseUrl?: string };
const runtimeConfig: MaConfig =
  (typeof window !== "undefined" && (window as unknown as { MA_CONFIG?: MaConfig }).MA_CONFIG) ||
  {};

const baseUrl = runtimeConfig.apiBaseUrl ?? import.meta.env.VITE_API_BASE_URL ?? "";
const apiKey = runtimeConfig.apiKey ?? import.meta.env.VITE_MARIAALPHA_API_KEY ?? "";

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!apiKey) {
    throw new ApiError(
      0,
      path,
      "API key is not set (window.MA_CONFIG.apiKey or VITE_MARIAALPHA_API_KEY)",
    );
  }
  const headers = new Headers(init.headers);
  headers.set("X-API-Key", apiKey);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${baseUrl}${path}`, { ...init, headers });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new ApiError(res.status, path, body || res.statusText);
  }
  // Some endpoints (DELETE) return 204 with no body.
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
