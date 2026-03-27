package com.interviewiq.job.dto;

import com.interviewiq.job.EmploymentType;
import com.interviewiq.job.LocationType;
import jakarta.validation.constraints.NotBlank;

public record CreateJobOpeningRequest(
        @NotBlank(message = "Job title is required")
        String title,

        String department,

        LocationType locationType,

        EmploymentType employmentType,

        String description
) {
}
