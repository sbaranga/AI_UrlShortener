package com.example.urlshortener.dto;

/**
 * Totals across every link, so the dashboard can show figures that a single page of results
 * cannot reveal.
 *
 * @param mostClicked the busiest link, or null while no link has been clicked yet
 */
public record AnalyticsSummary(
        long totalLinks, long totalClicks, long activeLinks, long expiredLinks, UrlResponse mostClicked) {
}
