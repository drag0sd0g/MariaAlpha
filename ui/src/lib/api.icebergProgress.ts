import { api } from "@/lib/api";
import type { IcebergProgress } from "@/types/api";

export const fetchIcebergProgress = (parentOrderId: string): Promise<IcebergProgress> =>
  api<IcebergProgress>(`/api/execution/orders/${parentOrderId}/iceberg-progress`);
