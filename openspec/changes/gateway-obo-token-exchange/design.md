## Context

The sidecar currently plays three roles in one process: OAuth 2.1 Authorization Server (interactive login, PKCE, dynamic client registration, consent, local JWK signing), Resource Server for `/mcp`, and On-Behalf-Of forwarder to master REST using a single hard-coded `blss_token`. Its client is VS Code — an interactive desktop MCP client with a browser and loopback redirect.

The target product flow begins with a user already authenticated to **master**. The browser obtains one `USER_TOKEN` bound to that login session and sends it directly to the **Agent Service** (LLM + MCP client). Before calling tools, the Agent performs an RFC 8693 token exchange at master, swapping `USER_TOKEN` for an `MCP_ACCESS_TOKEN` (+ `MCP_REFRESH_TOKEN`) bound to the MCP resource. Master validates the referenced login session during exchange and refresh. Logout prevents exchange/refresh, while an already issued access token expires naturally.

Per the decisions in exploration: **master is the Authorization Server and token issuer**; **mcp-spike becomes a pure MCP Gateway / Resource Server**; an authenticated browser obtains one session-bound `USER_TOKEN` for its current login session and sends it directly to the Agent; the AS validates both the token and continued login-session validity during exchange; and backend MCP servers independently validate the current `MCP_ACCESS_TOKEN` on every request. The Agent Service and backend MCP implementations are external and not built here.

### Current vs target (roles)

```
AS-IS                                   TO-BE
VS Code ──browser OAuth──▶ sidecar      Browser ──USER_TOKEN──▶ Agent
                           (AS + RS)      Agent ──token-exchange──▶ master (AS)
sidecar ──Basic blss_token──▶ master    Agent ──MCP_ACCESS──▶ mcp-spike (RS only)
                                          └─same Bearer JWT──▶ BLSS MCP (first backend)
```

## Goals / Non-Goals

**Goals:**
- Turn mcp-spike into a stateless Resource Server that validates `MCP_ACCESS_TOKEN`s issued by an external Authorization Server (master).
- Advertise the external AS via the `401` challenge + RFC 9728 protected-resource metadata so the Agent's MCP client discovers master for token exchange.
- Replace the fixed `blss_token` Basic credential and local demo REST facade with per-user Bearer-token pass-through to backend MCP servers.
- Act as a true MCP Gateway: aggregate multiple backend MCP servers' `tools` into one namespaced catalog and route calls to the owning backend (this is **in MVP scope** — see D9-D12 and tasks section 7).
- Remove all Authorization Server machinery from mcp-spike (login, PKCE, DCR, consent, local JWK, seeded clients, H2/AS schema).

**Non-Goals:**
- Building the Agent Service, the Chatbot UI, or the Core Server chat endpoint.
- Implementing browser-driven `USER_TOKEN` minting, master's AS (session validation, RFC 8693 token exchange, JWKS publication), or backend MCP servers — these are external prerequisites.
- Refresh-token handling — that is the Agent's responsibility; master binds refresh to the originating `sid` and rejects refresh after that login session ends.
- Backend-level token isolation — a single shared audience is used; per-backend authorization is each backend's own RBAC (see D8).
- Capability aggregation (merging downstream `initialize` capability sets) — deferred (see Open Questions).

## Decisions

### D1 — mcp-spike is a pure Resource Server; the AS moves to master
Delete the Authorization Server chain and all interactive/registration machinery. The sidecar keeps only a single stateless resource-server filter chain for `/mcp` plus the discovery/metadata endpoints.
- **Why**: The new flow has no browser and no interactive login; a co-hosted AS is dead weight and a larger attack surface. Master already owns user identity, so it is the natural token issuer.
- **Alternatives**: (a) Keep the AS in mcp-spike and add token-exchange — rejected: contradicts the "master is AS" decision and duplicates identity ownership. (b) Standalone dedicated AS — rejected for now: master already holds the app session and can mint/validate cheaply.

