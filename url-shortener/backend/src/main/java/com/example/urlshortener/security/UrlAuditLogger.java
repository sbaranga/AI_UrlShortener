package com.example.urlshortener.security;

import com.example.urlshortener.model.UrlAuditEvent;
import com.example.urlshortener.repository.UrlAuditRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UrlAuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(UrlAuditLogger.class);

    private final UrlAuditRepository repository;

    public UrlAuditLogger(UrlAuditRepository repository) {
        this.repository = repository;
    }

    public void created(String username, String shortCode) {
        record("CREATE", username, shortCode);
    }

    public void deleted(String username, String shortCode) {
        record("DELETE", username, shortCode);
    }

    private void record(String action, String userId, String shortCode) {
        repository.save(new UrlAuditEvent(action, userId, shortCode, Instant.now()));
        LOGGER.info("URL_ACTIVITY action={} userId={} shortCode={}", action, userId, shortCode);
    }
}