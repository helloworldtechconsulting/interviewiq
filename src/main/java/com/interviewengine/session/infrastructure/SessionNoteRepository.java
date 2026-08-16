package com.interviewengine.session.infrastructure;

import com.interviewengine.session.domain.SessionNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SessionNoteRepository extends JpaRepository<SessionNote, UUID> {

    List<SessionNote> findAllByCompanyIdAndSessionIdOrderByCreatedAtAsc(UUID companyId, UUID sessionId);
}
