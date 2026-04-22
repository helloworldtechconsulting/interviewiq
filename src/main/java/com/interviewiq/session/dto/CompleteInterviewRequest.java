package com.interviewiq.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Sent by the browser when the candidate finishes all questions.
 *
 * <p>The {@code answers} list pairs each question's 1-based {@code questionOrder}
 * with the speech-recognition transcript captured by the browser.
 *
 * <p>The optional {@code proctoringFlags} list captures anti-cheat signals
 * (tab-switch count, multi-face detections) observed during the interview.
 * An empty list means no anomalies were detected.
 */
public record CompleteInterviewRequest(

        @NotNull
        @Valid
        List<QuestionAnswer> answers,

        List<ProctoringFlag> proctoringFlags,

        /** S3 key the browser uploaded the WebM recording to. Null if upload failed. */
        String recordingS3Key
) {

    public record QuestionAnswer(
            int    questionOrder,
            String transcript
    ) {}

    public record ProctoringFlag(
            String type,
            int    count,
            String firstOccurrence
    ) {}
}
