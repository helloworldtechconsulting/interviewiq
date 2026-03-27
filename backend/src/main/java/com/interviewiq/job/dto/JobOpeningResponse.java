package com.interviewiq.job.dto;

import com.interviewiq.job.EmploymentType;
import com.interviewiq.job.JobStatus;
import com.interviewiq.job.LocationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record JobOpeningResponse(
        UUID id,
        String title,
        String department,
        LocationType locationType,
        EmploymentType employmentType,
        String description,
        JobStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
