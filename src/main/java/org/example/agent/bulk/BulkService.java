package org.example.agent.bulk;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.NoCredentialsException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.agent.ApiException;
import org.example.agent.PinAgent;
import org.example.agent.PinGenerationException;
import org.example.agent.PinWriter;
import org.example.agent.brand.BrandService;
import org.example.agent.model.PinIdea;
import org.example.agent.model.PinRequest;
import org.example.agent.model.PinResponse;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs bulk jobs: each page goes through the regular {@link PinAgent}, a few at a time, in the background. */
@ApplicationScoped
public class BulkService {

    private static final Logger LOG = Logger.getLogger(BulkService.class);
    private static final TypeReference<List<PinIdea>> PIN_LIST = new TypeReference<>() {
    };

    private final PinAgent agent;
    private final FeedReader feeds;
    private final BrandService brands;
    private final ObjectMapper json;
    private final int maxItems;
    private final int concurrency;

    public BulkService(PinAgent agent, FeedReader feeds, BrandService brands, ObjectMapper json,
                       @ConfigProperty(name = "pinmate.bulk.max-items", defaultValue = "50") int maxItems,
                       @ConfigProperty(name = "pinmate.bulk.concurrency", defaultValue = "2") int concurrency) {
        this.agent = agent;
        this.feeds = feeds;
        this.brands = brands;
        this.json = json;
        this.maxItems = Math.max(1, maxItems);
        this.concurrency = Math.max(1, concurrency);
    }

    public BulkViews.Discovery discover(String source) {
        List<BulkViews.DiscoveredPage> pages = feeds.discover(source).stream()
                .map(e -> new BulkViews.DiscoveredPage(e.url(), Topics.forItem(e.title(), e.url())))
                .toList();
        return new BulkViews.Discovery(source.strip(), pages, maxItems);
    }

    @Transactional
    public BulkViews.Job create(BulkRequest request) {
        Map<String, BulkRequest.Item> unique = new LinkedHashMap<>();
        for (BulkRequest.Item item : request.items()) {
            String url = item.url().strip();
            if (!FeedReader.isWebUrl(url)) {
                throw new ApiException(400, "invalid_url", "Not an http(s) URL: " + url);
            }
            unique.putIfAbsent(url, new BulkRequest.Item(url, item.topic()));
        }
        if (unique.size() > maxItems) {
            throw new ApiException(400, "too_many_items",
                    "A job can have at most " + maxItems + " pages; this one has " + unique.size() + ".");
        }
        brands.resolve(request.brandId()); // 400 for an unknown brand
        BulkJob job = new BulkJob();
        job.brandId = request.brandId();
        job.source = blankToNull(request.source());
        job.audience = blankToNull(request.audience());
        job.tone = blankToNull(request.tone());
        job.variations = request.variations() == null ? PinRequest.DEFAULT_VARIATIONS : request.variations();
        job.createdAt = Instant.now();
        job.persist();
        int seq = 0;
        for (BulkRequest.Item item : unique.values()) {
            BulkItem i = new BulkItem();
            i.job = job;
            i.seq = seq++;
            i.url = item.url();
            i.topic = Topics.forItem(item.topic(), item.url());
            i.status = BulkItem.Status.PENDING;
            i.persist();
        }
        return view(job, true);
    }

    @Transactional
    public List<BulkViews.Job> list() {
        Map<Long, Map<BulkItem.Status, Integer>> counts = new LinkedHashMap<>();
        List<Object[]> rows = BulkItem.getEntityManager()
                .createQuery("select i.job.id, i.status, count(i) from BulkItem i group by i.job.id, i.status", Object[].class)
                .getResultList();
        for (Object[] row : rows) {
            counts.computeIfAbsent((Long) row[0], k -> new EnumMap<>(BulkItem.Status.class))
                    .put((BulkItem.Status) row[1], ((Long) row[2]).intValue());
        }
        return BulkJob.<BulkJob>listAll(Sort.descending("createdAt")).stream()
                .map(job -> jobView(job, counts.getOrDefault(job.id, Map.of()), null))
                .toList();
    }

    @Transactional
    public BulkViews.Job get(long id) {
        return view(find(id), true);
    }

    /** Stops pages that haven't started; pages already running finish. */
    @Transactional
    public BulkViews.Job cancel(long id) {
        BulkJob job = find(id);
        BulkItem.update("status = ?1 where job = ?2 and status = ?3",
                BulkItem.Status.CANCELLED, job, BulkItem.Status.PENDING);
        return view(job, true);
    }

