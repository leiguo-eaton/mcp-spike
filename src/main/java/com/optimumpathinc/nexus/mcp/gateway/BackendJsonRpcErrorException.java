package com.optimumpathinc.nexus.mcp.gateway;

import tools.jackson.databind.JsonNode;

/**
 * The backend answered with a well-formed JSON-RPC error (for example a business authorization
 * failure during {@code tools/call}). The gateway relays it to the Agent unchanged instead of
 * replacing it with a gateway category, and never retries as a different identity.
 */
public class BackendJsonRpcErrorException extends RuntimeException {

    private final transient JsonNode error;

    public BackendJsonRpcErrorException(JsonNode error) {
        super("Backend MCP server returned a JSON-RPC error");
        this.error = error;
    }

    /** The raw JSON-RPC {@code error} object returned by the backend. */
    public JsonNode error() {
        return error;
    }
}
