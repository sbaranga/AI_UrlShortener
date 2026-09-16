package com.example.urlshortener.dto;

import com.example.urlshortener.model.UrlMapping;
import java.time.Instant;

public record UrlResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt,
        long clickCount) {

    public static UrlResponse from(UrlMapping mapping, String baseUrl) {
        return new UrlResponse(
                mapping.getShortCode(),
                baseUrl + "/" + mapping.getShortCode(),
                mapping.getLongUrl(),
                mapping.getCreatedAt(),
                mapping.getExpiresAt(),
                mapping.getClickCount());
    }
}
