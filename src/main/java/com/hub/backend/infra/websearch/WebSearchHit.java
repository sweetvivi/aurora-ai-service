package com.hub.backend.infra.websearch;

/**
 * One web search result fed back to the model.
 *
 * @param title       page title
 * @param url         canonical url
 * @param snippet     short excerpt / summary
 * @param site        optional site name
 * @param publishedAt optional true publish time from the search vendor (raw string)
 * @param crawledAt   optional crawl/index time; must not be treated as publish time
 */
public record WebSearchHit(
        String title,
        String url,
        String snippet,
        String site,
        String publishedAt,
        String crawledAt) {
}
