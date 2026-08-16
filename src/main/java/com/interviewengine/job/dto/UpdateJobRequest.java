package com.interviewengine.job.dto;

import com.interviewengine.job.domain.EmploymentType;
import com.interviewengine.job.domain.JobStatus;
import com.interviewengine.job.domain.LocationType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdateJobRequest(

        @Size(max = 255, message = "Title must be at most 255 characters.")
        String title,

        @Size(max = 255, message = "Department must be at most 255 characters.")
        String department,

        LocationType locationType,

        EmploymentType employmentType,

        JobStatus status,

        String description,

        @Min(value = 0, message = "Minimum experience cannot be negative.")
        Integer experienceMin,

        @Min(value = 0, message = "Maximum experience cannot be negative.")
        Integer experienceMax
) {}
