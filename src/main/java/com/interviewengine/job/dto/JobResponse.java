package com.interviewengine.job.dto;

import com.interviewengine.job.domain.EmploymentType;
import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.job.domain.JobStatus;
import com.interviewengine.job.domain.LocationType;
import com.interviewengine.shared.domain.PipelineStatus;

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
        String         description,
        Integer        experienceMin,
        Integer        experienceMax,
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
                job.getDescription(),
                job.getExperienceMin(),
                job.getExperienceMax(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
