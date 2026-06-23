package com.minidoodle.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 120) String displayName,
        @Size(max = 64) String timezone
) {}
