package com.interviewiq.company.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A tenant on the InterviewIQ platform.
 *
 * <p>Every other domain entity is scoped to a company via {@code companyId}.
 * Multi-tenant isolation is enforced at the service layer: every service method
 * that loads a resource must call {@code SecurityContext.requireSameCompany(entity.getCompanyId())}
 * before returning or mutating the resource.
 *
 * <p>DB table: {@code companies} (V001)
 */
@Entity
@Table(name = "companies")
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    /**
     * URL-safe platform identifier. Immutable after creation.
     * Used for routing: {@code /company/{slug}/dashboard}.
     */
    @Column(nullable = false, length = 100, unique = true)
    private String slug;

    /**
     * Optional corporate email domain (e.g. {@code acme.com}) used for
     * domain-based company resolution during Google OAuth. Not the primary
     * identifier — {@code slug} is.
     */
    @Column(length = 255)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private CompanyStatus status = CompanyStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public CompanyStatus getStatus() { return status; }
    public void setStatus(CompanyStatus status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Company{id=" + id + ", slug='" + slug + "'}";
    }
}
