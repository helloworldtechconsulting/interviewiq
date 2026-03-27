package com.interviewiq.candidate;

import com.interviewiq.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "candidates", indexes = {
        @Index(name = "idx_candidates_job", columnList = "job_opening_id"),
        @Index(name = "idx_candidates_company", columnList = "company_id"),
        @Index(name = "idx_candidates_email", columnList = "email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Candidate extends BaseEntity {

    @Column(nullable = false)
    private UUID companyId;

    @Column(nullable = false)
    private UUID jobOpeningId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    private String phone;

    private String resumeGcsPath;

    @Column(columnDefinition = "TEXT")
    private String resumeExtractedText;
}
