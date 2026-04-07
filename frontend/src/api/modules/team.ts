import { apiClient } from "@/api/client";
import type { InviteTeamMemberRequest, TeamMember, UpdateMemberRequest } from "@/types";

const BASE = "/api/v1/team";

export const teamApi = {
  list: () =>
    apiClient.get<TeamMember[]>(BASE).then((r) => r.data),

  invite: (data: InviteTeamMemberRequest) =>
    apiClient.post<TeamMember>(`${BASE}/invite`, data).then((r) => r.data),

  updateMember: (userId: string, data: UpdateMemberRequest) =>
    apiClient
      .patch<TeamMember>(`${BASE}/${userId}`, data)
      .then((r) => r.data),
};
