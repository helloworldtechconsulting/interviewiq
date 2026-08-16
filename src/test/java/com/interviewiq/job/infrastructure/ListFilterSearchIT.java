package com.interviewiq.job.infrastructure;

import com.interviewiq.candidate.infrastructure.CandidateRepository;
import com.interviewiq.job.domain.JobStatus;
import com.interviewiq.shared.domain.PipelineStatus;
import com.interviewiq.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the nullable-filter queries behind the jobs and candidates list pages
 * (INTIQ-21).
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>Both queries take a nullable {@code search} parameter and use it in a
 * {@code LIKE}. When it is null, PostgreSQL has nothing to infer a type from,
 * guesses {@code bytea}, and the statement fails outright:
 *
 * <pre>ERROR: operator does not exist: text ~~ bytea</pre>
 *
 * <p>Null search is not an edge case — it is the <em>default</em>, since the
 * filter is optional. Both list endpoints returned 500 on the first request
 * their page made. The fix is an explicit {@code CAST(:search AS String)}.
 *
 * <p>Nothing caught it: the mocked service tests stub the repository, so the
 * query text was never sent to a database. Only a real PostgreSQL rejects it,
 * which is what this test provides. It is deliberately about the parameter
 * <em>combinations</em> rather than about matching behaviour — every
 * combination is a distinct SQL statement, and any of them can fail to type.
 */
class ListFilterSearchIT extends AbstractPostgresIntegrationTest {

    @Autowired JobOpeningRepository jobRepository;
    @Autowired CandidateRepository candidateRepository;
    @Autowired DataSource dataSource;

    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID USER_ID    = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID JOB_ID     = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    @BeforeEach
    void seed() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.update("INSERT INTO companies (id, name, slug, status) VALUES (?, ?, ?, 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING", COMPANY_ID, "Filter Co", "filter-co");
        jdbc.update("INSERT INTO users (id, company_id, full_name, email, password_hash, role) "
                + "VALUES (?, ?, ?, ?, 'x', 'ADMIN') ON CONFLICT (id) DO NOTHING",
                USER_ID, COMPANY_ID, "Filter Admin", "filter@example.com");
        jdbc.update("INSERT INTO job_openings (id, company_id, created_by, title, department, "
                + "jd_extraction_status, status) VALUES (?, ?, ?, ?, ?, 'DONE', 'ACTIVE') "
                + "ON CONFLICT (id) DO NOTHING",
                JOB_ID, COMPANY_ID, USER_ID, "Backend Engineer", "Engineering");
        jdbc.update("INSERT INTO candidates (id, company_id, job_opening_id, email, full_name, candidate_ref) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                UUID.fromString("00000000-0000-0000-0000-0000000000f4"), COMPANY_ID, JOB_ID,
                "asha@example.com", "Asha Menon", "cand_filter_1");
    }

    // =========================================================================
    // Jobs
    // =========================================================================

    /** The exact request the jobs page makes on load: no filters at all. */
    @Test
    void jobSearchWithNoFiltersDoesNotFail() {
        assertThatCode(() ->
                jobRepository.search(COMPANY_ID, null, null, PageRequest.of(0, 20)).getContent())
                .doesNotThrowAnyException();

        assertThat(jobRepository.search(COMPANY_ID, null, null, PageRequest.of(0, 20)))
                .hasSize(1);
    }

    /** Each combination is a different statement, so each needs its own check. */
    @Test
    void everyJobFilterCombinationTypesCorrectly() {
        var page = PageRequest.of(0, 20);
        assertThatCode(() -> {
            jobRepository.search(COMPANY_ID, null, null, page).getContent();
            jobRepository.search(COMPANY_ID, JobStatus.ACTIVE, null, page).getContent();
            jobRepository.search(COMPANY_ID, null, "backend", page).getContent();
            jobRepository.search(COMPANY_ID, JobStatus.ACTIVE, "backend", page).getContent();
        }).doesNotThrowAnyException();
    }

    @Test
    void jobSearchStillMatchesOnTitleAndDepartment() {
        var page = PageRequest.of(0, 20);
        assertThat(jobRepository.search(COMPANY_ID, null, "backend", page)).hasSize(1);
        assertThat(jobRepository.search(COMPANY_ID, null, "engineering", page)).hasSize(1);
        assertThat(jobRepository.search(COMPANY_ID, null, "nothing-matches-this", page)).isEmpty();
    }

    // =========================================================================
    // Candidates
    // =========================================================================

    @Test
    void candidateSearchWithNoFiltersDoesNotFail() {
        assertThatCode(() ->
                candidateRepository.search(COMPANY_ID, null, null, null, PageRequest.of(0, 20)).getContent())
                .doesNotThrowAnyException();

        assertThat(candidateRepository.search(COMPANY_ID, null, null, null, PageRequest.of(0, 20)))
                .hasSize(1);
    }

    @Test
    void everyCandidateFilterCombinationTypesCorrectly() {
        var page = PageRequest.of(0, 20);
        assertThatCode(() -> {
            candidateRepository.search(COMPANY_ID, null, null, null, page).getContent();
            candidateRepository.search(COMPANY_ID, JOB_ID, null, null, page).getContent();
            candidateRepository.search(COMPANY_ID, null, PipelineStatus.PENDING, null, page).getContent();
            candidateRepository.search(COMPANY_ID, null, null, "asha", page).getContent();
            candidateRepository.search(COMPANY_ID, JOB_ID, PipelineStatus.PENDING, "asha", page).getContent();
        }).doesNotThrowAnyException();
    }

    @Test
    void candidateSearchStillMatchesOnNameAndEmail() {
        var page = PageRequest.of(0, 20);
        assertThat(candidateRepository.search(COMPANY_ID, null, null, "asha", page)).hasSize(1);
        assertThat(candidateRepository.search(COMPANY_ID, null, null, "example.com", page)).hasSize(1);
        assertThat(candidateRepository.search(COMPANY_ID, null, null, "zzz", page)).isEmpty();
    }
}
