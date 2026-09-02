# OAuth 2.0 OBO Token Exchange and MCP Gateway Design

> Current target design for BLSS. Source of truth:
> `openspec/changes/gateway-obo-token-exchange/`.

## 0. Implementation status

| Part | Status |
|------|--------|
| MCP Gateway (`mcp-spike`) — token validation, discovery metadata, lazy aggregation, prefix routing | **Implemented** |
| Master AS — signing key, JWKS, RFC 8414 metadata | **Implemented** (`mcp-obo-token-issuance` §1) |
| Master AS — `USER_TOKEN` minting | Not started (§2 of that change) |
| Master AS — token endpoint (exchange + refresh) | Not started (§3) |
| BLSS MCP — `/mcp` endpoint, per-request authentication, `sub` → user id | **Implemented** (`mcp-blss-server` §1–2) |
| BLSS MCP — actual business tools | Not started (§3); only a `whoami` diagnostic tool exists |
| Superset MCP | Deferred until its authentication contract is confirmed |

**Nothing is end-to-end yet**: no component can issue an `MCP_ACCESS_TOKEN` outside of tests, because
the token endpoint does not exist. That is the single blocking item.

Two implementation choices worth recording here because they are not obvious from the design:

- The master token endpoint is **hand-written**, not `spring-security-oauth2-authorization-server`.
  That library's token-exchange provider requires an authenticated registered client and requires the
  `subject_token` to be an access token it issued and stored itself — both contradict this design
  (`token_endpoint_auth_methods_supported=["none"]`, and a `USER_TOKEN` minted on a dedicated path).
- The Gateway's RFC 9728 metadata is served by **Spring Security's own filter**, not by an
  application controller. Spring Security 7 handles `/.well-known/oauth-protected-resource/**`
  itself and shadows any controller mapped there.

## 1. Goals

- Preserve the identity of the user already authenticated by master.
- Let the browser call the Agent with a session-bound `USER_TOKEN`, which the Agent exchanges without an interactive OAuth redirect flow.
- Keep `mcp-spike` as a pure MCP Gateway / OAuth Resource Server, not an Authorization Server.
- Aggregate BLSS MCP and Superset MCP tools behind one `/mcp` endpoint.
- Forward the same validated user token to the selected backend, where RBAC is enforced again.

## 2. Roles

| Component | Responsibility |
|-----------|----------------|
| Chatbot UI (browser) | Mints a `USER_TOKEN` before each chat request and calls the Agent directly with it |
| Core / master | Exposes the mint endpoint; derives `sub` from the authenticated session |
| Agent Service | Performs RFC 8693 exchange and refresh, caches the MCP tokens per user/resource, and acts as the MCP client |
| Master Authorization Server | Publishes RFC 8414 metadata and JWKS; issues `MCP_ACCESS_TOKEN` and `MCP_REFRESH_TOKEN` |
| MCP Gateway (`mcp-spike`) | Validates the access token, lazily aggregates backend tools, routes calls, and passes the token through |
| BLSS MCP | Exposes BLSS tools and independently validates the token before applying BLSS RBAC |
| Superset MCP | Exposes Superset tools and independently validates the token before applying Superset authorization |

The Gateway does not expose `/login`, authorization-code/PKCE, dynamic client registration,
consent, or its own token endpoint.

**No component in this chain has a service account.** The Agent can only reach `/mcp` inside a user
request, and the Gateway can only discover backend tools once some user's authenticated request
arrives — which is why catalog discovery is lazy (§6) rather than done at startup.

## 3. Token Model

### USER_TOKEN

```text
format:    signed JWT (RS256, master's key)
issuer:    master
subject:   current authenticated user
audience:  Agent Service resource
token_use: mcp_user
sid:       current master login session
TTL:       15 minutes, clamped to the end of the login session
```

**The browser mints one immediately before every chat request and does not cache it.** Master keeps
a record per login session and returns the recorded token while more than **5 minutes** remain,
re-signing otherwise — so a caller always receives at least 5 minutes of usable lifetime.

```
      ├──────────────────── 15 min ────────────────────┤
      ├──────── stable: return the recorded one ──┼ 5m ┤
      0                                               exp
                                              ↑ re-sign from here
```

