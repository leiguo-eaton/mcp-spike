package com.optimumpathinc.nexus.mcp.gateway;

/**
 * A backend's {@code tools/list} response is structurally unusable — there is no readable
 * {@code tools} array — so the whole discovery attempt for that backend fails as a unit and D11
 * degradation applies.
 *
 * <p>An <em>individual</em> malformed tool definition does NOT raise this: per D14 it is skipped and
 * logged while the backend's remaining valid tools are still published.
 */
public class InvalidBackendCatalogException extends RuntimeException {

    public InvalidBackendCatalogException(String message) {
        super(message);
    }
}
