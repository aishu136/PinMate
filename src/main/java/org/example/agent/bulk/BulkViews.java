package org.example.agent.bulk;

import org.example.agent.model.PinIdea;

import java.time.Instant;
import java.util.List;

/** What the bulk API returns. */
public final class BulkViews {

    private BulkViews() {
    }

    /** A page found by {@code POST /api/bulk/discover}. */
    public record DiscoveredPage(String url, String topic) {
    }

    public record Discovery(String source, List<DiscoveredPage> pages, int maxItemsPerJob) {
    }

    public record Counts(int total, int pending, int running, int done, int failed, int cancelled) {
    }

    /**
     * @param status {@code RUNNING} while any page is pending or running, otherwise {@code FINISHED}
     * @param items  null in job lists
     */
    public record Job(long id, String status, String source, String audience, String tone, int variations,
                      Long brandId, Instant createdAt, Counts counts, List<Item> items) {
    }

    /**
     * @param pageRead null until processed
     * @param pins     empty until done
     * @param warnings brand rules the pins still break
     */
    public record Item(long id, int seq, String url, String topic, BulkItem.Status status, Boolean pageRead,
                       String pageSummary, String model, int attempts, String error, List<PinIdea> pins,
                       List<String> warnings, Instant finishedAt) {
    }
}
