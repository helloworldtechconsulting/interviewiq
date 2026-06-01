package com.interviewiq.dashboard.infrastructure;

import com.interviewiq.dashboard.dto.ActivityFeedItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class DashboardRepository {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public List<ActivityFeedItem> findRecentActivity(UUID companyId) {
        String sql = """
                SELECT
                    s.id,
                    c.full_name,
                    j.title,
                    er.overall_score,
                    s.ended_at
                FROM interview_sessions s
                JOIN candidates c ON c.id = s.candidate_id
                JOIN job_openings j ON j.id = s.job_opening_id
                LEFT JOIN evaluation_reports er ON er.session_id = s.id
                WHERE s.company_id = :companyId
                  AND s.status = 'COMPLETED'
                ORDER BY s.ended_at DESC
                LIMIT 10
                """;

        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("companyId", companyId)
                .getResultList();

        List<ActivityFeedItem> result = new ArrayList<>();
        for (Object[] row : rows) {
            OffsetDateTime completedAt = null;
            if (row[4] != null) {
                if (row[4] instanceof java.time.Instant instant) {
                    completedAt = instant.atOffset(java.time.ZoneOffset.UTC);
                } else if (row[4] instanceof OffsetDateTime odt) {
                    completedAt = odt;
                }
            }
            result.add(new ActivityFeedItem(
                    UUID.fromString(row[0].toString()),
                    (String) row[1],
                    (String) row[2],
                    row[3] != null ? ((Number) row[3]).intValue() : null,
                    completedAt
            ));
        }
        return result;
    }
}