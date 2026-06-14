package com.mariaalpha.executionengine.basket;

/**
 * Aggregate state of a program/basket order, derived from the statuses of its legs.
 *
 * <ul>
 *   <li>{@link #REJECTED} — every leg was rejected at submission (risk/validation); nothing is
 *       working at any venue.
 *   <li>{@link #SUBMITTED} — at least one leg was accepted; no fills yet.
 *   <li>{@link #PARTIALLY_FILLED} — some quantity has filled but the accepted legs are not all
 *       complete.
 *   <li>{@link #FILLED} — every accepted leg is fully filled.
 * </ul>
 */
public enum BasketStatus {
  SUBMITTED,
  PARTIALLY_FILLED,
  FILLED,
  REJECTED
}
