package com.optimumpathinc.nexus.mcp.security;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Leg A — service trust. Validates that the caller (Nexus engine) presents the shared
 * API key. Also captures the end-user delegation token (Leg B) into {@link UserTokenContext}
 * for downstream master REST calls.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final SidecarProperties props;

    public ApiKeyAuthFilter(SidecarProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(props.getApiKeyHeader());
        log.debug("ApiKeyAuthFilter invoked for {} keyPresent={}", request.getRequestURI(), presented != null);
        if (!constantTimeEquals(props.getApiKey(), presented)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key");
            return;
        }

        var auth = new UsernamePasswordAuthenticationToken(
                "nexus-engine", null, AuthorityUtils.createAuthorityList("ROLE_NEXUS"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        String userToken = request.getHeader(props.getUserTokenHeader());
        try {
            UserTokenContext.set(userToken);
            chain.doFilter(request, response);
        } finally {
            UserTokenContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
