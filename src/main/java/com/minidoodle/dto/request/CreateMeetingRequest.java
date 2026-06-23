package com.minidoodle.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateMeetingRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull Long organizerId,
        Set<Long> participantUserIds
) {}
