package com.interviewiq.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CompleteInterviewRequest(

        @NotNull
        @Valid
        List<QuestionAnswer> answers,

        List<ProctoringFlag> proctoringFlags,

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
