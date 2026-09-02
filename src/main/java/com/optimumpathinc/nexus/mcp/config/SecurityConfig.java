package com.optimumpathinc.nexus.mcp.config;

import com.optimumpathinc.nexus.mcp.security.AudienceValidator;
import com.optimumpathinc.nexus.mcp.security.McpAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Pure MCP Gateway / Resource Server configuration.
 *
 * <p>The sidecar no longer runs an Authorization Server. A single stateless filter chain protects
 * {@code /mcp}: it validates {@code MCP_ACCESS_TOKEN}s issued by an EXTERNAL Authorization Server
 * (master). The validated token is read per request from the {@code SecurityContext} and forwarded
 * On-Behalf-Of to the routed backend MCP server; nothing is captured into shared state.
 */
@Configuration
public class SecurityConfig {

    /**
     * Single resource-server chain. Validates the external Bearer JWT and permits the
     * discovery/metadata + health endpoints. No login, registration, or consent endpoints exist.
     */
    @Bean
    SecurityFilterChain resourceServerFilterChain(
            HttpSecurity http, SidecarProperties props, JwtDecoder jwtDecoder) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/.well-known/**",
                                "/error")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(new McpAuthenticationEntryPoint(props))
                        // RFC 9728 protected-resource metadata. Spring Security serves
                        // /.well-known/oauth-protected-resource/** itself, so the AS is advertised
                        // here rather than from a controller (a controller would be shadowed).
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(props.getMcpResource())
                                        .authorizationServer(props.getIssuerUri())
                                        // v1 neither requests nor enforces OAuth scopes, and tokens
                                        // are not mTLS-bound: advertise neither.
                                        .tlsClientCertificateBoundAccessTokens(false)))
                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }

    /**
     * Decoder for access tokens minted by the EXTERNAL Authorization Server (master). Signing keys
     * come from the AS {@code jwks_uri} (explicit, or discovered from {@code issuer-uri}). Validates
     * the signature plus {@code iss}, {@code exp}/{@code nbf} (configurable clock skew), and
     * {@code aud} (must contain the configured MCP resource).
     */
    @Bean
    JwtDecoder jwtDecoder(SidecarProperties props) {
        NimbusJwtDecoder decoder;
        String jwksUri = props.getJwksUri();
        if (jwksUri != null && !jwksUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        } else {
            decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(props.getIssuerUri());
        }
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(props.getClockSkew()),
                JwtValidators.createDefaultWithIssuer(props.getIssuerUri()),
                new AudienceValidator(props.getMcpResource()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
