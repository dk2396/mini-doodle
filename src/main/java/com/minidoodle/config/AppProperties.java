package com.minidoodle.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@NotNull Slots slots, @NotNull Aggregate aggregate) {

    public record Slots(
            @Min(1) int minDurationMinutes,
            @Min(1) int maxDurationMinutes,
            @Min(1) int maxFutureDays,
            @Min(1) int maxPageSize
    ) {}

    public record Aggregate(@Min(0) int cacheTtlSeconds) {}
}
