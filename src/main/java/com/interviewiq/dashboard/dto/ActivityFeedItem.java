// src/main/java/com/interviewiq/dashboard/dto/ActivityFeedItem.java

package com.interviewiq.dashboard.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ActivityFeedItem(
        UUID           sessionId,
        String         candidateName,
        String         jobTitle,
        Integer        overallScore,
        OffsetDateTime completedAt
) {}