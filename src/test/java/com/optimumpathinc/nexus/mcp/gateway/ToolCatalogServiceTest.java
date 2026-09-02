package com.optimumpathinc.nexus.mcp.gateway;

import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.RecordingBackendMcpClient;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.richTool;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.tool;
import static com.optimumpathinc.nexus.mcp.gateway.GatewayTestSupport.toolNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tasks 7.16-7.18 — aggregation/namespacing, lazy loading and refresh, and concurrency/degradation
 * behaviour of the global tool catalog.
 */
class ToolCatalogServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration BACKOFF = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private ToolCatalogService service(BackendRegistry registry, BackendMcpClient client) {
        return new ToolCatalogService(registry, client, TTL, BACKOFF, clock);
    }

    private static BackendRegistry twoBackends() {
        return GatewayTestSupport.registry(
                "blss", "http://blss.local/mcp",
                "superset", "http://superset.local/mcp");
    }

    // --- 7.16 aggregation and namespacing -----------------------------------------------------

    @Test
    void mergesBackendToolsWithDoubleUnderscorePrefixes() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools())).containsExactly("blss__query_asset", "superset__run_sql");
    }

    @Test
    void identicalToolNamesFromTwoBackendsDoNotCollide() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("run_sql"))
                .withCatalog("superset", tool("run_sql"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools())).containsExactly("blss__run_sql", "superset__run_sql");
    }

    @Test
    void exposedNamesUseOnlyMcpToolNameCharactersAndNeverADot() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools()))
                .allSatisfy(name -> assertThat(name).matches("[a-zA-Z0-9_-]+").doesNotContain("."));
    }

    @Test
    void everyToolFieldExceptTheNameIsPreserved() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", richTool("query_asset"));

        ToolCatalogSnapshot snapshot = service(blssOnly(), client).obtain("token");

        JsonNode exposed = snapshot.tools().get(0);
        JsonNode original = richTool("query_asset");
        assertThat(exposed.get("name").asString()).isEqualTo("blss__query_asset");
        for (String field : List.of("description", "title", "inputSchema", "outputSchema",
                "annotations", "x-vendor-extension")) {
            assertThat(exposed.get(field)).as(field).isEqualTo(original.get(field));
        }
        assertThat(exposed.properties()).hasSameSizeAs(original.properties());
    }

    @Test
    void aRoutingTableEntryExistsForEveryDiscoveredBackend() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(snapshot.routes()).containsOnlyKeys("blss", "superset");
    }

    @Test
    void aStructurallyInvalidCatalogFailsThatBackendAsAUnit() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withDiscoveryFailure("superset", new InvalidBackendCatalogException("no tools array"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools())).containsExactly("blss__query_asset");
        assertThat(snapshot.failureFor("superset")).isEqualTo(GatewayErrorCode.INVALID_BACKEND_CATALOG);
    }

    @Test
    void aBackendWhoseToolsWereAllSkippedStillGetsARoute() {
        // The client drops individual invalid definitions, so a backend can answer with zero usable
        // tools. That is a successful discovery — the backend is reachable and simply contributes
        // nothing — so it must still hold a route rather than look unavailable.
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset");

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools())).containsExactly("blss__query_asset");
        assertThat(snapshot.routes()).containsKey("superset");
        assertThat(snapshot.entries().get("superset").tools()).isEmpty();
        assertThat(snapshot.failures()).doesNotContainKey("superset");
    }

    // --- 7.17 lazy loading, per-request token, refresh ----------------------------------------

    @Test
    void nothingIsDiscoveredUntilTheCatalogIsRequested() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));

        ToolCatalogService service = service(blssOnly(), client);

        assertThat(service.current()).isNull();
        assertThat(client.discoveries).hasValue(0);
    }

    @Test
    void discoveryUsesTheTriggeringRequestsToken() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));

        service(blssOnly(), client).obtain("user-a-token");

        assertThat(client.discoveryTokens).containsExactly("user-a-token");
    }

    @Test
    void aFreshCatalogIsServedFromCacheWithoutContactingBackends() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);

        service.obtain("token-a");
        clock.advance(Duration.ofMinutes(9));
        service.obtain("token-b");

        assertThat(client.discoveries).hasValue(1);
    }

    @Test
    void expiryAloneDoesNotContactAnyBackend() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);
        service.obtain("token-a");

        clock.advance(Duration.ofHours(3));

        // No request arrived, so no background refresh may have happened.
        assertThat(client.discoveries).hasValue(1);
    }

    @Test
    void aStaleCatalogIsRefreshedByTheNextRequestUsingThatRequestsToken() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);
        service.obtain("token-a");

        clock.advance(TTL.plusSeconds(1));
        client.withCatalog("blss", tool("query_asset"), tool("list_alarms"));
        ToolCatalogSnapshot refreshed = service.obtain("token-b");

        assertThat(client.discoveryTokens).containsExactly("token-a", "token-b");
        assertThat(toolNames(refreshed.tools())).containsExactly("blss__query_asset", "blss__list_alarms");
    }

    @Test
    void removedDownstreamToolsDisappearAfterRefresh() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"), tool("list_alarms"));
        ToolCatalogService service = service(blssOnly(), client);
        service.obtain("token-a");

        clock.advance(TTL.plusSeconds(1));
        client.withCatalog("blss", tool("query_asset"));

        assertThat(toolNames(service.obtain("token-b").tools())).containsExactly("blss__query_asset");
    }

    @Test
    void differentUsersObserveTheSameIdentityIndependentCatalog() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);

        ToolCatalogSnapshot forUserA = service.obtain("token-a");
        clock.advance(TTL.plusSeconds(1));
        ToolCatalogSnapshot forUserB = service.obtain("token-b");

        assertThat(toolNames(forUserA.tools())).isEqualTo(toolNames(forUserB.tools()));
        // No identity is retained anywhere in the published snapshot.
        assertThat(forUserB.toString()).doesNotContain("token-a").doesNotContain("token-b");
    }

    // --- 7.18 concurrency and graceful degradation --------------------------------------------

    @Test
    void concurrentFirstRequestsShareASingleDiscovery() throws Exception {
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .beforeDiscovery(() -> {
                    try {
                        // Hold the winner inside discovery so the others pile up behind it.
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
        ToolCatalogService service = service(blssOnly(), client);
        List<ToolCatalogSnapshot> observed = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch done = new CountDownLatch(callers);
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        observed.add(service.obtain("token"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(client.discoveries).hasValue(1);
        assertThat(observed).hasSize(callers);
        // Every waiting caller observed the very same completed snapshot.
        assertThat(observed).allSatisfy(snapshot -> assertThat(snapshot).isSameAs(observed.get(0)));
    }

    @Test
    void toolsAndRoutesArePublishedAsOneConsistentSnapshot() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        for (JsonNode exposed : snapshot.tools()) {
            String prefix = exposed.get("name").asString().split("__")[0];
            assertThat(snapshot.routes()).containsKey(prefix);
        }
        assertThat(snapshot.routes().keySet()).isEqualTo(snapshot.entries().keySet());
    }

    @Test
    void oneDownBackendStillYieldsAPartialInitialCatalog() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withDiscoveryFailure("superset", new BackendUnavailableException("connection refused"));

        ToolCatalogSnapshot snapshot = service(twoBackends(), client).obtain("token");

        assertThat(toolNames(snapshot.tools())).containsExactly("blss__query_asset");
        assertThat(snapshot.entries()).doesNotContainKey("superset");
    }

    @Test
    void allBackendsFailingInitiallyLeavesTheCatalogUninitialized() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withDiscoveryFailure("blss", new BackendUnavailableException("down"))
                .withDiscoveryFailure("superset", new BackendUnavailableException("down"));
        ToolCatalogService service = service(twoBackends(), client);

        assertThatThrownBy(() -> service.obtain("token"))
                .isInstanceOf(GatewayMcpException.class)
                .extracting(e -> ((GatewayMcpException) e).errorCode())
                .isEqualTo(GatewayErrorCode.CATALOG_UNAVAILABLE);
        assertThat(service.current()).isNull();
    }

    @Test
    void totalInitialFailureIsNotRetriedBeforeTheBackoffExpiresAndSucceedsAfter() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withDiscoveryFailure("blss", new BackendUnavailableException("down"));
        ToolCatalogService service = service(blssOnly(), client);
        assertThatThrownBy(() -> service.obtain("token")).isInstanceOf(GatewayMcpException.class);
        assertThat(client.discoveries).hasValue(1);

        clock.advance(Duration.ofSeconds(10));
        assertThatThrownBy(() -> service.obtain("token")).isInstanceOf(GatewayMcpException.class);
        assertThat(client.discoveries).as("no retry during backoff").hasValue(1);

        clock.advance(BACKOFF);
        client.withCatalog("blss", tool("query_asset"));
        assertThat(toolNames(service.obtain("token").tools())).containsExactly("blss__query_asset");
    }

    @Test
    void refreshFailureRetainsLastKnownGoodToolsWhileOtherBackendsUpdate() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"))
                .withCatalog("superset", tool("run_sql"));
        ToolCatalogService service = service(twoBackends(), client);
        service.obtain("token-a");

        clock.advance(TTL.plusSeconds(1));
        client.withCatalog("blss", tool("query_asset"), tool("list_alarms"));
        client.withDiscoveryFailure("superset", new BackendUnavailableException("down"));
        ToolCatalogSnapshot refreshed = service.obtain("token-b");

        assertThat(toolNames(refreshed.tools()))
                .containsExactly("blss__query_asset", "blss__list_alarms", "superset__run_sql");
        assertThat(refreshed.routes()).containsKey("superset");
    }

    @Test
    void totalRefreshFailureServesTheLastKnownGoodSnapshotWithoutAdvancingItsTimestamp() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);
        ToolCatalogSnapshot original = service.obtain("token-a");

        clock.advance(TTL.plusSeconds(1));
        client.withDiscoveryFailure("blss", new BackendUnavailableException("down"));
        ToolCatalogSnapshot served = service.obtain("token-b");

        assertThat(served).isSameAs(original);
        assertThat(served.refreshedAt()).isEqualTo(original.refreshedAt());

        // No further refresh attempt before the backoff expires.
        int afterFailure = client.discoveries.get();
        clock.advance(Duration.ofSeconds(5));
        service.obtain("token-c");
        assertThat(client.discoveries).hasValue(afterFailure);
    }

    @Test
    void aRecoveredBackendReplacesItsStaleEntries() {
        RecordingBackendMcpClient client = new RecordingBackendMcpClient()
                .withCatalog("blss", tool("query_asset"));
        ToolCatalogService service = service(blssOnly(), client);
        service.obtain("token-a");

        clock.advance(TTL.plusSeconds(1));
        client.withDiscoveryFailure("blss", new BackendUnavailableException("down"));
        service.obtain("token-b");

        clock.advance(BACKOFF.plusSeconds(1));
        client.withCatalog("blss", tool("query_asset_v2"));
        ToolCatalogSnapshot recovered = service.obtain("token-c");

        assertThat(toolNames(recovered.tools())).containsExactly("blss__query_asset_v2");
    }

    private static BackendRegistry blssOnly() {
        return GatewayTestSupport.registry("blss", "http://blss.local/mcp");
    }
}
