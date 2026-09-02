package com.optimumpathinc.nexus.mcp.gateway;

import java.net.URI;

/**
 * A validated backend MCP server in the static registry (D13).
 *
 * @param prefix  external namespace prefix; joined to the backend-native tool name with {@code __}
 * @param baseUrl absolute URL of the backend's Streamable HTTP MCP endpoint
 */
public record BackendDefinition(String prefix, URI baseUrl) {

    /** Separator between the backend prefix and the backend-native tool name (D9). */
    public static final String SEPARATOR = "__";

    /** Externally visible name of a backend-native tool, e.g. {@code blss__query_asset}. */
    public String externalToolName(String nativeToolName) {
        return prefix + SEPARATOR + nativeToolName;
    }
}
