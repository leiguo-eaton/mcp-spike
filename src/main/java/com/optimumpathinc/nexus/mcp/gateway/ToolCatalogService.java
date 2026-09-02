package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the aggregated, global tool catalog (D10/D11).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li><b>Lazy</b> — nothing is discovered at startup. The first authenticated request that needs
 *       the catalog triggers {@code initialize} + {@code tools/list} on every configured backend
 *       using <em>that request's</em> validated access token.</li>
 *   <li><b>Global</b> — downstream tool definitions are identity-independent, so one snapshot serves
 *       every user. No token or user identity is stored; the token is a parameter only.</li>
 *   <li><b>Request-driven refresh</b> — the freshness TTL is a threshold, not a schedule. Nothing is
 *       contacted until the next catalog-dependent request arrives after the TTL elapsed.</li>
 *   <li><b>Single-flight</b> — concurrent callers that find the catalog missing or stale serialize
 *       on one lock; the winner discovers and publishes, the others observe the fresh snapshot.</li>
 *   <li><b>Graceful degradation</b> — a partial catalog is published when at least one backend
 *       succeeds; failed backends keep their last-known-good entries on refresh; a totally failed
 *       refresh serves the previous snapshot without advancing its timestamp.</li>
 * </ul>
 */
public class ToolCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogService.class);

    private final BackendRegistry registry;
    private final BackendMcpClient client;
    private final Duration freshnessTtl;
    private final Duration failureBackoff;
    private final Clock clock;

    private final AtomicReference<ToolCatalogSnapshot> published = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final ReentrantLock discoveryLock = new ReentrantLock();

    public ToolCatalogService(BackendRegistry registry, BackendMcpClient client,
            Duration freshnessTtl, Duration failureBackoff, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.freshnessTtl = freshnessTtl;
        this.failureBackoff = failureBackoff;
        this.clock = clock;
    }

    /**
     * Returns a usable catalog snapshot, discovering or refreshing it on demand with the caller's
     * validated access token.
     *
     * @throws GatewayMcpException {@code catalog_unavailable} when no snapshot exists and discovery
     *                             cannot (yet) produce one
     */
    public ToolCatalogSnapshot obtain(String accessToken) {
        ToolCatalogSnapshot current = published.get();
        if (current != null && isFresh(current)) {
            return current;
        }
        discoveryLock.lock();
        try {
            // Re-check: another thread may have completed discovery while we waited (single-flight).
            current = published.get();
            if (current != null && isFresh(current)) {
                return current;
            }
            if (inFailureBackoff()) {
                if (current != null) {
                    return current;
                }
                throw new GatewayMcpException(GatewayErrorCode.CATALOG_UNAVAILABLE);
            }
            return discover(accessToken, current);
        } finally {
            discoveryLock.unlock();
        }
    }

    /** The currently published snapshot, or {@code null} while the catalog is uninitialized. */
    public ToolCatalogSnapshot current() {
        return published.get();
    }

    private boolean isFresh(ToolCatalogSnapshot snapshot) {
        return clock.instant().isBefore(snapshot.refreshedAt().plus(freshnessTtl));
    }

    private boolean inFailureBackoff() {
        Instant failedAt = lastFailureAt.get();
        return failedAt != null && clock.instant().isBefore(failedAt.plus(failureBackoff));
    }

    private ToolCatalogSnapshot discover(String accessToken, ToolCatalogSnapshot previous) {
        Map<String, BackendCatalogEntry> entries = new LinkedHashMap<>();
        Map<String, GatewayErrorCode> failures = new LinkedHashMap<>();

        for (BackendDefinition backend : registry.backends()) {
            try {
                List<JsonNode> tools = client.listTools(backend, accessToken);
                entries.put(backend.prefix(), BackendCatalogEntry.of(backend.prefix(), tools));
            } catch (InvalidBackendCatalogException e) {
                log.warn("Backend '{}' returned an invalid tool catalog: {}", backend.prefix(), e.getMessage());
                failures.put(backend.prefix(), GatewayErrorCode.INVALID_BACKEND_CATALOG);
            } catch (RuntimeException e) {
                log.warn("Backend '{}' discovery failed: {}", backend.prefix(), e.getMessage());
                failures.put(backend.prefix(), GatewayErrorCode.BACKEND_UNAVAILABLE);
            }
        }

        Instant now = clock.instant();
        if (entries.isEmpty()) {
            lastFailureAt.set(now);
            if (previous != null) {
                // Total refresh failure: keep serving last-known-good, do not advance its timestamp.
                return previous;
            }
            throw new GatewayMcpException(GatewayErrorCode.CATALOG_UNAVAILABLE);
        }

        // Partial failure on refresh: a failed backend keeps its last-known-good tools and routes.
        if (previous != null) {
            for (String prefix : List.copyOf(failures.keySet())) {
                BackendCatalogEntry lastKnownGood = previous.entries().get(prefix);
                if (lastKnownGood != null) {
                    entries.put(prefix, lastKnownGood);
                    failures.remove(prefix);
                }
            }
        }

        if (failures.isEmpty()) {
            lastFailureAt.set(null);
        } else {
            lastFailureAt.set(now);
        }
        ToolCatalogSnapshot snapshot = ToolCatalogSnapshot.build(registry, entries, failures, now);
        published.set(snapshot);
        return snapshot;
    }
}
