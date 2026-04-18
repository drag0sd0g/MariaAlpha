package com.mariaalpha.executionengine.model;

import java.time.Instant;

public record RiskAlert(
    String symbol, String alertType, String severity, String message, Instant timestamp) {}
