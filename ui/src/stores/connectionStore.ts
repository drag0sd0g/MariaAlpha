import { create } from "zustand";
import type { WsEndpoint } from "@/lib/wsUrl";

export type ConnectionState = "connecting" | "open" | "closed" | "error";

interface ConnectionStore {
  states: Partial<Record<WsEndpoint, ConnectionState>>;
  setState: (endpoint: WsEndpoint, state: ConnectionState) => void;
  remove: (endpoint: WsEndpoint) => void;
}

export const useConnectionStore = create<ConnectionStore>((set) => ({
  states: {},
  setState: (endpoint, state) => {
    set((s) => ({ states: { ...s.states, [endpoint]: state } }));
  },
  remove: (endpoint) => {
    set((s) => {
      const next = { ...s.states };
      // eslint-disable-next-line @typescript-eslint/no-dynamic-delete
      delete next[endpoint];
      return { states: next };
    });
  },
}));