    /** Queues failed and cancelled pages again. */
    @Transactional
    public BulkViews.Job retry(long id) {
        BulkJob job = find(id);
        BulkItem.update("status = ?1, error = null where job = ?2 and status in ?3",
                BulkItem.Status.PENDING, job, List.of(BulkItem.Status.FAILED, BulkItem.Status.CANCELLED));
        return view(job, true);
    }

    @Transactional
    public void delete(long id) {
        BulkJob job = find(id);
        if (BulkItem.count("job = ?1 and status = ?2", job, BulkItem.Status.RUNNING) > 0) {
            throw new ApiException(409, "running", "Pages of this job are being written right now. Cancel it and try again shortly.");
        }
        BulkItem.delete("job", job);
        job.delete();
    }

    /** All pins of a job as CSV, one row per pin. */
    @Transactional
    public String csv(long id) {
        BulkJob job = find(id);
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel reads UTF-8
        sb.append("url,topic,variation,title,description,hashtags,alt_text,suggested_boards,image_idea\r\n");
        for (BulkItem item : items(job)) {
            List<PinIdea> pins = pins(item);
            for (int v = 0; v < pins.size(); v++) {
                PinIdea p = pins.get(v);
                sb.append(String.join(",",
                        Csv.cell(item.url), Csv.cell(item.topic), String.valueOf(v + 1),
                        Csv.cell(p.title()), Csv.cell(p.description()),
                        Csv.cell(p.hashtags() == null ? "" : String.join(" ", p.hashtags())),
                        Csv.cell(p.altText()),
                        Csv.cell(p.suggestedBoards() == null ? "" : String.join("; ", p.suggestedBoards())),
                        Csv.cell(p.imageIdea())))
                        .append("\r\n");
            }
        }
        return sb.toString();
    }

    // --- processing ---

    /** Pages a worker was writing when the app stopped are simply written again. */
    @Transactional
    void requeueInterrupted(@Observes StartupEvent event) {
        long n = BulkItem.update("status = ?1 where status = ?2", BulkItem.Status.PENDING, BulkItem.Status.RUNNING);
        if (n > 0) {
            LOG.infof("Re-queued %d interrupted bulk page(s)", n);
        }
    }

    @Scheduled(every = "{pinmate.bulk.poll-interval}", delayed = "5s",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        processPending();
    }

    /** Writes pins for every pending page, {@code concurrency} at a time; returns how many pages were processed. */
    public int processPending() {
        AtomicInteger processed = new AtomicInteger();
        try (ExecutorService workers = Executors.newFixedThreadPool(concurrency)) {
            for (int w = 0; w < concurrency; w++) {
                workers.submit(() -> {
                    Long id;
                    while ((id = claimNext()) != null) {
                        process(id);
                        processed.incrementAndGet();
                    }
                });
            }
        }
        return processed.get();
    }

    /** Marks the oldest pending page RUNNING and returns its id, or null when there is none. */
    private Long claimNext() {
        for (int tries = 0; tries < 5; tries++) {
            Long claimed = QuarkusTransaction.requiringNew().call(() -> {
                List<Long> next = BulkItem.getEntityManager()
                        .createQuery("select i.id from BulkItem i where i.status = :s order by i.job.id, i.seq", Long.class)
                        .setParameter("s", BulkItem.Status.PENDING)
                        .setMaxResults(1)
                        .getResultList();
                if (next.isEmpty()) {
                    return -1L;
                }
                long id = next.get(0);
                return BulkItem.update("status = ?1 where id = ?2 and status = ?3",
                        BulkItem.Status.RUNNING, id, BulkItem.Status.PENDING) == 1 ? id : null;
            });
            if (claimed == null) {
                continue; // another worker took it
            }
            return claimed == -1L ? null : claimed;
        }
        return null;
    }

    private record Work(String url, String topic, String audience, String tone, int variations, Long brandId) {
    }

