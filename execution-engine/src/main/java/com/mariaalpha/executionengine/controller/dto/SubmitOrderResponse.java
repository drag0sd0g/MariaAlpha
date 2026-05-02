package com.mariaalpha.executionengine.controller.dto;

import com.mariaalpha.executionengine.model.OrderStatus;
import java.time.Instant;

public record SubmitOrderResponse(String orderId, OrderStatus status, Instant acceptedAt) {}
