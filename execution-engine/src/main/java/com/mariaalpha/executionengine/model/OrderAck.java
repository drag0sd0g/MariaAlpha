package com.mariaalpha.executionengine.model;

public record OrderAck(String orderId, String exchangeOrderId, boolean accepted, String reason) {}
