## ADDED Requirements

### Requirement: Challenge unauthenticated MCP requests with external AS discovery
The `/mcp` Resource Server SHALL reject requests that lack a valid access token with `401 Unauthorized` and a `WWW-Authenticate: Bearer` header containing a `resource_metadata` pointer (RFC 9728). The protected-resource metadata SHALL identify the MCP `resource` and list the **external** Authorization Server (master) under `authorization_servers`, so an MCP client discovers master for token exchange.

#### Scenario: Unauthenticated request receives a challenge
- **WHEN** a client calls `POST /mcp` without an `Authorization` header
- **THEN** the gateway responds `401` with `WWW-Authenticate: Bearer resource_metadata="<gateway-origin>/.well-known/oauth-protected-resource"`

#### Scenario: Metadata points to the external Authorization Server
- **WHEN** a client GETs `/.well-known/oauth-protected-resource`
- **THEN** the response `authorization_servers` contains the external AS (master) URL
- **AND** the response `resource` equals the configured MCP resource identifier
- **AND** the response does not advertise OAuth scopes in v1

### Requirement: Validate externally-issued MCP access tokens
The `/mcp` Resource Server SHALL validate the incoming `MCP_ACCESS_TOKEN` as a JWT signed by the external Authorization Server, verifying the signature against the AS `jwks_uri`, and asserting `iss` matches the configured issuer, `aud` contains the configured MCP resource identifier, and the token is within its `exp`/`nbf` window (allowing a small configured clock skew). The gateway SHALL NOT accept tokens it minted itself and SHALL NOT expose any authorization, login, registration, or consent endpoints.

#### Scenario: Valid token is accepted
- **WHEN** a request presents a `Bearer` token signed by the external AS with `iss` = configured issuer and `aud` including the MCP resource, not expired
- **THEN** the gateway authenticates the request and processes the MCP call

#### Scenario: Token for a different audience is rejected
- **WHEN** a request presents a valid AS-signed token whose `aud` does not include the configured MCP resource
- **THEN** the gateway responds `401`

#### Scenario: Expired or wrongly-signed token is rejected
- **WHEN** a request presents a token that is expired or whose signature does not verify against the AS `jwks_uri`
- **THEN** the gateway responds `401`

#### Scenario: No authorization-server endpoints are served
- **WHEN** a client requests `/oauth2/authorize`, `/oauth2/token`, `/connect/register`, `/login`, or a consent page
- **THEN** the gateway does not provide an Authorization Server response for these paths
