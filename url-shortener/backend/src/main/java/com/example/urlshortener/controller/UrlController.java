package com.example.urlshortener.controller;

import com.example.urlshortener.dto.AnalyticsSummary;
import com.example.urlshortener.dto.PageResponse;
import com.example.urlshortener.dto.ShortenRequest;
import com.example.urlshortener.dto.UrlResponse;
import com.example.urlshortener.service.UrlService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UrlController {

    /** Same character set and length bounds the service enforces for aliases. */
    private static final String CODE_PATTERN = "[A-Za-z0-9_-]{3,16}";

    /** Versioned prefix, so a future breaking change can ship alongside as /api/v2. */
    private static final String API = "/api/v1";

    private static final int MAX_PAGE_SIZE = 100;

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @PostMapping(API + "/shorten")
    @ResponseStatus(HttpStatus.CREATED)
    public UrlResponse shorten(@Valid @RequestBody ShortenRequest request) {
        return urlService.shorten(request);
    }

    /**
     * Tracked mappings for the dashboard, newest first. Paged rather than returning the whole
     * table, so the response stays bounded as the number of links grows.
     */
    @GetMapping(API + "/analytics")
    public PageResponse<UrlResponse> analytics(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return urlService.list(PageRequest.of(safePage, safeSize));
    }

    /** Totals over every link, which a single page of {@code /analytics} cannot show. */
    @GetMapping(API + "/analytics/summary")
    public AnalyticsSummary summary() {
        return urlService.summary();
    }

    @GetMapping(API + "/urls/{shortCode}")
    public UrlResponse stats(@PathVariable String shortCode) {
        return urlService.stats(shortCode);
    }

    @DeleteMapping(API + "/urls/{shortCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String shortCode) {
        urlService.delete(shortCode);
    }

    /**
     * Public redirect. Uses 302 with no-store so browsers come back through the service on every
     * visit and clicks keep being counted.
     */
    @GetMapping("/{shortCode:" + CODE_PATTERN + "}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = urlService.resolveTarget(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
