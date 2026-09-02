package com.optimumpathinc.nexus.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    private static final String RESOURCE = "http://localhost:8090/mcp";

    private final AudienceValidator validator = new AudienceValidator(RESOURCE);

    @Test
    void acceptsTokenWhoseAudienceContainsTheResource() {
        Jwt jwt = jwtWithAudience(List.of("something-else", RESOURCE));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutTheRequiredAudience() {
        Jwt jwt = jwtWithAudience(List.of("https://other.resource"));

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithNoAudience() {
        Jwt jwt = jwtWithAudience(List.of());

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
    }

    private static Jwt jwtWithAudience(List<String> audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("aud", audience)
                .claims(claims -> claims.put("sub", "peter"))
                .build();
    }
}
