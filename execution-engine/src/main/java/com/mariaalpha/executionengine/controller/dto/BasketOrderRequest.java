package com.mariaalpha.executionengine.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BasketOrderRequest(
    String name, @NotEmpty @Size(max = 500) @Valid List<BasketLegRequest> legs) {}
