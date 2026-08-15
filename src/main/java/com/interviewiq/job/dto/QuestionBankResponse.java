package com.interviewiq.job.dto;

import com.interviewiq.shared.domain.PipelineStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The generated question bank for a job, for employer preview (PRD v2.1 §11).
 *
 * <h2>Why an employer is shown this at all</h2>
 *
 * <p>Every candidate for this opening draws their interview from this bank, and
 * until now the employer could not see it. That is an odd place for a hiring
 * product to be: the questions are asked on the company's behalf, and the
 * company carries the consequences of a bad one. §7.10 puts the hiring decision
 * with a human — that is hard to mean seriously if the human cannot read the
 * questions first.
 *
 * <p>Retired questions are included rather than filtered out, marked as such.
 * An employer looking at a thin bank needs to see <em>why</em> it is thin, and
 * "eleven of these were retired for producing no signal" is the answer.
 *
 * @param status      whether generation has run; a bank in PENDING has no
 *                    questions yet and that is normal, not an error
 * @param totalCount  questions in the bank, including retired ones
 * @param activeCount questions a candidate can still draw
 */
public record QuestionBankResponse(
        PipelineStatus  status,
        OffsetDateTime  generatedAt,
        int             totalCount,
        int             activeCount,
        List<Question>  questions
) {

    /**
     * @param core    true when this question is asked of every candidate — the
     *                comparable spine of the interview (§7.3)
     * @param retired true when telemetry has taken it out of rotation; it stays
     *                visible so the count makes sense
     */
    public record Question(
            String  id,
            String  text,
            String  category,
            String  dimension,
            String  rationale,
            int     rank,
            boolean core,
            boolean retired
    ) {}

    /** A job whose bank has not been generated yet. */
    public static QuestionBankResponse notGenerated(PipelineStatus status) {
        return new QuestionBankResponse(status, null, 0, 0, List.of());
    }
}
