package com.interviewiq.candidate;

import com.interviewiq.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("TODO: enable once auth helpers + CreateCandidateRequest schema are confirmed")
class CandidateLifecycleIT extends AbstractIntegrationTest {

    @Test
    void createListGetCandidate_andPresignedUrl() {
        // 1. Register company + create job opening (helper).
        // 2. POST /api/v1/candidates → expect 201, Candidate row created.
        // 3. GET  /api/v1/candidates → list contains new candidate.
        // 4. GET  /api/v1/candidates/{id}/resume-upload-url?contentType=application/pdf
        //    → expect 200 + url+objectKey present (S3Presigner is mocked).
    }
}
