package com.interviewiq.scheduling.dto;

import java.time.LocalDateTime;

public record CreateSlotRequest(
        LocalDateTime startTime,
        LocalDateTime endTime,
        int maxInterviews
) {
}
