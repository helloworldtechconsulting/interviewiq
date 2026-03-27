package com.interviewiq.scheduling.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SlotResponse(
        UUID id,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int maxInterviews,
        int bookedCount,
        boolean available
) {
}
