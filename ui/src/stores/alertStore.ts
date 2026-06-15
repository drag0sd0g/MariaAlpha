import { create } from "zustand";
import type { RiskAlert } from "@/types/api";

export interface AlertEntry extends RiskAlert {
  receivedAt: number;
  dismissed: boolean;
}

interface AlertStore {
  alerts: AlertEntry[];
  push: (a: RiskAlert) => void;
  dismiss: (receivedAt: number) => void;
  clear: () => void;
}

const MAX_ALERTS = 50;

export const useAlertStore = create<AlertStore>((set) => ({
  alerts: [],
  push: (a) => {
    set((s) => ({
      alerts: [{ ...a, receivedAt: Date.now(), dismissed: false }, ...s.alerts].slice(
        0,
        MAX_ALERTS,
      ),
    }));
  },
  dismiss: (receivedAt) => {
    set((s) => ({
      alerts: s.alerts.map((al) =>
        al.receivedAt === receivedAt ? { ...al, dismissed: true } : al,
      ),
    }));
  },
  clear: () => {
    set(() => ({ alerts: [] }));
  },
}));
