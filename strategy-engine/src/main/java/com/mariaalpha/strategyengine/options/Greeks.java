package com.mariaalpha.strategyengine.options;

/**
 * Bundle of first-order Black-Scholes-Merton sensitivities for a European option (roadmap 3.2.2).
 *
 * <ul>
 *   <li>{@code delta} (Δ) — change in option value per +1.00 change in the underlying.
 *       Dimensionless in {@code [−1, 1]}.
 *   <li>{@code gamma} (Γ) — change in {@link #delta} per +1.00 change in the underlying. Same for
 *       calls and puts.
 *   <li>{@code vega} — change in option value per +1 percentage-point change in volatility ({@code
 *       0.01} of σ). Reported per-1%-vol so the UI can show "vega = 0.18 per 1% vol".
 *   <li>{@code theta} — change in option value per one passing day. Sign convention: typically
 *       negative for long options. Day count comes from {@link
 *       OptionsPricingConfig#thetaDayCount()}.
 *   <li>{@code rho} — change in option value per +1 percentage-point change in the risk-free rate.
 *       Reported per-1%-rate so the UI can show "rho = 0.12 per 1% rate".
 * </ul>
 */
public record Greeks(double delta, double gamma, double vega, double theta, double rho) {}
