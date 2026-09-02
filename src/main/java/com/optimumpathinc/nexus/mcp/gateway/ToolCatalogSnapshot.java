package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, atomically published view of the aggregated tool catalog and its routing table
 * (D9/D10).
 *
 * <p>Because {@code tools} and {@code routes} are built together and handed out as one object, a
 * caller can never observe a tool without its route, or a route from a different generation.
 *
 * @param entries        per-backend last-known-good definitions, keyed by prefix
 * @param failures       stable failure category for backends with no usable entry, keyed by prefix
 * @param tools          the unified, prefixed tool definitions served by {@code tools/list}
 * @param routes         prefix to backend routing table for {@code tools/call}
 * @param refreshedAt    timestamp of the last discovery/refresh in which at least one backend
 *                       succeeded; a fully failed refresh does not advance it
 */
public record ToolCatalogSnapshot(
        Map<String, BackendCatalogEntry> entries,
        Map<String, GatewayErrorCode> failures,
        List<JsonNode> tools,
        Map<String, BackendDefinition> routes,
        Instant refreshedAt) {

    /**
     * Builds the published snapshot. Iterating the registry keeps the exposed order stable and
     * independent of which backend answered first.
     */
    public static ToolCatalogSnapshot build(BackendRegistry registry,
            Map<String, BackendCatalogEntry> entries,
            Map<String, GatewayErrorCode> failures,
            Instant refreshedAt) {
        List<JsonNode> tools = new ArrayList<>();
        Map<String, BackendDefinition> routes = new LinkedHashMap<>();
        Map<String, BackendCatalogEntry> orderedEntries = new LinkedHashMap<>();
        for (BackendDefinition backend : registry.backends()) {
            BackendCatalogEntry entry = entries.get(backend.prefix());
            if (entry == null) {
                continue;
            }
            orderedEntries.put(backend.prefix(), entry);
            routes.put(backend.prefix(), backend);
            for (JsonNode tool : entry.tools()) {
                tools.add(prefixed(backend, tool));
            }
        }
        return new ToolCatalogSnapshot(
                Map.copyOf(orderedEntries),
                Map.copyOf(failures),
                List.copyOf(tools),
                Map.copyOf(routes),
                refreshedAt);
    }

    /**
     * Returns a copy of the backend's tool definition with only its externally visible {@code name}
     * rewritten to {@code <prefix>__<name>}. Every other field — description, title, input/output
     * schemas, annotations, extension fields — is preserved verbatim (D14).
     */
    private static JsonNode prefixed(BackendDefinition backend, JsonNode tool) {
        ObjectNode copy = (ObjectNode) tool.deepCopy();
        copy.put("name", backend.externalToolName(tool.get("name").asString()));
        return copy;
    }

    /** The failure category recorded for a configured backend that has no usable catalog entry. */
    public GatewayErrorCode failureFor(String prefix) {
        return failures.getOrDefault(prefix, GatewayErrorCode.BACKEND_UNAVAILABLE);
    }
}
