const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
});
const qty = new Intl.NumberFormat("en-US", { minimumFractionDigits: 0 });
const bps = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

export const fmtMoney = (v: number | string | null | undefined): string =>
  v == null ? "—" : money.format(typeof v === "string" ? parseFloat(v) : v);

export const fmtQty = (v: number | string | null | undefined): string =>
  v == null ? "—" : qty.format(typeof v === "string" ? parseFloat(v) : v);

export const fmtBps = (v: number | string | null | undefined): string =>
  v == null ? "—" : `${bps.format(typeof v === "string" ? parseFloat(v) : v)} bps`;

export const fmtPnl = (v: number | string | null | undefined): { text: string; cls: string } => {
  if (v == null) return { text: "—", cls: "" };
  const n = typeof v === "string" ? parseFloat(v) : v;
  return {
    text: fmtMoney(n),
    cls: n > 0 ? "text-green-600" : n < 0 ? "text-red-600" : "text-slate-600",
  };
};