    private void process(long id) {
        Work work = QuarkusTransaction.requiringNew().call(() -> {
            BulkItem i = BulkItem.findById(id);
            return new Work(i.url, i.topic, i.job.audience, i.job.tone, i.job.variations, i.job.brandId);
        });
        try {
            PinResponse result = agent.generate(new PinRequest(work.topic(), work.url(), work.audience(), work.tone(),
                    work.variations()), null, brands.resolveIfPresent(work.brandId()));
            String pinsJson = json.writeValueAsString(result.pins());
            QuarkusTransaction.requiringNew().run(() -> {
                BulkItem i = BulkItem.findById(id);
                i.status = BulkItem.Status.DONE;
                i.pinsJson = pinsJson;
                i.pageRead = result.source() != null && result.source().fetched();
                i.pageSummary = result.source() == null ? null : PinWriter.truncate(result.source().summary(), 2000);
                i.model = result.model();
                i.attempts = result.attempts();
                i.error = null;
                i.warnings = result.warnings().isEmpty() ? null
                        : PinWriter.truncate(String.join("\n", result.warnings()), 2000);
                i.finishedAt = Instant.now();
            });
        } catch (RuntimeException | JsonProcessingException e) {
            String message = describe(e);
            LOG.warnf("Bulk page %d (%s) failed: %s", id, work.url(), message);
            QuarkusTransaction.requiringNew().run(() -> {
                BulkItem i = BulkItem.findById(id);
                i.status = BulkItem.Status.FAILED;
                i.error = PinWriter.truncate(message, 1000);
                i.finishedAt = Instant.now();
            });
        }
    }

    /** A short reason for the page's error column, matching what the REST error mappers would say. */
    static String describe(Exception e) {
        Throwable t = e instanceof RuntimeException r ? PinAgent.unwrap(r) : e;
        if (t instanceof PinGenerationException p) {
            return p.getMessage();
        }
        if (t instanceof NoCredentialsException || t instanceof UnauthorizedException || t instanceof PermissionDeniedException) {
            return "The Claude API credentials are missing or invalid. Set ANTHROPIC_API_KEY.";
        }
        if (t instanceof RateLimitException) {
            return "Rate limited by the Claude API. Retry later.";
        }
        if (t instanceof AnthropicIoException) {
            return "The Claude API could not be reached.";
        }
        if (t instanceof AnthropicServiceException s) {
            return "The Claude API returned an error (HTTP " + s.statusCode() + ").";
        }
        LOG.error("Unexpected error in a bulk job", e);
        return "Unexpected error: " + t.getClass().getSimpleName();
    }

    // --- views ---

    private BulkViews.Job view(BulkJob job, boolean withItems) {
        List<BulkItem> items = items(job);
        Map<BulkItem.Status, Integer> counts = new EnumMap<>(BulkItem.Status.class);
        for (BulkItem i : items) {
            counts.merge(i.status, 1, Integer::sum);
        }
        return jobView(job, counts, withItems ? items.stream().map(this::itemView).toList() : null);
    }

    private static BulkViews.Job jobView(BulkJob job, Map<BulkItem.Status, Integer> c, List<BulkViews.Item> items) {
        int pending = c.getOrDefault(BulkItem.Status.PENDING, 0);
        int running = c.getOrDefault(BulkItem.Status.RUNNING, 0);
        int done = c.getOrDefault(BulkItem.Status.DONE, 0);
        int failed = c.getOrDefault(BulkItem.Status.FAILED, 0);
        int cancelled = c.getOrDefault(BulkItem.Status.CANCELLED, 0);
        BulkViews.Counts counts = new BulkViews.Counts(pending + running + done + failed + cancelled,
                pending, running, done, failed, cancelled);
        return new BulkViews.Job(job.id, pending + running > 0 ? "RUNNING" : "FINISHED", job.source, job.audience,
                job.tone, job.variations, job.brandId, job.createdAt, counts, items);
    }

    private BulkViews.Item itemView(BulkItem i) {
        return new BulkViews.Item(i.id, i.seq, i.url, i.topic, i.status, i.pageRead, i.pageSummary, i.model,
                i.attempts, i.error, pins(i), i.warnings == null ? List.of() : List.of(i.warnings.split("\n")),
                i.finishedAt);
    }

    private List<PinIdea> pins(BulkItem item) {
        if (item.pinsJson == null) {
            return List.of();
        }
        try {
            return json.readValue(item.pinsJson, PIN_LIST);
        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Unreadable pins for bulk page %d", item.id);
            return List.of();
        }
    }

    private static List<BulkItem> items(BulkJob job) {
        return new ArrayList<>(BulkItem.list("job", Sort.by("seq"), job));
    }

    private static BulkJob find(long id) {
        BulkJob job = BulkJob.findById(id);
        if (job == null) {
            throw new ApiException(404, "not_found", "No bulk job with id " + id + ".");
        }
        return job;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** CSV cells, quoted, with spreadsheet formula injection neutralised. */
    static final class Csv {
        private Csv() {
        }

        static String cell(String value) {
            String v = value == null ? "" : value;
            if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
                v = "'" + v;
            }
            return '"' + v.replace("\"", "\"\"") + '"';
        }
    }
}
