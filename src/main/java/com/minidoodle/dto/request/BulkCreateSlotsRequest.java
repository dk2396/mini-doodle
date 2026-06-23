package com.minidoodle.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkCreateSlotsRequest(
        @NotEmpty @Size(max = 500) @Valid List<CreateSlotRequest> slots
) {}
