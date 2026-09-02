package com.optimumpathinc.nexus.mcp.gateway;

/** A backend MCP server could not be reached or did not answer a request successfully. */
public class BackendUnavailableException extends RuntimeException {

    public BackendUnavailableException(String message) {
        super(message);
    }

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
