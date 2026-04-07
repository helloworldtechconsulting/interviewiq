package com.interviewiq.job.dto;

import com.interviewiq.job.domain.EmploymentType;
import com.interviewiq.job.domain.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobRequest(

        @NotBlank(message = "Job title is required.")
        @Size(max = 255, message = "Title must be at most 255 characters.")
        String title,

        @Size(max = 255, message = "Department must be at most 255 characters.")
        String department,

        LocationType locationType,

        EmploymentType employmentType
) {}
