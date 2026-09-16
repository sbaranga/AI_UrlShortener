package com.example.urlshortener.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param customAlias optional caller-chosen code; a random one is generated when blank
 * @param expiresInDays optional lifetime; the link never expires when null
 */
public record ShortenRequest(
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Pattern(
                regexp = "^$|^[A-Za-z0-9_-]{3,16}$",
                message = "customAlias must be 3-16 characters of letters, digits, '-' or '_'")
        String customAlias,

        @Min(value = 1, message = "expiresInDays must be at least 1")
        @Max(value = 3650, message = "expiresInDays must be at most 3650")
        Integer expiresInDays) {
}
