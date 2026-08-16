package com.interviewengine.candidate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateCandidateRequest(

        @NotNull(message = "Job opening ID is required.")
        UUID jobOpeningId,

        @NotBlank(message = "Email is required.")
        @Email(message = "Must be a valid email address.")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Full name is required.")
        @Size(max = 255)
        String fullName,

        @Size(max = 30, message = "Phone number must be at most 30 characters.")
        String phone
) {}
