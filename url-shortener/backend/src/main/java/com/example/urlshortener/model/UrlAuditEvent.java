package com.example.urlshortener.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "url_audit_event")
public class UrlAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "short_code", nullable = false, length = 16)
    private String shortCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected UrlAuditEvent() {}

    public UrlAuditEvent(String action, String userId, String shortCode, Instant occurredAt) {
        this.action = action;
        this.userId = userId;
        this.shortCode = shortCode;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getUserId() {
        return userId;
    }

    public String getShortCode() {
        return shortCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
