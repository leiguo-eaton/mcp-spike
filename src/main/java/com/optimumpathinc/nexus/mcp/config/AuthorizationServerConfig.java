package com.optimumpathinc.nexus.mcp.config;

import com.optimumpathinc.nexus.mcp.security.UserTokenContext;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationValidator;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Leg A (OAuth 2.1) — Authorization Server half of the sidecar.
 *
 * <p>The sidecar acts as BOTH the Authorization Server and the Resource Server for the MCP
 * endpoint. This chain (highest precedence) owns the OAuth/OIDC protocol endpoints:
 * <ul>
 *   <li>{@code /oauth2/authorize}, {@code /oauth2/token}, {@code /oauth2/jwks}</li>
 *   <li>{@code /.well-known/oauth-authorization-server} (RFC 8414 metadata)</li>
 *   <li>{@code /connect/register} (OIDC Dynamic Client Registration, RFC 7591)</li>
 * </ul>
 *
 * <p>A single public client ({@code mcp-vscode}) is pre-registered as a reliable fallback for
 * MCP clients that cannot use dynamic registration. It is a PUBLIC client (no secret) and
 * therefore requires PKCE.
 */
@Configuration
public class AuthorizationServerConfig {

    /**
     * Access-token lifetime. Public (PKCE) MCP clients receive no refresh token from Spring
     * Authorization Server, so the token must live long enough to cover a work session; otherwise
     * VS Code is forced to re-run the OAuth flow when it expires (the recurring "Allow" popup).
     */
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(12);

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.with(authorizationServer, as -> as
                // Enable OIDC (provider metadata + userinfo).
                .oidc(Customizer.withDefaults())
                // RFC 7591 Dynamic Client Registration with OPEN (anonymous) registration so MCP
                // clients such as VS Code can self-register a public PKCE client on first connect.
                // The endpoint is advertised as `registration_endpoint` in the RFC 8414 metadata.
                .clientRegistrationEndpoint(reg -> reg
                        .openRegistrationAllowed(true)
                        // The DEFAULT validator forbids `scope` during open registration (an
                        // anti-privilege-escalation default). MCP clients must be able to register
                        // with `mcp.read`/`mcp.invoke` so they can later request them, so swap in a
                        // validator that keeps the strict redirect_uri/jwks checks but permits scopes.
                        .authenticationProviders(providers -> providers.forEach(provider -> {
                            if (provider instanceof OAuth2ClientRegistrationAuthenticationProvider registrationProvider) {
                                registrationProvider.setAuthenticationValidator(
                                        OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR
                                                .andThen(OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_JWK_SET_URI_VALIDATOR)
                                                .andThen(OAuth2ClientRegistrationAuthenticationValidator.SIMPLE_SCOPE_VALIDATOR));
                            }
                        }))));
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .authorizeHttpRequests(auth -> auth
                        // Open (anonymous) Dynamic Client Registration: allow the POST to reach the
                        // registration endpoint filter. The GET client-configuration endpoint stays
                        // protected (requires a registration access token).
                        .requestMatchers(HttpMethod.POST, "/connect/register").permitAll()
                        .anyRequest().authenticated())
                // Redirect unauthenticated browser requests (the /authorize step) to the login page.
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // Bearer auth for the DCR endpoint (uses tokens minted by this same server).
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @DependsOnDatabaseInitialization
    RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate, SidecarProperties props) {
        // Normalize every stored client (see normalizeClient): (1) disable interactive consent so
        // /oauth2/authorize redirects straight back to the MCP client's loopback with the code, and
        // (2) apply a long access-token lifetime. Spring Authorization Server does NOT issue refresh
        // tokens to PUBLIC (PKCE, no-secret) clients, so with the default 5-minute access token VS
        // Code cannot silently refresh and is forced to re-run the OAuth flow every few minutes — the
        // recurring "Allow" popup. A long-lived access token avoids that on a localhost POC.
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcTemplate) {
            @Override
            public void save(RegisteredClient registeredClient) {
                super.save(normalizeClient(registeredClient));
            }
        };
        // Re-apply the normalization to clients persisted before this fix (e.g. the client_id VS Code
        // already cached) so their consent flag and token lifetime are corrected without a DB wipe or
        // a forced client re-registration.
        for (String existingId : jdbcTemplate.queryForList(
                "SELECT client_id FROM oauth2_registered_client", String.class)) {
            RegisteredClient existing = repository.findByClientId(existingId);
            if (existing != null) {
                repository.save(existing);
            }
        }
        // Seed a static public client as a manual-testing fallback. Dynamically-registered MCP
        // clients (VS Code) are persisted into the SAME table, so they survive restarts and the
        // client_id VS Code cached stays valid (fixes the endless re-authentication loop).
        seedPublicClient(repository, "mcp-vscode");
        // Pre-seed any client_ids that an MCP client already cached from an earlier (in-memory, now
        // lost) registration. Without this, VS Code keeps calling /oauth2/authorize with a client_id
        // the server no longer knows -> 400 loop, and VS Code will not re-register on its own.
        // Configure via sidecar.seed-public-client-ids; safe to remove once the client re-registers.
        for (String clientId : props.getSeedPublicClientIds()) {
            if (!clientId.isBlank()) {
                seedPublicClient(repository, clientId.trim());
            }
        }
        return repository;
    }

    /**
     * Returns a copy of the client with authorization consent disabled and a long access-token
     * lifetime applied. Public (PKCE) clients get no refresh token from Spring Authorization Server,
     * so a short access token would force VS Code to re-authenticate (the popup loop); a long TTL
     * keeps the MCP session alive for the duration of a work session on this localhost POC.
     */
    private static RegisteredClient normalizeClient(RegisteredClient client) {
        return RegisteredClient.from(client)
                .clientSettings(ClientSettings.withSettings(client.getClientSettings().getSettings())
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.withSettings(client.getTokenSettings().getSettings())
                        .accessTokenTimeToLive(ACCESS_TOKEN_TTL)
                        .build())
                .build();
    }

    /** Registers a public (no-secret, PKCE) client with the MCP scopes if it does not already exist. */
    private static void seedPublicClient(RegisteredClientRepository repository, String clientId) {
        if (repository.findByClientId(clientId) != null) {
            return;
        }
        RegisteredClient publicClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                // Public client: no secret, PKCE required (OAuth 2.1 for native/desktop clients).
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                // VS Code MCP OAuth loopback / redirect targets.
                .redirectUri("http://127.0.0.1:33418/")
                .redirectUri("https://vscode.dev/redirect")
                .redirectUri("http://localhost:8090/login/oauth2/code/mcp-vscode")
                .scope(OidcScopes.OPENID)
                .scope("mcp.read")
                .scope("mcp.invoke")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        repository.save(publicClient);
    }

    /** Persists issued authorizations (auth codes, access/refresh tokens) so refresh survives restart. */
    @Bean
    OAuth2AuthorizationService authorizationService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    /** Persists user consent decisions. */
    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate jdbcTemplate, RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * RSA signing key for issued JWTs. Persisted to {@code ./data/jwk.json} so tokens remain valid
     * across restarts (a fresh-per-boot key would invalidate every cached access token). POC only:
     * the private key is stored unencrypted on disk — acceptable for a localhost sidecar.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = loadOrCreateRsaKey();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    private static RSAKey loadOrCreateRsaKey() {
        Path keyFile = Path.of("data", "jwk.json");
        try {
            if (Files.exists(keyFile)) {
                return RSAKey.parse(Files.readString(keyFile));
            }
            KeyPair keyPair = generateRsaKey();
            RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            Files.createDirectories(keyFile.getParent());
            Files.writeString(keyFile, rsaKey.toJSONString());
            return rsaKey;
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load or create RSA signing key", ex);
        }
    }

    /** Shared decoder — used both internally by the AS and by the /mcp resource-server chain. */
    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Binds the access-token {@code aud} claim to the RFC 8707 {@code resource} the client asked for.
     *
     * <p>MCP clients (VS Code) send {@code resource=<issuer>/mcp} on the authorize/token requests and,
     * per the MCP authorization spec, REQUIRE the issued access token to be audience-bound to that
     * resource. Spring Authorization Server defaults {@code aud} to the client_id, so without this the
     * client rejects every token and re-authorizes endlessly (the popup loop). We echo the requested
     * resource(s) as the audience, falling back to {@code <issuer>/mcp} when none was supplied.
     */
    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer(SidecarProperties props) {
        String defaultAudience = props.getIssuerUri() + "/mcp";
        String blssToken = props.getBlssUserToken();
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            Set<String> audiences = new LinkedHashSet<>();
            collectResourcesFromAuthorization(context.getAuthorization(), audiences);
            collectResourcesFromGrant(context.getAuthorizationGrant(), audiences);
            if (audiences.isEmpty()) {
                audiences.add(defaultAudience);
            }
            context.getClaims().audience(List.copyOf(audiences));
            // Leg B (on-behalf-of): carry the BLSS delegation token in the access token so the
            // resource-server side (UserTokenCaptureFilter) can forward it to master REST. Sourced
            // from config for the POC; swap for a per-user value here without touching MasterClient.
            if (blssToken != null && !blssToken.isBlank()) {
                context.getClaims().claim(UserTokenContext.BLSS_TOKEN_CLAIM, blssToken);
            }
        };
    }

    /** Pulls RFC 8707 {@code resource} values from the stored authorize request. */
    private static void collectResourcesFromAuthorization(OAuth2Authorization authorization, Set<String> audiences) {
        if (authorization == null) {
            return;
        }
        OAuth2AuthorizationRequest authRequest =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (authRequest != null) {
            addResourceValue(authRequest.getAdditionalParameters().get("resource"), audiences);
        }
    }

    /** Pulls RFC 8707 {@code resource} values from the current token request. */
    private static void collectResourcesFromGrant(Authentication grant, Set<String> audiences) {
        if (grant instanceof OAuth2AuthorizationGrantAuthenticationToken grantToken) {
            addResourceValue(grantToken.getAdditionalParameters().get("resource"), audiences);
        }
    }

    private static void addResourceValue(Object resource, Set<String> audiences) {
        if (resource instanceof String s) {
            if (!s.isBlank()) {
                audiences.add(s);
            }
        } else if (resource instanceof String[] arr) {
            for (String s : arr) {
                if (s != null && !s.isBlank()) {
                    audiences.add(s);
                }
            }
        } else if (resource instanceof Collection<?> col) {
            for (Object o : col) {
                if (o != null && !o.toString().isBlank()) {
                    audiences.add(o.toString());
                }
            }
        }
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(SidecarProperties props) {
        return AuthorizationServerSettings.builder()
                .issuer(props.getIssuerUri())
                .clientRegistrationEndpoint("/connect/register")
                .build();
    }

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to generate RSA key for OAuth signing", ex);
        }
    }
}
