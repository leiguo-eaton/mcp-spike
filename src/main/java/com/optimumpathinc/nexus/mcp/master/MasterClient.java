package com.optimumpathinc.nexus.mcp.master;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import com.optimumpathinc.nexus.mcp.security.UserTokenContext;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin client for BLSS master REST API.
 *
 * <p>Leg B (on-behalf-of): forwards the end-user delegation token captured by the
 * inbound MCP request so master rebuilds the real user's {@code UserContext} and applies
 * existing RBAC / device-scope. The sidecar itself holds no service account.
 */
@Component
public class MasterClient {

    private static final Logger log = LoggerFactory.getLogger(MasterClient.class);

    private final RestClient restClient;
    private final SidecarProperties props;

    public MasterClient(SidecarProperties props) {
        this.props = props;
        // Bound outbound calls to master so a slow/hung backend fails fast instead of stalling the
        // MCP tool call (which otherwise races with the client tearing down the SSE session).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getMasterConnectTimeout());
        factory.setReadTimeout(props.getMasterReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.getMasterBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * GET a master REST resource, propagating the delegated user token.
     *
     * @param path resource path relative to master base URL, e.g. {@code /rest/hierarchy}
     * @return response body as String (raw JSON) for the POC
     */
    public String get(String path) {
        return get(path, Map.of());
    }

    /**
     * GET a master REST resource with query parameters, propagating the delegated user token.
     *
     * <p>Query parameter values are URL-encoded by the client, so callers pass raw values
     * (e.g. an unencoded Base64 string).
     *
     * @param path        resource path relative to master base URL, e.g. {@code /search/assets}
     * @param queryParams query parameters to append to the request
     * @return response body as String (raw JSON) for the POC
     */
    public String get(String path, Map<String, String> queryParams) {
        String userToken = UserTokenContext.get();
        boolean hasToken = userToken != null && !userToken.isBlank();

        String fullUrl = UriComponentsBuilder.fromUriString(props.getMasterBaseUrl())
                .path(path)
                .build()
                .toUriString()
                + (queryParams.isEmpty() ? "" : "?" + toQueryString(queryParams));

        log.info("Master GET {} | queryParams={} | masterAuthHeader={} | hasUserToken={} | tokenPreview={}",
                fullUrl, queryParams, props.getMasterAuthHeader(), hasToken, maskToken(userToken));

        try {
            String body = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        queryParams.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .headers(h -> {
                        if (hasToken) {
                            h.set(props.getMasterAuthHeader(), String.format("Basic %s", userToken));
                        }
                    })
                    .retrieve()
                    .body(String.class);
            log.info("Master GET {} -> OK ({} bytes)", path, body == null ? 0 : body.length());
            return body;
        } catch (RuntimeException e) {
            log.warn("Master GET {} -> FAILED: {}", path, e.getMessage());
            throw e;
        }
    }

    private static String toQueryString(Map<String, String> queryParams) {
        return queryParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    /** Masks the delegation token so logs never contain the full secret. */
    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "<none>";
        }
        int len = token.length();
        String prefix = token.substring(0, Math.min(6, len));
        return prefix + "…(len=" + len + ")";
    }
}