Returning the same token bounds how many credentials are live: a user sending ten messages holds one
token rather than ten. The record is keyed by **login session** and cleared on logout — keying it by
user would hand a freshly logged-in user a token bound to the dead `sid`, leaving them without MCP
access for up to 15 minutes.

It is a signed JWT rather than an opaque handle because the Agent is its audience: the Agent
validates it locally and reads `sub`/`sid` for its cache key.

The Agent uses it both as its inbound credential and as the RFC 8693 `subject_token`. The token
endpoint requires no separate Agent client authentication and validates that `sid` still identifies
an active session before exchange.

> **Topology**: the browser calls the Agent **directly** (it is not proxied through Core), so
> `<agent-resource>` is an externally reachable URL behind the same reverse proxy as the gateway.
> The `USER_TOKEN` therefore lands in browser JavaScript. The browser already holds `APP_SESSION`,
> which is more powerful, so the incremental exposure is not about duration but about
> **portability** — an `HttpOnly` cookie cannot be exfiltrated by XSS, a JWT can. This is bounded by
> the 15-minute lifetime and by `sid` binding, and accepted.

### MCP_ACCESS_TOKEN

```text
issuer: master Authorization Server
subject: preserved from USER_TOKEN
audience: the MCP Gateway resource
TTL: approximately 30 minutes
```

There is one shared MCP audience for the Gateway and all configured backends. The Gateway does not
exchange this token again or mint a backend-specific token.

### MCP_REFRESH_TOKEN

The Agent uses the refresh token to renew the MCP access token. The Gateway does not handle refresh
tokens. If refresh returns `invalid_grant`, the Agent repeats token exchange with the `USER_TOKEN`
that arrived with the current request. Logout prevents future exchange **and** future refresh because
`sid` is no longer active; an existing MCP access token is allowed to expire naturally.

> **Retained, though currently redundant.** Because the browser supplies a fresh `USER_TOKEN` on
> every chat request, the Agent can always renew by repeating the exchange, at the same cost as a
> refresh (one token-endpoint call plus one session lookup). Refresh therefore buys nothing today
> while being the longest-lived credential in the system (~8 h, portable), and retaining it obliges
> the `sid` binding plus a session re-check on **every** refresh — without which logout would stop
> revoking MCP access. **Revisit when** the Agent gains work that outlives the request that started
> it (asynchronous or background tool calls), at which point no fresh `USER_TOKEN` is available and
> refresh becomes necessary. Removing it would cap the longest-lived credential at 30 minutes.

## 4. Discovery and Token Exchange

```text
Agent -> Gateway POST /mcp without token
Gateway -> Agent 401 + WWW-Authenticate resource_metadata=<gateway metadata URI>

Agent -> Gateway GET /.well-known/oauth-protected-resource
Gateway -> Agent resource=<mcp-resource>, authorization_servers=[master AS]

Agent -> master AS GET /.well-known/oauth-authorization-server
master AS -> Agent token_endpoint, jwks_uri, supported grants
```

Concrete AS endpoints as implemented:

| Purpose | Path (relative to `issuer`) |
|---------|-----------------------------|
| RFC 8414 metadata | `/.well-known/oauth-authorization-server` |
| JWK Set | `/.well-known/jwks.json` |
| Token endpoint | `/oauth2/token` (not implemented yet) |

> **Deployment note.** Master is a WAR served under a Tomcat context path, but the `issuer` is an
> externally reachable host (`https://auth.blss.local`). A reverse proxy must map the issuer origin
> onto master's context path, or discovery will not resolve. The `jwks_uri` advertised in the
> metadata is derived from the configured issuer, so it is whatever the proxy exposes.

```text
Agent -> master AS POST /oauth2/token
  grant_type=urn:ietf:params:oauth:grant-type:token-exchange
  subject_token=<USER_TOKEN>
  subject_token_type=urn:ietf:params:oauth:token-type:jwt
  resource=<mcp-resource>

master AS -> Agent MCP_ACCESS_TOKEN + MCP_REFRESH_TOKEN
```

The token endpoint uses the validated `USER_TOKEN` as its only credential and advertises
`token_endpoint_auth_methods_supported=["none"]`. V1 does not advertise or request OAuth scopes;
backend RBAC is authoritative.

