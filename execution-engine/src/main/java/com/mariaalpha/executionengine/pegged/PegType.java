package com.mariaalpha.executionengine.pegged;

/**
 * Reference price the order's working limit price is pegged to (roadmap 3.2.3).
 *
 * <ul>
 *   <li>{@link #MIDPOINT} — pegs to {@code (bid + ask) / 2}. Common in dark pools and
 *       internalisation engines; the order rests at the midpoint and crosses against incoming flow
 *       on the opposite side.
 *   <li>{@link #PRIMARY} — pegs to the join-side: a BUY rests at the bid, a SELL rests at the ask.
 *       Passive — the order adds liquidity and waits to be lifted/hit.
 *   <li>{@link #MARKET} — pegs to the take-side: a BUY tracks the ask, a SELL tracks the bid.
 *       Aggressive — the order is poised to cross as soon as the opposite side flinches.
 * </ul>
 */
public enum PegType {
  MIDPOINT,
  PRIMARY,
  MARKET
}
