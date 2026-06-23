package com.minidoodle.dto.request;

import com.minidoodle.domain.SlotStatus;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public record AggregateAvailabilityRequest(
        @NotEmpty @Size(max = 50) Set<Long> userIds,
        @NotNull Instant from,
        @NotNull Instant to,
        SlotStatus status   // optional - default FREE
) {}
