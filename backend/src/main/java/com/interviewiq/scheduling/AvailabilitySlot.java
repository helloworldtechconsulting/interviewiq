package com.interviewiq.scheduling;

import com.interviewiq.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "availability_slots", indexes = {
        @Index(name = "idx_slots_job_opening", columnList = "job_opening_id"),
        @Index(name = "idx_slots_start_time", columnList = "start_time")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AvailabilitySlot extends BaseEntity {

    @Column(nullable = false)
    private UUID jobOpeningId;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    @Column(nullable = false)
    private Integer maxInterviews;

    @Column(nullable = false)
    private Integer bookedCount = 0;

    @Version
    private Long version;
}
