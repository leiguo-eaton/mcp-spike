## 1. Configuration

- [x] 1.1 Add `sidecar.issuer-uri` (external AS / master) and `sidecar.jwks-uri` (or rely on issuer discovery) to `SidecarProperties`
- [x] 1.2 Add `sidecar.mcp-resource` (expected `aud`) and an optional `sidecar.clock-skew` to `SidecarProperties`
- [x] 1.3 Remove `blssUserToken`, `authUsername`, `authPassword`, `seedPublicClientIds` from `SidecarProperties`
- [x] 1.4 Update `application.yml`: add issuer/jwks/mcp-resource; remove `blss-user-token`, `auth-username`, `auth-password`, the H2 `datasource`, and the Spring Authorization Server `sql.init` schema block

## 2. Resource Server & Token Validation

- [x] 2.1 Replace the local `JwtDecoder` (JWK from disk) with one built from the external issuer/`jwks_uri`
- [x] 2.2 Add a JWT validator asserting `iss` = configured issuer, `aud` contains `mcp-resource`, and `exp`/`nbf` within configured clock skew
- [x] 2.3 Rework `SecurityConfig` into a single stateless resource-server chain for `/mcp` (drop `formLogin`, `userDetailsService`, `PasswordEncoder`, the default/login chain)
- [x] 2.4 Keep `/.well-known/**` and `/actuator/health` permitted; ensure no AS endpoints are exposed

## 3. Remove Authorization Server Role

- [x] 3.1 Delete `AuthorizationServerConfig` (AS chain, DCR, consent service, seeded clients, token customizer, on-disk JWK)
- [x] 3.2 Remove H2 datasource + AS schema bootstrapping and any now-unused Spring Authorization Server dependencies from `pom.xml`
- [x] 3.3 Remove the interactive login view/assets if any are bundled

## 4. Discovery Metadata

- [x] 4.1 Update `ProtectedResourceMetadataController` so `authorization_servers` points to the external AS (master) and `resource` = `mcp-resource` — _superseded during section 7: Spring Security 7 serves `/.well-known/oauth-protected-resource/**` itself and shadowed the controller, so the metadata is now customised on the resource-server chain and the controller was removed_
- [x] 4.2 Verify `McpAuthenticationEntryPoint` still emits `401` + `resource_metadata` pointer to the protected-resource metadata

## 5. On-Behalf-Of Pass-through (Leg B)

> Tasks 5.1-5.4 implemented the transitional local demo-tool/`MasterClient` path. Section 7 replaces and removes that path for the final backend-MCP gateway.

- [x] 5.1 Update `UserTokenCaptureFilter` to capture the raw inbound bearer token (from the validated `Jwt` / `Authorization` header) into `UserTokenContext` instead of the `blss_token` claim
- [x] 5.2 Remove the `BLSS_TOKEN_CLAIM` mechanism from `UserTokenContext`
- [x] 5.3 Change `MasterClient` to send `Authorization: Bearer <token>` (not `Basic`) and to make no shared-credential fallback
- [x] 5.4 Confirm the token is masked in `MasterClient` logs (retain existing masking)

## 6. Verification

- [x] 6.1 Update/replace unit tests to cover external-token validation (valid, wrong `aud`, expired, bad signature) and the 401 challenge/metadata
- [x] 6.2 Add a test asserting `MasterClient` forwards `Bearer` and never a static credential
- [ ] 6.3 Manual end-to-end check against a master that issues `MCP_ACCESS_TOKEN` (or a stubbed AS) via the Agent flow — _blocked: requires a running master AS environment_
- [x] 6.4 Update `doc/auth-flow.md` to describe the OBO gateway architecture (supersede the AS+RS narrative)

## 7. MCP Gateway — Multi-Backend Tool Aggregation

