import { renderHook, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useWebSocket } from "./useWebSocket";
import { useConnectionStore } from "@/stores/connectionStore";

// ---------------------------------------------------------------------------
// Fake WebSocket
// ---------------------------------------------------------------------------

interface FakeSocket {
  url: string;
  readyState: number;
  onopen: ((ev: Event) => void) | null;
  onmessage: ((ev: MessageEvent) => void) | null;
  onerror: ((ev: Event) => void) | null;
  onclose: ((ev: CloseEvent) => void) | null;
  close: (code?: number) => void;
  triggerOpen: () => void;
  triggerMessage: (data: string) => void;
  triggerClose: (code: number) => void;
}

let lastSocket: FakeSocket | null = null;
const allSockets: FakeSocket[] = [];

class FakeWebSocket implements FakeSocket {
  url: string;
  readyState: number = WebSocket.CONNECTING;
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    lastSocket = this;
    allSockets.push(this);
  }

  close(code = 1000): void {
    this.readyState = WebSocket.CLOSED;
    this.onclose?.({ code, wasClean: code === 1000 } as CloseEvent);
  }

  triggerOpen(): void {
    this.readyState = WebSocket.OPEN;
    this.onopen?.(new Event("open"));
  }

  triggerMessage(data: string): void {
    this.onmessage?.({ data } as MessageEvent);
  }

  triggerClose(code: number): void {
    this.readyState = WebSocket.CLOSED;
    this.onclose?.({ code, wasClean: code === 1000 } as CloseEvent);
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

beforeEach(() => {
  allSockets.length = 0;
  lastSocket = null;
  vi.stubGlobal("WebSocket", FakeWebSocket);
  // Set env vars needed by buildWsUrl
  vi.stubGlobal("location", { protocol: "http:", host: "localhost:5173" });
  Object.assign(import.meta.env, {
    VITE_MARIAALPHA_API_KEY: "local-dev-key",
    VITE_API_BASE_URL: "",
  });
  useConnectionStore.setState({ states: {} });
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("useWebSocket", () => {
  it("mounts and constructs a WebSocket with apiKey in the URL", () => {
    renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn() }),
    );
    expect(allSockets).toHaveLength(1);
    expect(allSockets[0]!.url).toContain("apiKey=local-dev-key");
    expect(allSockets[0]!.url).toContain("/ws/positions");
  });

  it("parses messages and calls onMessage with parsed object", () => {
    const onMessage = vi.fn();
    renderHook(() => useWebSocket({ endpoint: "/ws/positions", onMessage }));
    act(() => {
      lastSocket?.triggerOpen();
      lastSocket?.triggerMessage('{"symbol":"AAPL","netQuantity":100}');
    });
    expect(onMessage).toHaveBeenCalledWith({ symbol: "AAPL", netQuantity: 100 });
  });

  it("drops malformed JSON without calling onMessage", () => {
    const onMessage = vi.fn();
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    renderHook(() => useWebSocket({ endpoint: "/ws/positions", onMessage }));
    act(() => {
      lastSocket?.triggerOpen();
      lastSocket?.triggerMessage("not-valid-json{{{");
    });
    expect(onMessage).not.toHaveBeenCalled();
    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it("reconnects after a non-1000 close after initial backoff", async () => {
    renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn() }),
    );
    act(() => {
      lastSocket?.triggerOpen();
      lastSocket?.triggerClose(1006);
    });
    expect(allSockets).toHaveLength(1);
    act(() => { vi.advanceTimersByTime(1_000); });
    expect(allSockets).toHaveLength(2);
  });

  it("doubles backoff on each reconnect up to 30s cap", () => {
    renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn() }),
    );
    const delays = [1, 2, 4, 8, 16, 30, 30, 30].map((s) => s * 1_000);
    for (const delay of delays) {
      act(() => {
        lastSocket?.triggerClose(1006);
        vi.advanceTimersByTime(delay);
      });
    }
    expect(allSockets.length).toBe(1 + delays.length);
  });

  it("closes with code 1000 on unmount", () => {
    const { unmount } = renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn() }),
    );
    act(() => { lastSocket?.triggerOpen(); });
    const closeSpy = vi.spyOn(lastSocket!, "close");
    unmount();
    expect(closeSpy).toHaveBeenCalledWith(1000);
  });

  it("does not construct a WebSocket when enabled=false", () => {
    renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn(), enabled: false }),
    );
    expect(allSockets).toHaveLength(0);
  });

  it("propagates connection state to connectionStore", () => {
    renderHook(() =>
      useWebSocket({ endpoint: "/ws/positions", onMessage: vi.fn() }),
    );
    expect(useConnectionStore.getState().states["/ws/positions"]).toBe("connecting");
    act(() => { lastSocket?.triggerOpen(); });
    expect(useConnectionStore.getState().states["/ws/positions"]).toBe("open");
    act(() => { lastSocket?.triggerClose(1006); });
    expect(useConnectionStore.getState().states["/ws/positions"]).toBe("closed");
  });
});
