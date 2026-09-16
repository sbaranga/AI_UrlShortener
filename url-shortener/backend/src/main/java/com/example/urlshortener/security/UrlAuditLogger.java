package com.example.urlshortener.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UrlAuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(UrlAuditLogger.class);

    public void created(String username, String shortCode) {
        LOGGER.info("URL_ACTIVITY action=CREATE user={} shortCode={}", username, shortCode);
    }

    public void deleted(String username, String shortCode) {
        LOGGER.info("URL_ACTIVITY action=DELETE user={} shortCode={}", username, shortCode);
    }
}