### D2 — Validate external tokens via the AS `jwks_uri`
Configure the `JwtDecoder` from the external issuer (OIDC/RFC 8414 discovery via `issuer-uri`, or a direct `jwks-set-uri`). Validate signature, `iss`, `exp`, and `aud` = the configured MCP resource identifier.
- **Why**: Spring Security's resource-server support does this idiomatically; audience binding (RFC 8707) prevents a token minted for another resource from being replayed at the gateway.
- **Alternatives**: Token introspection (RFC 7662) — rejected: adds a network round-trip per call and master already publishes JWKS for offline verification.

### D3 — Discovery metadata advertises the external AS
`McpAuthenticationEntryPoint` still returns `401 WWW-Authenticate: Bearer resource_metadata="…"`, and `ProtectedResourceMetadataController` still serves RFC 9728 metadata — but `authorization_servers` now points to **master**, and `resource` is the MCP resource identifier the Agent must bind its token to.
- **Why**: Keeps zero-config discovery for the Agent's MCP client while decoupling the AS from the gateway.

### D4 — On-Behalf-Of by forwarding the validated access token as a Bearer JWT
The gateway does **not** perform a second token exchange. It forwards the current inbound `MCP_ACCESS_TOKEN` unchanged to the selected backend MCP server as `Authorization: Bearer <token>`. The backend validates the current request token, reads `sub`, builds its user context, and applies RBAC during `tools/call`.
- **Why**: avoids gateway client credentials and backend-specific exchange while keeping identity cryptographically verifiable at every hop.
- **Identity claim**: each backend derives the real user from the standard `sub` claim.
- **Audience handling**: all registered backends accept the shared Gateway MCP resource audience. Accepting that audience does **not** waive signature, issuer, or time validation.
- **Alternatives**: (a) Gateway does RFC 8693 exchange to a master-REST-scoped token — rejected: reintroduces AS-client complexity in the gateway. (b) Send a `UserContext=sub=…` header instead of the token — rejected: without mTLS a header is unauthenticated and forgeable by any local process; the signed token is the trust anchor.

### D7 — Every backend MCP request is independently authenticated
BLSS MCP and every future registered backend SHALL validate `Authorization: Bearer <current MCP_ACCESS_TOKEN>` independently on every `initialize`, `tools/list`, `tools/call`, and session-termination request. Identity is derived only from the current request token and is never inherited from a previous `initialize`, MCP session, or pooled HTTP connection. `Mcp-Session-Id`, when required by an SDK, carries protocol state only and is not an authentication credential.
- **Why**: HTTP connection reuse and MCP protocol sessions must not cause cross-user identity leakage. Internal networking and loopback are transport choices, not trust boundaries.
- **Preferred transport**: stateless Streamable HTTP. If an SDK requires a stateful MCP session, discovery uses a temporary `initialize` → `tools/list` → close session, and an identity-bound session is never shared across users.

### D5 — Carry only the current validated token into downstream calls
The downstream MCP client receives the raw validated token from the current Gateway request context and sends it on backend calls. No token, user identity, or authentication context is stored in the global catalog/routing snapshot. The POC `MasterClient`, local demo tools, and their request-scoped `UserTokenContext` path are removed when backend routing replaces them.
- **Why**: the final Gateway has no direct REST tool facade, and downstream identity must remain request-scoped even if execution crosses threads.

### D6 — Remove AS persistence
Drop the H2 datasource and the Spring Authorization Server schemas (registered-client / authorization / consent) and the on-disk JWK, since the gateway no longer issues or stores tokens.
- **Why**: These existed solely for the AS role.

