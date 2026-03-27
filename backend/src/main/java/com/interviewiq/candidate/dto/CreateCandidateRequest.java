package com.interviewiq.candidate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateCandidateRequest(
        @NotBlank(message = "Candidate name is required")
        String name,

        @NotBlank(message = "Email is required")
        @Email(message = "Email should be valid")
        String email,

        String phone
) {
}
