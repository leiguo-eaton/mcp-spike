## Why

Today the sidecar is a self-contained OAuth 2.1 server: it is BOTH the Authorization Server (interactive `/login`, PKCE, dynamic client registration, consent) AND the Resource Server, and it forwards a single hard-coded `blss_token` to master REST. That model fits an interactive desktop MCP client (VS Code) but does not fit the new product flow, where the user is already authenticated to master, the browser sends a session-bound `USER_TOKEN` directly to an Agent Service, and that Agent calls MCP on the user's behalf. There is no interactive browser OAuth flow at the sidecar, and the caller identity must be the real end user — not a shared credential.

We are moving to an On-Behalf-Of (OBO) architecture in which **master** is the Authorization Server and token issuer, and **mcp-spike becomes a pure MCP Gateway / Resource Server** that validates master-issued access tokens and passes the delegated user identity through to backend MCP servers.

## What Changes

- **BREAKING**: mcp-spike stops being an Authorization Server. Remove the interactive `/login` page, authorization-code + PKCE flow, dynamic client registration (RFC 7591), consent service, seeded public clients, and the local RSA signing key / JWK persistence.
- **BREAKING**: The `/mcp` resource server no longer trusts tokens it minted itself. It validates `MCP_ACCESS_TOKEN`s issued by the **external** Authorization Server (master), verifying signature against master's `jwks_uri`, plus `iss`, `aud` (the MCP resource), and `exp`.
- The `401` challenge and protected-resource metadata (RFC 9728) now advertise the **external** Authorization Server (master) in `authorization_servers`, so the Agent's MCP client discovers master — not the sidecar — for token exchange.
- **BREAKING**: Leg B changes from a fixed `blss_token` Basic credential to per-user identity pass-through. The gateway forwards the validated `MCP_ACCESS_TOKEN` unchanged to the selected backend MCP server as a Bearer JWT; that backend independently validates the current request token and applies the real user's RBAC. The static credential, local demo tools, and direct `MasterClient` REST path are removed from the final gateway.
- mcp-spike becomes a true **MCP Gateway** in front of multiple backend MCP servers (BLSS MCP, Superset MCP, …), not just a single-backend forwarder. It acts as an MCP client to each backend, aggregates their `tools/list` into one unified catalog with **double-underscore namespace prefixes** (`blss__query_asset`, `superset__run_sql`), and routes `tools/call` back to the owning backend by prefix. A single shared audience (the gateway's MCP resource) is used across all backends (Option A); backend-level authorization is each backend's own RBAC.
- Remove now-unused config: demo resource-owner credentials (`auth-username`/`auth-password`), `seed-public-client-ids`, and the H2-backed OAuth authorization/registered-client/consent schemas. Add config for the external issuer/JWKS, the expected MCP resource (audience), and the backend MCP registry (base URLs + prefixes).

## Capabilities

### New Capabilities
- `gateway-token-validation`: The `/mcp` Resource Server validates externally-issued MCP access tokens (signature via the external AS `jwks_uri`, plus `iss`/`aud`/`exp`), and challenges unauthenticated requests with a `401` + RFC 9728 protected-resource metadata that points to the external Authorization Server.
- `obo-identity-passthrough`: The gateway forwards the validated end-user access token unchanged to the target backend MCP server as a `Bearer` JWT (On-Behalf-Of), replacing the fixed shared credential.
- `mcp-tool-aggregation`: The gateway aggregates multiple backend MCP servers' tools into one unified, namespaced (`<backend>__<tool>`) catalog and routes `tools/call` to the owning backend by prefix. It lazily discovers the global, identity-independent catalog on the first authenticated request that needs it, refreshes stale data on a later catalog-dependent request using that request's token, shares concurrent discovery through single-flight, and degrades gracefully through partial initial catalogs and last-known-good entries on refresh failure.

### Modified Capabilities
<!-- No existing specs under openspec/specs/ yet; all behavior is captured as new capabilities. -->

## Impact

- **mcp-spike code**: Remove `AuthorizationServerConfig` (AS chain, DCR, consent, JWK, token customizer, seeded clients). Rework `SecurityConfig` to a single stateless resource-server chain (drop `formLogin`, `userDetailsService`, `PasswordEncoder`). Repoint `JwtDecoder` to the external `jwks_uri`. Update `McpAuthenticationEntryPoint` / `ProtectedResourceMetadataController` to advertise the external AS. Replace the local demo-tool/`MasterClient` REST path with downstream MCP discovery and routing, forwarding the current validated Bearer token on every backend request. Trim `SidecarProperties` and `application.yml`.
- **External dependency (master / bldc-blss-master-service)**: Must (1) let an authenticated browser mint one session-bound `USER_TOKEN` for the current login session, (2) expose an Authorization Server with an RFC 8693 token-exchange endpoint that validates that the referenced login session is still active and swaps `USER_TOKEN` for `MCP_ACCESS_TOKEN` + `MCP_REFRESH_TOKEN` bound to the MCP resource, and (3) publish `jwks_uri` + RFC 8414 metadata. These are prerequisites but are outside this repo's change.
- **External dependency (BLSS MCP)**: The first delivered backend must expose stateless Streamable HTTP, validate the current Bearer token independently on every MCP request, return an identity-independent `tools/list`, and enforce user RBAC during `tools/call`. Superset MCP integration is deferred until its authentication contract is known.
- **External dependency (Agent Service)**: New MCP client that performs the token exchange and caches access/refresh tokens; not built in mcp-spike.
- **Dependencies**: Drop the H2 datasource + Spring Authorization Server schema bootstrapping used only for the AS role (retain only if needed for other reasons).
