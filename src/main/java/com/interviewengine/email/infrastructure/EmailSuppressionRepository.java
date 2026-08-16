package com.interviewengine.email.infrastructure;

import com.interviewengine.email.domain.EmailSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, UUID> {

    boolean existsByEmail(String email);

    Optional<EmailSuppression> findByEmail(String email);
}