## 5. Gateway Token Validation

For every authenticated MCP request, the Gateway validates:

- signature against master's JWKS;
- `iss` equals the configured master issuer;
- `aud` contains the configured MCP resource;
- `exp` and `nbf`, with configured clock skew.

An invalid token produces `401`. User identity comes from the signed token, never from tool
arguments or a caller-supplied identity header.

## 6. Lazy Tool Aggregation

The Gateway does not contact backend MCP servers during process startup.

```text
First authenticated tools/list
or tools/call with no catalog
             |
             v
use the current validated MCP_ACCESS_TOKEN
             |
             +--> BLSS MCP initialize + tools/list
             +--> Superset MCP initialize + tools/list
             |
             v
atomically publish catalog + routing table
```

The Gateway's own `initialize` response is fixed and advertises only `tools`; it does not trigger
backend discovery. Metadata, unauthenticated requests, and unsupported non-tools requests also do
not trigger discovery.

### Global catalog contract

Backend `tools/list` definitions are identity-independent. Every valid user sees the same tool names
and schemas for a given backend version. User-specific authorization occurs only during
`tools/call`. This permits one global catalog rather than a cache keyed by user.

### Namespacing and routing

```text
BLSS MCP tool query_asset       -> blss__query_asset
Superset MCP tool run_sql       -> superset__run_sql
```

The double underscore is the routing separator. On `tools/call`, the Gateway resolves the prefix,
removes it, forwards the original arguments and same access token, and returns the backend result
without semantic transformation.

### Invalid tool definitions are skipped, not fatal

An individual tool definition that is unusable — a name outside `[a-zA-Z0-9_-]+`, or a missing or
non-object `inputSchema` — is **dropped**, and the backend's remaining valid tools are still
published. One malformed tool must not remove an entire backend's namespace from the catalog.

A backend's discovery fails **as a unit** only when its `tools/list` response is structurally
unusable (no readable `tools` array).

The trade-off is that a skipped tool is **silently absent** rather than loudly failing: the Gateway
logs it, but the Agent sees only a tool that is not there, and calling it yields `unknown_tool`. Each
backend is therefore required to validate its own catalog — see the BLSS MCP backend contract.

### Request-driven refresh

The freshness TTL defaults to 10 minutes. Once stale, the next
authenticated `tools/list` or `tools/call` refreshes the catalog with that request's token. The
Gateway never stores an end-user token for a later refresh. Concurrent initialization or refresh
requests share one in-flight operation, and catalog/routes are published as one immutable snapshot.

## 7. Failure Semantics

| Situation | Behavior |
|-----------|----------|
| One or more backends succeed during initial discovery | Publish a partial catalog |
| Every backend fails during initial discovery | Return `catalog_unavailable`; retry no earlier than the 30-second failure backoff |
| Backend fails during a later refresh | Keep that backend's last-known-good tools and routes |
| Every backend fails during a later refresh | Successfully serve the existing snapshot and retry after the 30-second backoff |
| Tool targets a currently unavailable backend | Fail only that call |
| Another backend remains available | Continue serving and routing its tools |

Last-known-good entries can remain visible while their backend is temporarily unavailable. Agents
must therefore handle a per-call unavailable error even for a tool returned by `tools/list`.

### Stable error categories

Every Gateway-originated tool-call failure carries one of these categories in the JSON-RPC
`error.data.category`. The category and its safe message are part of the contract; the numeric code
may follow the transport convention. No message ever contains a token or an internal backend URL.

| Category | Meaning | Evaluated |
|----------|---------|-----------|
| `unknown_prefix` | The tool name's prefix matches no configured backend | **Before** any discovery |
| `catalog_unavailable` | No catalog exists because every backend failed initial discovery | After prefix check |
| `backend_unavailable` | The prefix names a configured backend whose catalog could not be discovered, or the call to it failed | After catalog resolution |
| `invalid_backend_catalog` | That backend's `tools/list` response was structurally unusable | After catalog resolution |
| `unknown_tool` | The backend was discovered but does not expose the de-prefixed tool — including a tool that was skipped as invalid | Last |

A JSON-RPC error returned by the **backend** (for example a business authorization failure) is
relayed to the Agent unchanged and does not carry a Gateway category.

