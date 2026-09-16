package com.example.urlshortener.service;

import com.example.urlshortener.config.AppProperties;
import com.example.urlshortener.dto.AnalyticsSummary;
import com.example.urlshortener.dto.PageResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.UrlResponse;
import com.example.urlshortener.exception.ApiException;
import com.example.urlshortener.model.UrlMapping;
import com.example.urlshortener.repository.UrlRepository;
import com.example.urlshortener.util.Base62Encoder;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UrlService {

    /** Paths served by the app itself, which must not be claimed as short codes. */
    private static final Set<String> RESERVED_CODES =
            Set.of("api", "h2-console", "actuator", "index.html", "favicon.ico", "robots.txt", "assets");

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final UrlRepository repository;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public UrlService(UrlRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public UrlResponse shorten(ShortenRequest request) {
        String longUrl = normalizeTarget(request.url());
        Instant now = Instant.now();
        Instant expiresAt =
                request.expiresInDays() == null ? null : now.plus(Duration.ofDays(request.expiresInDays()));

        if (StringUtils.hasText(request.customAlias())) {
            return save(new UrlMapping(validateAlias(request.customAlias()), longUrl, now, expiresAt), true);
        }

        // Codes are random rather than sequential so they cannot be walked to enumerate links.
        for (int attempt = 0; attempt < properties.getMaxCodeAttempts(); attempt++) {
            UrlMapping candidate = new UrlMapping(generateCode(), longUrl, now, expiresAt);
            try {
                return save(candidate, false);
            } catch (ApiException conflict) {
                // Lost the race to an identical code; fall through and try another one.
            }
        }
        throw ApiException.serverError("Could not allocate a unique short code, please retry");
    }

    /** Resolves a code for redirection and counts the click. */
    @Transactional
    public String resolveTarget(String shortCode) {
        UrlMapping mapping = repository
                .findByShortCode(shortCode)
                .orElseThrow(() -> ApiException.notFound("No link found for code " + shortCode));
        if (mapping.isExpired(Instant.now())) {
            throw ApiException.gone("Link " + shortCode + " has expired");
        }
        String target = mapping.getLongUrl();
        repository.incrementClickCount(shortCode);
        return target;
    }

    @Transactional(readOnly = true)
    public UrlResponse stats(String shortCode) {
        return repository
                .findByShortCode(shortCode)
                .map(mapping -> UrlResponse.from(mapping, properties.getBaseUrl()))
                .orElseThrow(() -> ApiException.notFound("No link found for code " + shortCode));
    }

    @Transactional(readOnly = true)
    public PageResponse<UrlResponse> list(Pageable pageable) {
        Page<UrlMapping> page = repository.findAllByOrderByCreatedAtDescIdDesc(pageable);
        return PageResponse.from(page, mapping -> UrlResponse.from(mapping, properties.getBaseUrl()));
    }

    /** Aggregates for the dashboard, computed over every link rather than the current page. */
    @Transactional(readOnly = true)
    public AnalyticsSummary summary() {
        long totalLinks = repository.count();
        long expiredLinks = repository.countByExpiresAtBefore(Instant.now());
        UrlResponse mostClicked = repository
                .findFirstByOrderByClickCountDescIdDesc()
                // An untouched link is not a meaningful "busiest link", so report none.
                .filter(mapping -> mapping.getClickCount() > 0)
                .map(mapping -> UrlResponse.from(mapping, properties.getBaseUrl()))
                .orElse(null);
        return new AnalyticsSummary(
                totalLinks, repository.sumClickCounts(), totalLinks - expiredLinks, expiredLinks, mostClicked);
    }

    @Transactional
    public void delete(String shortCode) {
        if (repository.deleteByShortCode(shortCode) == 0) {
            throw ApiException.notFound("No link found for code " + shortCode);
        }
    }

    /**
     * Saved outside a surrounding transaction so a unique-constraint violation can be turned into a
     * retry or a 409 rather than poisoning an in-flight transaction.
     */
    private UrlResponse save(UrlMapping mapping, boolean aliasWasChosen) {
        if (repository.existsByShortCode(mapping.getShortCode())) {
            throw conflictFor(mapping.getShortCode(), aliasWasChosen);
        }
        try {
            UrlMapping saved = repository.saveAndFlush(mapping);
            return UrlResponse.from(saved, properties.getBaseUrl());
        } catch (DataIntegrityViolationException exception) {
            throw conflictFor(mapping.getShortCode(), aliasWasChosen);
        }
    }

    private ApiException conflictFor(String shortCode, boolean aliasWasChosen) {
        return ApiException.conflict(
                aliasWasChosen ? "Alias " + shortCode + " is already taken" : "Generated code collided");
    }

    private String generateCode() {
        int length = properties.getCodeLength();
        return Base62Encoder.encodePadded(random.nextLong(Base62Encoder.capacityFor(length)), length);
    }

    private String validateAlias(String alias) {
        String trimmed = alias.trim();
        if (RESERVED_CODES.contains(trimmed.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("Alias " + trimmed + " is reserved");
        }
        return trimmed;
    }

    /**
     * Accepts only absolute http(s) URLs. Rejecting other schemes matters because the value is
     * echoed back into a Location header: a {@code javascript:} or {@code data:} target would turn
     * every short link into a script-injection vector.
     */
    private String normalizeTarget(String url) {
        String trimmed = url.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException exception) {
            throw ApiException.badRequest("url is not a valid URL: " + exception.getReason());
        }
        if (!uri.isAbsolute() || uri.getScheme() == null) {
            throw ApiException.badRequest("url must be absolute and start with http:// or https://");
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("url scheme must be http or https, got " + uri.getScheme());
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw ApiException.badRequest("url must include a host");
        }
        return trimmed;
    }
}
