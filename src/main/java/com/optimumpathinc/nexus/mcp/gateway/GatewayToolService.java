package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The gateway's tool surface: serves the aggregated {@code tools/list} and routes {@code tools/call}
 * to the owning backend by namespace prefix (D9).
 *
 * <p>Error precedence (D15): a prefix that is absent from the static registry is rejected as
 * {@code unknown_prefix} <em>before</em> any discovery happens. Only afterwards can
 * {@code catalog_unavailable}, {@code backend_unavailable} / {@code invalid_backend_catalog}, and
 * finally {@code unknown_tool} apply.
 */
@Service
public class GatewayToolService {

    private final BackendRegistry registry;
    private final ToolCatalogService catalog;
    private final BackendMcpClient client;

    public GatewayToolService(BackendRegistry registry, ToolCatalogService catalog, BackendMcpClient client) {
        this.registry = registry;
        this.catalog = catalog;
        this.client = client;
    }

    /** The unified, prefixed catalog. Lazily discovered with the caller's validated token. */
    public List<JsonNode> listTools(String accessToken) {
        return catalog.obtain(accessToken).tools();
    }

    /**
     * Routes a prefixed tool call to its backend and returns the backend result unchanged.
     *
     * @param prefixedName the externally visible name, e.g. {@code blss__query_asset}
     * @param arguments    the tool arguments, forwarded unchanged
     */
    public JsonNode callTool(String accessToken, String prefixedName, JsonNode arguments) {
        int separator = prefixedName == null ? -1 : prefixedName.indexOf(BackendDefinition.SEPARATOR);
        if (separator <= 0) {
            throw new GatewayMcpException(GatewayErrorCode.UNKNOWN_PREFIX);
        }
        String prefix = prefixedName.substring(0, separator);
        String nativeToolName = prefixedName.substring(separator + BackendDefinition.SEPARATOR.length());

        // Rejected before discovery: an unconfigured prefix can never be routed.
        BackendDefinition backend = registry.byPrefix(prefix);
        if (backend == null) {
            throw new GatewayMcpException(GatewayErrorCode.UNKNOWN_PREFIX);
        }
        if (nativeToolName.isBlank()) {
            throw new GatewayMcpException(GatewayErrorCode.UNKNOWN_TOOL);
        }

        ToolCatalogSnapshot snapshot = catalog.obtain(accessToken);
        BackendCatalogEntry entry = snapshot.entries().get(prefix);
        if (entry == null) {
            throw new GatewayMcpException(snapshot.failureFor(prefix));
        }
        if (!entry.nativeNames().contains(nativeToolName)) {
            throw new GatewayMcpException(GatewayErrorCode.UNKNOWN_TOOL);
        }

        try {
            // On-Behalf-Of: the same validated access token is forwarded to the owning backend.
            return client.callTool(backend, accessToken, nativeToolName, arguments);
        } catch (BackendJsonRpcErrorException e) {
            throw e;
        } catch (RuntimeException e) {
            // One unavailable backend fails only this call.
            throw new GatewayMcpException(GatewayErrorCode.BACKEND_UNAVAILABLE);
        }
    }
}