### D8 — Audience strategy: single shared gateway audience (Option A)
BLSS MCP and every future backend admitted to the static registry share **one** audience: the gateway's MCP resource identifier (`sidecar.mcp-resource`). The `MCP_ACCESS_TOKEN.aud` binds to the **gateway**, not to a specific backend. The gateway validates `aud`, then forwards the **same** token unchanged to whichever backend a tool call routes to; each backend independently re-validates that same token (signature via master JWKS, `iss`, `aud`, `exp`). Superset is admitted only if its future authentication contract satisfies this rule.
- **Why**: a single shared audience keeps the gateway "pure" (no second token exchange, consistent with D1/D4). Per-backend audiences would force the gateway to perform an RFC 8693 exchange per downstream hop and re-introduce AS-client complexity.
- **Accepted trade-off (isolation)**: a valid token can reach **any** backend behind the gateway — it proves "who the user is" (`sub`), not "which backend the user may call". There is no token-level backend isolation. Backend-level authorization is delegated to each backend's own RBAC / business authorization (per the handover doc §11). A compromised backend holding a live token could replay it against another backend as the same user; this is an accepted risk under same-origin trust, mitigated by short access-token TTL, backend RBAC, and not logging raw tokens.
- **v2 escape hatch (not in this change)**: if backend-level isolation is later required, add a `scope` claim (e.g., `mcp.blss`, `mcp.superset`) rather than splitting `aud`. The gateway can enforce scope-per-backend at routing time (see D9) without a second token exchange. The tool-name prefix (D9) is the natural anchor for this future check.

### D9 — Multi-backend tool aggregation via namespaced prefixes and prefix routing
The gateway is an **MCP gateway**, not merely an API gateway: it presents downstream backends as a single unified MCP endpoint. Acting as an MCP **client** to each backend, it aggregates their `tools/list` into one flat catalog exposed to the Agent, and routes `tools/call` back to the owning backend.
- **Namespacing**: every downstream tool is exposed with a backend prefix using a **double underscore** separator, e.g. `blss__query_asset`, `superset__run_sql`. The prefix (a) eliminates name collisions between backends, (b) serves as the routing key (the gateway reverse-maps prefix → backend), and (c) anchors the future scope check in D8. A double underscore is chosen because the MCP tool-name grammar allows `[a-zA-Z0-9_-]` but **not** `.`, so a dotted namespace (`blss.query_asset`) is invalid.
- **Routing**: on `tools/call`, the gateway parses the prefix, looks up the target backend, strips the prefix to recover the backend-native tool name, and forwards the call with the same `MCP_ACCESS_TOKEN` (D8 passthrough). Results are returned to the Agent unchanged.
- **Why**: keeps "the Agent sees one MCP" while allowing many backends; the prefix does triple duty (collision-avoidance, routing, isolation anchor).
- **Alternatives**: exposing multiple MCP resources to the Agent (one per backend) — rejected: breaks the unified-entry vision. Hyphen (`blss-query_asset`) — viable but `-` also appears inside tool names, making the split ambiguous; `__` is unambiguous.

### D10 — Downstream discovery: lazy initialization and request-driven refresh
The gateway SHALL NOT contact backend MCP servers at process startup. The first authenticated request that requires the catalog triggers `initialize` + `tools/list` against each configured backend using that request's validated `MCP_ACCESS_TOKEN`. `tools/list` requires the catalog; a `tools/call` received before the catalog exists also initializes it before resolving the prefix. The gateway's own `initialize` handshake does not require the downstream catalog and therefore does not trigger discovery.

The aggregated catalog is global because downstream tool definitions are identity-independent: every valid user sees the same tool names and schemas. User-specific authorization is applied by the target backend only when `tools/call` executes. A backend MUST NOT filter `tools/list` by the delegated user's identity if its tools are aggregated into this global catalog.

The gateway caches an immutable catalog + routing-table snapshot. The freshness TTL is **10 minutes**, not a background schedule. After the TTL expires, the next authenticated `tools/list` or `tools/call` refreshes the catalog using that request's validated token. The gateway never retains an end-user token for later/background discovery. Concurrent initialization or refresh requests share one in-flight operation (single-flight), and the catalog and routing table are published atomically so callers never observe mismatched versions. Discovery uses temporary downstream sessions unless a backend is demonstrably stateless.
- **Why**: startup has no delegated user token, while protected backends require the same validated user token for `initialize` and `tools/list`. Request-driven discovery supplies that token without introducing a gateway service credential or retaining an end-user secret. Caching still avoids per-request fan-out.
- **Alternatives**: (a) startup/background discovery with a service credential — rejected: introduces a second credential model. (b) retain the first user's token for scheduled refresh — rejected: unsafe and fails when the token expires. (c) per-user catalogs — rejected because tool definitions are explicitly identity-independent and authorization belongs at `tools/call`.

