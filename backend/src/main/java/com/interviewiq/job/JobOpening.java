package com.interviewiq.job;

import com.interviewiq.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "job_openings", indexes = {
        @Index(name = "idx_job_openings_company", columnList = "company_id"),
        @Index(name = "idx_job_openings_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobOpening extends BaseEntity {

    @Column(nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private String title;

    private String department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LocationType locationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmploymentType employmentType;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String jdGcsPath;

    @Column(columnDefinition = "TEXT")
    private String jdExtractedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private UUID createdBy;
}
