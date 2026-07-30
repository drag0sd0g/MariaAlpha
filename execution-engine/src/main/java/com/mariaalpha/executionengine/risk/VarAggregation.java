package com.mariaalpha.executionengine.risk;

/**
 * How per-position VaR is aggregated into a portfolio number.
 *
 * <p>{@link #SUM_OF_ABSOLUTES} is the pre-4.6.1 behaviour: {@code VaR_p = sum_i VaR_i}. That is the
 * rho = 1 corner of the covariance formula, so it is a strict upper bound — deliberately
 * conservative, but it credits no diversification at all, and because it takes absolute values it
 * cannot tell a hedge from a doubled-up position.
 *
 * <p>{@link #COVARIANCE} uses {@code VaR_p = z sqrt(v' Sigma_d v)} with signed notionals and the
 * correlation matrix published by analytics-service on {@code analytics.risk-model}. It falls back
 * to {@code SUM_OF_ABSOLUTES} automatically whenever no fresh model is available, so a dead or
 * lagging analytics service can never silently loosen the gate.
 */
public enum VarAggregation {
  COVARIANCE,
  SUM_OF_ABSOLUTES
}
