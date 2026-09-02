package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One backend's last-known-good tool definitions.
 *
 * <p>Definitions are stored exactly as the backend returned them (native, unprefixed names); the
 * external {@code <prefix>__<name>} form is derived when the snapshot is published (D14). No user
 * identity or token is stored here — the catalog is global and identity-independent (D10).
 *
 * @param prefix      the owning backend's namespace prefix
 * @param tools       backend-native tool definitions
 * @param nativeNames the native tool names, for fast {@code unknown_tool} detection
 */
public record BackendCatalogEntry(String prefix, List<JsonNode> tools, Set<String> nativeNames) {

    public static BackendCatalogEntry of(String prefix, List<JsonNode> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asString());
        }
        return new BackendCatalogEntry(prefix, List.copyOf(tools), Set.copyOf(names));
    }
}
