import { apiClient } from "@/api/client";

export interface ActivityFeedItem {
    sessionId: string;
    candidateName: string;
    jobTitle: string;
    overallScore: number | null;
    completedAt: string;
}

export const dashboardApi = {
    getRecentActivity: () =>
        apiClient
            .get("/api/v1/dashboard/activity")
            .then((res) => res.data as ActivityFeedItem[]),
};