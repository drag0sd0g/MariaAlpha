import { useEffect, useRef, useState } from "react";
import { buildWsUrl, type WsEndpoint } from "@/lib/wsUrl";
import { useConnectionStore, type ConnectionState } from "@/stores/connectionStore";

interface Options<T> {
  endpoint: WsEndpoint;
  query?: Record<string, string>;
  onMessage: (msg: T) => void;
  enabled?: boolean;
}

const INITIAL_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

export function useWebSocket<T>(opts: Options<T>): { state: ConnectionState } {
  const { endpoint, query, onMessage, enabled = true } = opts;
  const [state, setLocalState] = useState<ConnectionState>("connecting");
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage; // always invoke the latest closure

  // Capture query snapshot so the dep array doesn't churn on every render.
  const queryKey = JSON.stringify(query ?? {});

  useEffect(() => {
    if (!enabled) {
      useConnectionStore.getState().remove(endpoint);
      setLocalState("closed");
      return;
    }

    let socket: WebSocket | null = null;
    let backoff = INITIAL_BACKOFF_MS;
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let unmounted = false;

    const setBoth = (s: ConnectionState): void => {
      setLocalState(s);
      useConnectionStore.getState().setState(endpoint, s);
    };

    const connect = (): void => {
      setBoth("connecting");
      try {
        socket = new WebSocket(buildWsUrl(endpoint, query));
      } catch (err) {
        // Configuration error (missing key) — never recoverable; do not retry.
        console.error(`useWebSocket(${endpoint}) buildWsUrl failed`, err);
        setBoth("error");
        return;
      }

      socket.onopen = () => {
        if (unmounted) return;
        backoff = INITIAL_BACKOFF_MS;
        setBoth("open");
      };

      socket.onmessage = (ev) => {
        if (unmounted) return;
        try {
          const parsed = JSON.parse(typeof ev.data === "string" ? ev.data : "") as T;
          onMessageRef.current(parsed);
        } catch (err) {
          console.warn(`useWebSocket(${endpoint}) bad payload`, err);
        }
      };

      socket.onerror = () => {
        if (unmounted) return;
        setBoth("error");
      };

      socket.onclose = (ev) => {
        if (unmounted) return;
        if (ev.code === 1000) {
          setBoth("closed");
          return;
        }
        setBoth("closed");
        reconnectTimer = setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
      };
    };

    connect();

    return () => {
      unmounted = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      if (socket && socket.readyState === WebSocket.OPEN) socket.close(1000);
      useConnectionStore.getState().remove(endpoint);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- queryKey captures `query`
  }, [endpoint, queryKey, enabled]);

  return { state };
}
