package com.optimumpathinc.nexus.mcp.security;

import com.optimumpathinc.nexus.mcp.config.SidecarProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Leg B (on-behalf-of) capture. Resolves the end-user delegation token and stashes it in
 * {@link UserTokenContext} so {@code MasterClient} can forward it to master REST for the duration of
 * the request. The token is taken from the authenticated access token's {@code blss_token} claim
 * (the primary source), falling back to the inbound {@link SidecarProperties#getUserTokenHeader()}
 * header for manual/testing callers.
 *
 * <p>Leg A (client authentication) is handled earlier by the OAuth 2.1 resource-server (JWT bearer);
 * this filter performs no authentication of its own.
 */
public class UserTokenCaptureFilter extends OncePerRequestFilter {

    private final SidecarProperties props;

    public UserTokenCaptureFilter(SidecarProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userToken = tokenFromJwtClaim();
        if (userToken == null || userToken.isBlank()) {
            userToken = request.getHeader(props.getUserTokenHeader());
        }
        try {
            UserTokenContext.set(userToken);
            chain.doFilter(request, response);
        } finally {
            UserTokenContext.clear();
        }
    }

    /** Reads the {@code blss_token} claim from the authenticated access token, if present. */
    private static String tokenFromJwtClaim() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getClaimAsString(UserTokenContext.BLSS_TOKEN_CLAIM);
        }
        return null;
    }
}