### D11 — Graceful degradation on partial backend failure
During initial discovery, if at least one backend succeeds, the gateway SHALL publish and serve a partial catalog containing the reachable backends. If every backend fails, the gateway SHALL return an MCP error and leave the catalog uninitialized so a later catalog-dependent request can retry.

During a later refresh, a failed backend SHALL retain its last-known-good tool definitions and routes while successful backends are updated. If every backend refresh fails and a snapshot exists, the triggering request succeeds using that last-known-good snapshot. Failed refresh does not advance the last-successful timestamp; another refresh is attempted no earlier than the **30-second failure backoff**. During backoff, `tools/list` serves stale data and `tools/call` attempts the stale route with the current token. A `tools/call` routed to a currently unavailable backend fails only that call.
- **Why**: one downstream outage should not take the entire MCP surface offline; the Agent can still use available tools.
- **Alternatives**: (a) all-or-nothing initial discovery — rejected unless all backends fail. (b) remove a backend's tools on every refresh failure — rejected because a transient discovery failure would abruptly invalidate a previously usable catalog.

### D12 — v1 aggregates only the `tools` capability
The gateway in v1 SHALL aggregate and expose **only** the `tools` capability. In the `initialize` handshake with the Agent, the gateway advertises `tools` (and its own auth/discovery surface) and does **not** advertise `resources`, `prompts`, `logging`, or `completions` — even if a downstream backend supports them. It performs no aggregation or routing for those capabilities.
- **Why**: the only confirmed backend today, **BLSS MCP, exposes only `tools`** (asset queries, etc. are all function calls). Each additional capability has its own aggregation/routing complexity — `resources` carry URIs (needing their own namespacing) and subscriptions; `prompts` carry templated arguments; `logging` is a server→client push. Designing those now, with no consumer, is over-engineering. Advertising only `tools` is effectively choosing the "minimal set" and defers the union-vs-intersection question until a real need appears.
- **Scope boundary**: if a future backend (or Superset MCP) requires `resources` / `prompts` / another capability, each will be added as its **own** follow-up change that designs that capability's aggregation and routing rules; it is explicitly out of scope here.
- **Alternatives**: (a) Union — advertise any capability supported by at least one backend — rejected for v1: forces the gateway to handle "Agent requests a capability the target backend lacks" with no current benefit. (b) Intersection — advertise only capabilities all backends share — equivalent to "tools only" today, but framed as a rule rather than an explicit v1 scope; we prefer the explicit boundary.

### D13 — Static backend registry; BLSS MCP first
V1 uses a statically configured backend registry. The first required backend is BLSS MCP. Superset is not an MVP dependency and will be registered only after its authentication contract is confirmed. Prefixes are non-empty, case-sensitive, unique, match `[a-zA-Z0-9_-]+`, and cannot contain `__`; backend URLs are absolute. Invalid registry configuration fails Gateway startup, while a correctly configured but unreachable backend does not.

### D14 — Preserve tool definitions; skip individual invalid tools
The Gateway changes only the externally visible tool `name` by adding the backend prefix. It preserves all other fields supported by the negotiated MCP version, including description, input/output schemas, annotations, titles, and extension fields, without semantic modification.

An **individual** tool definition that is invalid — a `name` outside the MCP tool-name grammar `[a-zA-Z0-9_-]+`, or a missing / non-object `inputSchema` — is **skipped**, and that backend's remaining valid tools are still published. Every skipped tool is logged with its backend prefix and offending name so the defect stays visible.

A backend's discovery attempt fails **as a unit** only when the response is structurally unusable — the `tools` field is absent or is not an array — which is reported as `invalid_backend_catalog`; initial and refresh degradation then follow D11.
- **Why**: a single malformed tool must not delete an entire backend's namespace from the catalog. If BLSS ships one bad tool, all-or-nothing rejection would take every `blss__*` tool offline, which is a far worse outcome than that one tool being missing. Partial availability is the same principle already chosen for unreachable backends in D11.
- **Trade-off (accepted)**: a skipped tool is silently absent from `tools/list` rather than loudly failing the Agent. Visibility is preserved through gateway logs, and the backend is independently required to validate its own catalog (see the BLSS MCP contract), so gateway-side validation is a defensive net rather than the primary check.
- **Alternatives**: (a) all-or-nothing per backend — rejected as above. (b) Publish the invalid tool unchanged and let the Agent fail — rejected: a name outside the grammar breaks prefix routing, and a missing `inputSchema` breaks the MCP contract with the Agent.

