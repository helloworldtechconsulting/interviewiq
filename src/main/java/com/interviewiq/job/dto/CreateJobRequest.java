package com.interviewiq.job.dto;

import com.interviewiq.job.domain.EmploymentType;
import com.interviewiq.job.domain.LocationType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(

        @NotBlank(message = "Job title is required.")
        @Size(max = 255, message = "Title must be at most 255 characters.")
        String title,

        @Size(max = 255, message = "Department must be at most 255 characters.")
        String department,

        LocationType locationType,

        EmploymentType employmentType,

        String description,

        @Min(value = 0, message = "Minimum experience cannot be negative.")
        Integer experienceMin,

        @Min(value = 0, message = "Maximum experience cannot be negative.")
        Integer experienceMax
) {}