## 8. OBO Pass-through and Trust Boundary

```text
Agent
  Authorization: Bearer MCP_ACCESS_TOKEN
       |
       v
Gateway validates signature/iss/aud/time
       |
       | same token, unchanged
       v
BLSS MCP or Superset MCP
  independently validates signature/iss/aud/time
  reads sub
  applies backend RBAC
```

Internal networking or loopback is not an authentication boundary. Each backend must independently
validate the signed token before trusting `sub`. The Gateway uses no static/shared credential and
must mask tokens in all logs.

## 9. Capability Boundary

Gateway v1 aggregates only MCP tools:

- supported: `initialize`, `tools/list`, `tools/call`;
- not aggregated or routed: `resources`, `prompts`, `logging`, `completions`.

Any future non-tools aggregation requires a separate design because those capabilities need their
own namespace, routing, lifecycle, and failure semantics.

## 10. Deployment Identifiers

Three identifiers must agree across master, the gateway and the Agent. Only `issuer` has to
resolve — the Agent fetches `jwks_uri`, which is derived from it. `<mcp-resource>` and
`<agent-resource>` are opaque audience identifiers, compared as strings and never dereferenced; they
are URLs only because RFC 8707 requires an absolute URI.

| Identifier | Meaning | Consumed by |
|---|---|---|
| `issuer` | Who signs the tokens; base of the discovery documents | Gateway (`iss` check, JWKS), Agent (metadata) |
| `<mcp-resource>` | The gateway's canonical MCP URI; the `aud` of `MCP_ACCESS_TOKEN` | Gateway and every backend (`aud` check), Agent (`resource` parameter) |
| `<agent-resource>` | The Agent Service; the `aud` of `USER_TOKEN` | Master's token endpoint, Agent |

### Production shape

master and the gateway sit behind the **same existing reverse proxy**, on one external origin, split
by path. This keeps an on-premise install to a single hostname:

```text
https://blss.<deployment>/mcp                                    -> gateway
https://blss.<deployment>/.well-known/oauth-protected-resource   -> gateway
https://blss.<deployment>/.well-known/oauth-authorization-server -> master
https://blss.<deployment>/.well-known/jwks.json                  -> master
everything else (/rest/** ...)                                   -> master
```

> The two `/.well-known` documents belong to **different services**. Routing both to master (the
> obvious mistake, since master owns the origin) makes the gateway's protected-resource metadata
> unreachable, and the failure presents as a blanket `401` with no other diagnostic.

Then:

```text
issuer            = https://blss.<deployment>
<mcp-resource>    = https://blss.<deployment>/mcp
<agent-resource>  = https://blss.<deployment>/agent
```

`<mcp-resource>` carrying the `/mcp` path is correct: the MCP specification defines the resource
identifier as the canonical URI of the MCP server and lists `https://mcp.example.com/mcp` as a valid
example, instructing clients to use the most specific URI they can.

The gateway's own listen port is internal; the reverse proxy is the public surface, so the port is
not part of any identifier.

### Placeholders in code

Compiled-in defaults use the reserved host `blss.invalid` (RFC 2606 guarantees `.invalid` never
resolves) rather than a production-shaped value. A deployment that forgets to configure these fails
visibly instead of minting tokens against a plausible but wrong identity; master also logs a warning
while the placeholder is still in use. Substituting the real domain is a single replacement, because
all three identifiers share the placeholder host.

## 11. Open Items

- The deployment domain itself, and the reverse-proxy rules above.
- **How master re-checks that a login session is still active** during exchange and refresh. This is
  the foundation of "one token per user + logout revokes", and it has not been investigated. The OTT
  handoff store cannot be reused (one-per-user, consumed on use, bridges to a full login session).
- Master's signing key is generated in memory at startup and is not persisted, so a restart
  invalidates every outstanding token. Accepted for a single node; must change if master is clustered.
- Superset MCP authentication and user mapping; integration is deferred until BLSS MCP is complete.
- Audit storage, retention, and sink details, tracked by the separate `gateway-audit` change.

> Protocol-revision items (`2026-07-28` support, `subscriptions/listen`, `x-mcp-header`) are tracked
> by the `gateway-mcp-2026-07-28` change, not here.
