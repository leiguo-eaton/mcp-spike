## ADDED Requirements

### Requirement: Aggregate downstream tools into a single namespaced catalog
The gateway SHALL act as an MCP client to each configured backend MCP server, discover their tools via `tools/list`, and expose a single unified `tools/list` to the Agent. Each downstream tool SHALL be exposed with a backend namespace prefix joined by a **double underscore** (`__`), e.g. `blss__query_asset`. The prefix SHALL be unique per backend and SHALL be used as the routing key. Except for the prefixed name, every tool-definition field supported by the negotiated MCP version SHALL be preserved without semantic modification. An individual tool definition that is invalid SHALL be skipped while that backend's remaining valid tools are still published.

#### Scenario: Tools from multiple backends are merged with prefixes
- **WHEN** the Agent calls `tools/list` on the gateway
- **THEN** the response contains every reachable backend's tools, each renamed to `<backend-prefix>__<original-tool-name>`
- **AND** two backends exposing the same original tool name do not collide, because each carries its own prefix

#### Scenario: Dotted namespaces are not used
- **WHEN** a downstream tool is exposed through the gateway
- **THEN** the exposed name uses `__` as the separator and contains only characters valid for an MCP tool name (`[a-zA-Z0-9_-]`), never `.`

#### Scenario: Tool definitions are preserved
- **WHEN** a backend returns a valid tool with description, input/output schemas, annotations, title, or supported extension fields
- **THEN** the gateway changes only its externally visible name
- **AND** it preserves the remaining fields without semantic modification

#### Scenario: An invalid tool definition is skipped, not fatal
- **WHEN** a backend returns a tool whose name is outside `[a-zA-Z0-9_-]+` or whose `inputSchema` is missing or is not an object
- **THEN** the gateway omits only that tool from the published catalog
- **AND** the same backend's remaining valid tools are still exposed under their prefix
- **AND** the gateway logs the skipped tool's backend prefix and name

#### Scenario: A structurally unusable catalog fails that backend discovery
- **WHEN** a backend's `tools/list` response has no `tools` field, or `tools` is not an array
- **THEN** that backend's discovery attempt fails as a unit with `invalid_backend_catalog`
- **AND** the gateway applies the initial or refresh degradation rules

#### Scenario: A skipped tool is reported as unknown when called
- **WHEN** the Agent calls a tool whose definition the gateway skipped as invalid
- **THEN** the gateway returns an `unknown_tool` error
- **AND** it does not forward the call to the backend

### Requirement: Use a validated static backend registry
V1 SHALL use a static backend registry, initially containing BLSS MCP. Each prefix SHALL be non-empty, case-sensitive, unique, match `[a-zA-Z0-9_-]+`, and not contain `__`; each backend URL SHALL be absolute. Invalid registry configuration SHALL fail gateway startup. A valid registry entry whose backend is unreachable SHALL NOT fail startup because discovery is lazy. Superset MCP SHALL not be an MVP dependency and may be added after its authentication contract is defined.

#### Scenario: Invalid static registry fails startup
- **WHEN** configured backends contain a duplicate/invalid prefix or a non-absolute URL
- **THEN** gateway startup fails with a configuration error

#### Scenario: Unreachable configured backend does not fail startup
- **WHEN** the static registry is valid but BLSS MCP is unreachable
- **THEN** the gateway starts without contacting or requiring that backend

### Requirement: Route tool calls to the owning backend by prefix
On `tools/call`, the gateway SHALL parse the backend prefix from the tool name, resolve the owning backend, strip the prefix to recover the backend-native tool name, and forward the call to that backend. The gateway SHALL forward the same validated `MCP_ACCESS_TOKEN` (On-Behalf-Of pass-through) and return the backend result to the Agent unchanged.

#### Scenario: A prefixed tool call is routed and de-prefixed
- **WHEN** the Agent calls `tools/call` with name `blss__query_asset`
- **THEN** the gateway forwards `tools/call` with name `query_asset` to the BLSS backend
- **AND** the outbound call carries `Authorization: Bearer <the validated access token>`
- **AND** the backend result is returned to the Agent without modification

