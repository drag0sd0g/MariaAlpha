package com.mariaalpha.executionengine.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request body for {@code POST /api/execution/baskets} — submits a program/basket order: a set of
 * independent legs that are fanned out simultaneously through the standard execution pipeline (risk
 * → SOR → venue) and tracked as one aggregate.
 *
 * @param name optional human-readable label (e.g. {@code "SP500-rebalance-2026-06-14"}); a UUID
 *     basket id is always generated regardless.
 * @param legs the basket constituents — at least one, capped to keep a single request bounded.
 */
public record BasketOrderRequest(
    String name, @NotEmpty @Size(max = 500) @Valid List<BasketLegRequest> legs) {}
