package com.minidoodle.controller;

import com.minidoodle.dto.request.AggregateAvailabilityRequest;
import com.minidoodle.dto.response.AggregateAvailabilityResponse;
import com.minidoodle.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Availability", description = "Aggregated views of free/busy slots across users.")
@RestController
@RequestMapping("/api/v1/availability")
@RequiredArgsConstructor
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    @Operation(
        summary = "Aggregate availability across multiple users in a time window",
        description = "Returns, per user, the slots that match the requested status (default FREE) within [from, to). " +
                      "Designed for the 'find a common free slot' use case. Results are cached for a short TTL."
    )
    @PostMapping("/aggregate")
    public AggregateAvailabilityResponse aggregate(@Valid @RequestBody AggregateAvailabilityRequest req) {
        return availabilityService.aggregate(req);
    }
}