#### Scenario: Unknown prefix is rejected
- **WHEN** the Agent calls `tools/call` with a name whose prefix matches no configured backend
- **THEN** the gateway returns an `unknown_prefix` error before catalog discovery
- **AND** it does not forward the call to any backend

#### Scenario: Configured backend is unavailable
- **WHEN** the tool prefix names a configured backend that has no discovered catalog entry because discovery failed
- **THEN** the gateway returns a `backend_unavailable` error

#### Scenario: Native tool is unknown
- **WHEN** the prefix resolves to a successfully discovered backend but its catalog does not contain the de-prefixed tool name
- **THEN** the gateway returns an `unknown_tool` error

### Requirement: Lazily discover downstream tools on authenticated demand
The gateway SHALL NOT contact backend MCP servers at process startup. The first authenticated request that requires the catalog SHALL trigger `initialize` + `tools/list` against each configured backend using the validated `MCP_ACCESS_TOKEN` from that request. Both `tools/list` and `tools/call` require the catalog; the gateway's own `initialize` handshake does not. The gateway SHALL NOT retain the triggering end-user token after discovery completes.

#### Scenario: Startup does not discover backend tools
- **WHEN** the gateway process starts
- **THEN** it does not call `initialize` or `tools/list` on any backend
- **AND** the aggregated catalog remains uninitialized

#### Scenario: First authenticated tools/list lazily builds the catalog
- **WHEN** the catalog is uninitialized and an authenticated Agent calls `tools/list`
- **THEN** the gateway uses that request's validated access token to call `initialize` + `tools/list` on each configured backend
- **AND** it builds the unified catalog and routing table before responding
- **AND** it does not retain the access token after discovery completes

#### Scenario: Direct tools/call lazily builds the catalog
- **WHEN** the catalog is uninitialized and an authenticated Agent calls `tools/call`
- **THEN** the gateway initializes the catalog before resolving and routing the prefixed tool name

#### Scenario: Requests that do not require the catalog do not trigger discovery
- **WHEN** the Agent performs the gateway `initialize` handshake, calls a metadata endpoint, requests an unsupported non-tools capability, or sends an unauthenticated MCP request
- **THEN** the gateway does not perform backend tool discovery

### Requirement: Keep the global catalog identity-independent
The aggregated catalog SHALL be global rather than keyed by user. Every backend aggregated by the gateway SHALL return the same tool names and schemas from `tools/list` for every valid user. User-specific RBAC and data authorization SHALL be enforced by the target backend when `tools/call` executes, not by filtering tool discovery.

#### Scenario: Different users observe the same tool definitions
- **WHEN** two valid users cause discovery or refresh with different delegated access tokens
- **THEN** the resulting tool names and schemas are identical for the same backend version
- **AND** no user identity is stored in the catalog or routing table

### Requirement: Authenticate every backend MCP request independently
The gateway SHALL send the current request's validated `MCP_ACCESS_TOKEN` on every backend `initialize`, `tools/list`, `tools/call`, and session-termination request. Backend identity SHALL be evaluated from that current token, never inherited from an earlier request, MCP session, or pooled HTTP connection. Stateless Streamable HTTP is preferred. If a backend SDK requires a session, discovery SHALL use a temporary `initialize` + `tools/list` session that is closed after use, and an identity-bound session SHALL NOT be shared across users.

#### Scenario: Pooled transport does not pool identity
- **WHEN** HTTP connections are reused for requests from two different users
- **THEN** each backend request carries and authenticates its own current Bearer token
- **AND** no authentication context is inherited from the connection

#### Scenario: Discovery session does not become an execution identity
- **WHEN** discovery is initialized with user A's token and a later tool call arrives with user B's token
- **THEN** the tool call is authorized only as user B
- **AND** user A's discovery identity or MCP session is not reused as authentication

### Requirement: Refresh a stale catalog on authenticated demand
The gateway SHALL cache the aggregated catalog and routing table. The freshness TTL SHALL default to 10 minutes and SHALL act as a request-driven freshness threshold rather than a background schedule. After the TTL expires, the next authenticated `tools/list` or `tools/call` SHALL refresh the backends using that request's validated access token. The gateway SHALL NOT retain an end-user token or run token-dependent background refreshes.

