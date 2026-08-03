package com.optimumpathinc.nexus.mcp.config;

import com.optimumpathinc.nexus.mcp.security.McpAuthenticationEntryPoint;
import com.optimumpathinc.nexus.mcp.security.UserTokenCaptureFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
public class SecurityConfig {

    /**
     * Leg A (OAuth 2.1) — Resource Server chain for the MCP endpoint. Validates the Bearer JWT
     * minted by the sidecar's own Authorization Server and, once authenticated, captures the
     * Leg B user-delegation token for downstream master REST calls.
     */
    @Bean
    @Order(2)
    SecurityFilterChain mcpResourceServerFilterChain(
            HttpSecurity http, SidecarProperties props, JwtDecoder jwtDecoder) throws Exception {
        http
                .securityMatcher("/mcp", "/mcp/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .authenticationEntryPoint(new McpAuthenticationEntryPoint(props))
                        .jwt(jwt -> jwt.decoder(jwtDecoder)))
                // Capture X-BLSS-User-Token (Leg B) after the bearer token has been validated.
                .addFilterAfter(new UserTokenCaptureFilter(props), BasicAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Default chain: serves the interactive login page (used by the OAuth authorization step),
     * the protected-resource metadata, and health. Everything else requires authentication.
     */
    @Bean
    @Order(3)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/.well-known/**",
                                "/login",
                                "/error")
                        .permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    /** Demo resource owner for the interactive OAuth login (POC only). */
    @Bean
    UserDetailsService userDetailsService(SidecarProperties props, PasswordEncoder passwordEncoder) {
        UserDetails user = User.withUsername(props.getAuthUsername())
                .password(passwordEncoder.encode(props.getAuthPassword()))
                .roles("MCP_USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
