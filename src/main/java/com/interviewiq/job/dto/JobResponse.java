package com.interviewiq.job.dto;

import com.interviewiq.job.domain.EmploymentType;
import com.interviewiq.job.domain.JobOpening;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.job.domain.LocationType;
import com.interviewiq.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobResponse(
        UUID           id,
        UUID           companyId,
        UUID           createdBy,
        String         title,
        String         department,
        LocationType   locationType,
        EmploymentType employmentType,
        String         jdS3Key,
        PipelineStatus jdExtractionStatus,
        JobStatus      status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static JobResponse from(JobOpening job) {
        return new JobResponse(
                job.getId(),
                job.getCompanyId(),
                job.getCreatedBy(),
                job.getTitle(),
                job.getDepartment(),
                job.getLocationType(),
                job.getEmploymentType(),
                job.getJdS3Key(),
                job.getJdExtractionStatus(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