- [x] 7.1 Add a static backend MCP registry to config, initially containing BLSS MCP; validate absolute URLs and prefixes (required, case-sensitive unique, `[a-zA-Z0-9_-]+`, no `__`); invalid config fails startup while unreachable backends do not
- [x] 7.2 Implement a downstream Streamable HTTP MCP client that sends the current validated Bearer token on every request; prefer stateless operation, use/close temporary discovery sessions if required, and never share identity-bound sessions across users
- [x] 7.3 Build the aggregated catalog: prefix each tool name with `<backend>__` and maintain a prefix → backend routing table
- [x] 7.4 Serve the gateway `tools/list` from the aggregated catalog
- [x] 7.5 Implement `tools/call` routing: parse prefix, resolve backend, strip prefix, forward with the same `MCP_ACCESS_TOKEN` (OBO pass-through), return result unchanged; reject unknown prefixes
- [x] 7.6 Lazily initialize the catalog on the first authenticated `tools/list`, or before an authenticated `tools/call` when no catalog exists; use the triggering request's validated token for backend `initialize` + `tools/list`, and perform no backend discovery at gateway startup (D10)
- [x] 7.7 Treat the catalog as global and identity-independent: all valid users receive the same backend tool definitions, no user identity is stored in the catalog/routing table, and user-specific RBAC is enforced only by the backend during `tools/call`
- [x] 7.8 Add configurable catalog freshness TTL (default 10m) and failure backoff (default 30s); after expiry, refresh on the next authenticated `tools/list` or `tools/call` using that request's token, with no token-dependent background refresh and no retained end-user token
- [x] 7.9 Add single-flight concurrency control for lazy initialization and refresh, and atomically publish the catalog + routing-table snapshot
- [x] 7.10 Implement graceful degradation (D11): publish a partial catalog when at least one backend succeeds initially; return `catalog_unavailable` and remain uninitialized when all initially fail; retain last-known-good entries and successfully serve the existing snapshot when all later refreshes fail; respect failure backoff; fail only calls to an unavailable backend
- [x] 7.11 Advertise **only** the `tools` capability in the gateway `initialize` handshake; do not advertise or route `resources` / `prompts` / `logging` / `completions` (D12 — v1 scope; BLSS MCP exposes only `tools`)
- [x] 7.12 Preserve every valid downstream tool-definition field except the prefixed name; **skip** an individual invalid tool definition (bad name grammar or missing/non-object `inputSchema`) and log it, while still publishing that backend's remaining valid tools; fail the backend's discovery as a unit only when the `tools` response is structurally unusable
- [x] 7.13 Implement stable safe error categories and precedence: reject an unconfigured prefix as `unknown_prefix` before discovery; distinguish `backend_unavailable`, `unknown_tool`, `invalid_backend_catalog`, and `catalog_unavailable`; never expose tokens or internal backend URLs
- [x] 7.14 Remove local POC/demo tool registration and the direct REST path (`TopologyTools`, `DeviceSearchTools`, `McpToolsConfig`, `MasterClient`, and obsolete request-token/config support where no longer needed), leaving only namespaced downstream tools
- [x] 7.15 Remove `mcp.read` / `mcp.invoke` from protected-resource metadata and do not enforce or request OAuth scopes in v1
- [x] 7.16 Add aggregation/routing tests: static registry validation, BLSS-first configuration, `__` namespacing/collision avoidance, complete tool-definition preservation, per-tool invalid-definition skipping (siblings survive) and structural `invalid_backend_catalog` rejection, prefix routing + de-prefixing, stable error precedence, unchanged backend result, and same-token forwarding
- [x] 7.17 Add lazy-loading/session/refresh tests: no startup discovery, no discovery for unauthenticated or non-catalog requests, first `tools/list` and direct `tools/call` loading, per-request backend authentication across users, temporary session cleanup, token disposal, 10m TTL refresh, 30s failure backoff, and no background refresh
- [x] 7.18 Add concurrency/degradation tests: concurrent requests share one discovery, catalog/routes publish atomically, partial initial success, all-backend initial failure remains retryable after backoff, total refresh failure serves last-known-good, backend recovery replaces stale entries, and unavailable-backend calls fail independently
- [x] 7.19 Add capability tests: `initialize` advertises only `tools`, non-tools requests are not forwarded, and protected-resource metadata advertises no v1 scopes
- [ ] 7.20 Perform BLSS MCP contract/E2E verification: stateless or temporary-session Streamable HTTP, current-token validation on every request, identity-independent `tools/list`, user RBAC on `tools/call`, and no cross-user session identity leakage — _blocked: requires a running BLSS MCP backend and master AS_
- [x] 7.21 Defer Superset registry entry and E2E coverage until its master-dependent authentication and user-mapping contract is confirmed
- [x] 7.22 Add capability tests: `initialize` advertises only `tools`, and `resources` / `prompts` / `logging` / `completions` requests are not forwarded downstream (was a duplicate "7.15"; covered by `McpGatewayControllerTest`)