#### Scenario: A catalog-dependent request refreshes a stale catalog
- **WHEN** the catalog freshness TTL has expired and an authenticated Agent calls `tools/list` or `tools/call`
- **THEN** the gateway refreshes backend tools using that request's validated access token
- **AND** added or removed downstream tools are reflected after a successful refresh
- **AND** the token is not retained after the refresh completes

#### Scenario: Expiry alone does not run a background refresh
- **WHEN** the freshness TTL expires and no catalog-dependent request arrives
- **THEN** the gateway does not contact any backend

### Requirement: Serialize discovery and publish an atomic snapshot
Concurrent requests that encounter an uninitialized or stale catalog SHALL share one in-flight discovery or refresh operation. The gateway SHALL publish the catalog and routing table atomically as one consistent snapshot.

#### Scenario: Concurrent first requests share one discovery
- **WHEN** multiple authenticated catalog-dependent requests arrive while the catalog is uninitialized
- **THEN** only one backend discovery operation is started
- **AND** all waiting requests observe the same completed snapshot

#### Scenario: Callers never observe mismatched catalog and routes
- **WHEN** a discovery or refresh publishes an updated snapshot
- **THEN** a caller cannot observe a tool in `tools/list` without its corresponding route, or a route from a different snapshot

### Requirement: Degrade gracefully when a backend is unavailable
During initial discovery, if at least one backend succeeds, the gateway SHALL expose a partial catalog containing those successful backends. If every backend fails, the gateway SHALL return a `catalog_unavailable` MCP error and leave the catalog uninitialized. During a later refresh, a backend that fails SHALL retain its last-known-good tools and routes while successful backends are updated. If every backend refresh fails and a prior snapshot exists, the triggering request SHALL succeed using that snapshot. A failed discovery/refresh SHALL set a default 30-second failure backoff; requests during backoff SHALL not retry discovery. A `tools/call` routed to an unavailable backend SHALL fail only that call.

#### Scenario: A down backend does not break the whole catalog
- **WHEN** one backend is unreachable during initial discovery and another is reachable
- **THEN** `tools/list` returns the reachable backend's tools
- **AND** the unavailable backend's tools are absent because no last-known-good definition exists

#### Scenario: All backends fail during initial discovery
- **WHEN** every configured backend fails during initial discovery
- **THEN** the gateway returns an MCP error rather than a successful empty catalog
- **AND** the catalog remains uninitialized so a later request can retry
- **AND** another discovery is not attempted before the failure backoff expires

#### Scenario: Refresh failure retains last-known-good tools
- **WHEN** a backend previously contributed tools but fails during a later refresh
- **THEN** its last-known-good tools and routes remain in the published snapshot
- **AND** successful backends are updated normally

#### Scenario: Total refresh failure serves the existing snapshot
- **WHEN** every backend fails during refresh and a previously published snapshot exists
- **THEN** the triggering `tools/list` succeeds with the last-known-good snapshot, or the triggering `tools/call` uses its last-known-good route
- **AND** the last-successful-refresh timestamp is not advanced
- **AND** no further refresh is attempted before the failure backoff expires

#### Scenario: A call to a down backend fails only that call
- **WHEN** the Agent calls a tool whose owning backend is currently unavailable
- **THEN** only that `tools/call` returns an error
- **AND** calls to tools on reachable backends continue to succeed

### Requirement: Advertise only the tools capability in v1
The gateway SHALL advertise **only** the `tools` capability to the Agent during the `initialize` handshake. The gateway SHALL NOT advertise, aggregate, or route the `resources`, `prompts`, `logging`, or `completions` capabilities in this version, even when a downstream backend supports them.

#### Scenario: Only tools is advertised at handshake
- **WHEN** the Agent performs `initialize` against the gateway
- **THEN** the gateway's advertised capabilities include `tools`
- **AND** they do not include `resources`, `prompts`, `logging`, or `completions`

#### Scenario: A non-tools capability request is not routed downstream
- **WHEN** the Agent requests a non-`tools` capability (e.g., `resources/list` or `prompts/list`)
- **THEN** the gateway does not aggregate or forward it to any backend
