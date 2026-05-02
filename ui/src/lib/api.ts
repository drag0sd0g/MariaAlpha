export class ApiError extends Error {
  constructor(
    public status: number,
    public path: string,
    message: string,
  ) {
    super(message);
  }
}

const baseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
const apiKey = import.meta.env.VITE_MARIAALPHA_API_KEY ?? "";

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (!apiKey) {
    throw new ApiError(0, path, "VITE_MARIAALPHA_API_KEY is not set");
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
