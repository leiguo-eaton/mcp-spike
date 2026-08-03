package com.optimumpathinc.nexus.mcp.security;

/**
 * Holds the end-user delegation token (on-behalf-of) for the current request so that
 * MCP tool methods can forward it to master REST (Leg B pass-through).
 *
 * <p>POC caveat: this relies on the MCP WebMVC transport executing the tool call on the
 * same servlet request thread that the {@link ApiKeyAuthFilter} runs on. If a future
 * transport dispatches tool calls to a different thread pool, replace this with the
 * MCP tool-context / exchange mechanism instead.
 */
public final class UserTokenContext {

    /**
     * JWT claim carrying the BLSS delegation token (Leg B). The Authorization Server writes it into
     * the issued access token; {@link UserTokenCaptureFilter} reads it back for {@code MasterClient}.
     */
    public static final String BLSS_TOKEN_CLAIM = "blss_token";

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    private UserTokenContext() {
    }

    public static void set(String token) {
        TOKEN.set(token);
    }

    public static String get() {
        return TOKEN.get();
    }

    public static void clear() {
        TOKEN.remove();
    }
}
