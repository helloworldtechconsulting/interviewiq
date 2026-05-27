package com.interviewiq.dashboard.service;

import com.interviewiq.dashboard.dto.ActivityFeedItem;
import com.interviewiq.dashboard.infrastructure.DashboardRepository;
import com.interviewiq.shared.security.SecurityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DashboardService {

    private final DashboardRepository dashboardRepository;

    public DashboardService(DashboardRepository dashboardRepository) {
        this.dashboardRepository = dashboardRepository;
    }

    @Transactional(readOnly = true)
    public List<ActivityFeedItem> getRecentActivity() {
        UUID companyId = SecurityContext.requireCompanyId();
        return dashboardRepository.findRecentActivity(companyId);
    }
}