### D15 — Stable Gateway error categories
The Gateway SHALL distinguish at least: `unknown_prefix`, `backend_unavailable`, `unknown_tool`, `invalid_backend_catalog`, and `catalog_unavailable`. A prefix absent from the static registry is rejected before catalog discovery. A configured prefix whose backend cannot be discovered is `backend_unavailable`; a successfully discovered backend without the requested native tool is `unknown_tool` (this also covers a tool that was skipped as invalid per D14); a backend whose `tools/list` response is structurally unusable is `invalid_backend_catalog`; total initial discovery failure is `catalog_unavailable`. Exact MCP numeric codes may follow the selected SDK, but the category and safe message remain stable and no error exposes tokens or internal URLs.

## Risks / Trade-offs

- **Token pass-through / confused-deputy** → The gateway forwards a user token downstream. Mitigate by validating `aud` strictly at the gateway, requiring every backend request to be independently authenticated (D7), applying backend RBAC, and never logging raw tokens.
- **Remote backend transport** → A remote backend connection must use TLS. Loopback for an in-process/co-located BLSS deployment remains a transport convenience, not a trust boundary.
- **Clock skew between master (issuer) and gateway** → `exp`/`nbf` validation may reject valid tokens. Mitigate with a small configurable allowed clock skew.
- **Availability coupling to master JWKS** → The gateway needs master's `jwks_uri` reachable; Spring caches JWKS, but a cold start during master downtime fails validation. Mitigate with JWKS caching + sane refresh.
- **Breaking change / coordinated rollout** → The gateway cannot serve the old VS Code interactive flow after this change. Mitigate with the migration plan below.

## Migration Plan

1. **Prerequisite (master)**: master exposes browser minting of a session-bound `USER_TOKEN`, AS discovery (`issuer` + `jwks_uri`), and the RFC 8693 token-exchange endpoint that checks login-session validity. The gateway change cannot ship before this is available.
2. **Gateway config**: introduce `sidecar.issuer-uri` / `sidecar.jwks-uri` and `sidecar.mcp-resource` (expected audience); remove `blss-user-token`, `auth-username`/`auth-password`, `seed-public-client-ids`, and the H2/AS datasource block.
3. **Code**: land the RS-only `SecurityConfig`, external `JwtDecoder`, metadata endpoints, static BLSS MCP registry, lazy aggregation/routing, and remove the local demo-tool/`MasterClient` path.
4. **Verify** end-to-end with the Agent Service, master AS, and BLSS MCP.
5. **Rollback**: redeploy the previous sidecar build (self-contained AS+RS) and restore the old config; no persistent gateway state is created by the new build, so rollback is a redeploy.

## Resolved Questions

- **Identity claim**: each backend rebuilds user context from the standard `sub` claim on the current request.
- **Audience**: Gateway and registered backends accept the shared `<mcp-resource>` audience while still fully validating the token.
- **Backend transport/authentication**: prefer stateless Streamable HTTP; authenticate every request; never share identity-bound MCP sessions across users.
- **Registry and rollout**: static registry, BLSS MCP first; Superset integration is deferred until its authentication is known.
- **Catalog timing**: 10-minute freshness TTL and 30-second failure backoff.
- **Environment identifiers (proposed, pending team review)**: `issuer` = `https://auth.blss.local`, `<mcp-resource>` (gateway `aud`) = `https://mcp.blss.local`. These must match master's AS config exactly (`sidecar.issuer-uri` = `issuer`, `sidecar.mcp-resource` = `<mcp-resource>`). If the team raises no objection, these become the production values.

## Open Questions

- Whether a future version should mark the catalog stale on downstream `notifications/tools/list_changed`.
- Superset MCP authentication and user-mapping contract; Superset is deferred until this is known.
