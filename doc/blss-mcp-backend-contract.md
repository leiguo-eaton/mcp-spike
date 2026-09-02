# BLSS MCP Backend Contract

> Cross-repository contract for the BLSS MCP server implementation. Framework details must not
> weaken these externally observable requirements.
>
> **Implementation status:** BLSS MCP is being built **in-process inside master**
> (`bldc-blss-master-service`, OpenSpec change `mcp-blss-server`). The endpoint, the transport and
> per-request authentication are done; the business tools are not. Where this contract has already
> been satisfied, the note says so — but the requirement, not the current implementation, is what
> binds.

## 1. Role and Transport

BLSS MCP is the first required backend behind `mcp-spike`. It exposes MCP over **stateless
Streamable HTTP** on a single endpoint and supports `initialize`, `tools/list`, and `tools/call`. It
does not add the `blss__` prefix itself; the Gateway owns external namespacing.

The Gateway's MCP client speaks Streamable HTTP **only**. The older HTTP+SSE two-endpoint transport
(a long-lived `GET /sse` plus `POST /message?sessionId=…`) is **not** interoperable with it — this is
a hard compatibility boundary, not a preference.

Stateless operation is required. If a future implementation ever needs `Mcp-Session-Id`, that
session may carry protocol state only and must not be an authentication or user-identity source.

> *Implemented as:* MCP Java SDK 2.0.0 `HttpServletStatelessServerTransport`, registered at `/mcp`
> in `web.xml`. No MCP session is issued; `GET` returns `405`.

### Wire expectations the Gateway relies on

| Aspect | Expectation |
|--------|-------------|
| Endpoint | One path, all methods over `POST` |
| Request header | The Gateway sends `Accept: application/json, text/event-stream` on every POST |
| Response | `application/json`, or an SSE stream whose `data:` lines carry the JSON-RPC message — the Gateway parses both |
| Session | None expected. If a `Mcp-Session-Id` response header appears, the Gateway will echo it and `DELETE` the session when it is finished |
| Handshake | The Gateway performs `initialize` + `notifications/initialized` before each discovery or tool call, then closes any session it was given |

> Note the last row: because the Gateway does not hold sessions, a single `tools/call` costs three
> POSTs (`initialize`, `notifications/initialized`, `tools/call`). This is acceptable for an
> in-process backend on loopback; it is worth knowing before optimising elsewhere.

## 2. Authentication Contract

Every MCP HTTP request independently requires:

```http
Authorization: Bearer <MCP_ACCESS_TOKEN>
```

This includes `initialize`, `tools/list`, `tools/call`, and session termination when supported. BLSS
MCP validates on every request:

- signature using master JWKS;
- `iss` equals the configured master issuer;
- `aud` contains the shared Gateway MCP resource;
- `exp` and `nbf`, with allowed clock skew;
- `sub` is present and resolves as required for tool execution.

Missing or invalid tokens produce `401`. HTTP connections may be pooled, but authentication context
must never be inherited from a connection or a previous request.

> *Implemented as:* authentication runs in the servlet that fronts the MCP transport, **before** the
> MCP layer sees the request, so it applies uniformly to `initialize`, `tools/list` and
> `tools/call`. A rejection returns `401` with `WWW-Authenticate: Bearer` and no detail in the body.
> (It cannot live in the SDK's `contextExtractor`: the SDK invokes that outside any try/catch, so a
> rejection raised there would surface as a container error page rather than a `401`.)

## 3. Session and Identity Isolation

BLSS MCP must not bind the identity seen during `initialize` to later requests. The current Bearer
token is always authoritative.

```text
initialize(token A) -> protocol state only
tools/call(token B) -> execute only as user B
```

An identity-bound MCP session must never be shared across users. The Gateway is expected to use a
temporary `initialize` -> `tools/list` -> close session for discovery if sessions are required.

> *Implemented as:* identity is resolved per HTTP request and passed to each tool handler as a
> method argument via the MCP transport context — it is never stored in a field, a session, or a
> `ThreadLocal` that could outlive the request.
>
> **A specific trap in master:** `UserContextSupport.getId()` returns `1` (administrator) when no
> `Authentication` is present, and the MCP path deliberately does not run the servlet security chain
> that would populate one. A tool reaching for it would execute as admin for every caller. MCP code
> must take identity only from the MCP call context; a source-scanning test enforces this.

## 4. Catalog Contract

`tools/list` is authenticated but identity-independent:

- every valid user sees the same tool names and definitions for a given deployment version;
- tools are not hidden based on user, role, tenant, device scope, or data permission;
- user-specific authorization is deferred to `tools/call`;
- BLSS MCP returns native names such as `query_asset`, never `blss__query_asset`;
- returned definitions are valid for the negotiated MCP version.

User-specific devices/assets/datasets are represented as tool results or valid business arguments,
not by changing the available tool inventory.

## 5. Tool Execution and RBAC

For every `tools/call`, BLSS MCP derives the user from the current token's `sub`, builds the BLSS user
context, and applies existing business/data authorization. Identity fields such as `sub` or
`userId` are not accepted as LLM-controlled tool arguments.

A valid token without permission to perform the requested business operation produces a stable
authorization failure. The Gateway returns that failure to the Agent without changing identity or
retrying as another user.

## 6. Tool Definition Quality

Each tool definition must provide a valid name and schema. The Gateway preserves every supported
field except the externally prefixed name, including:

- description and title;
- `inputSchema` and `outputSchema` when supported;
- annotations;
- negotiated extension fields.

The Gateway **skips an individual invalid tool** — a name outside `[a-zA-Z0-9_-]+`, or a missing or
non-object `inputSchema` — and still publishes the backend's remaining valid tools. The skipped tool
is logged and simply does not appear in the aggregated catalog; calling it yields `unknown_tool`.
One bad tool therefore does not take the whole `blss__` namespace offline.

The Gateway fails BLSS discovery **as a unit** only when the `tools/list` response is structurally
unusable (no `tools` field, or `tools` is not an array).

Because a skipped tool disappears silently from the Agent's view, BLSS MCP must still validate its
own catalog at startup or in contract tests — Gateway-side validation is a safety net, not the
primary check.

> *Implemented as:* a startup check over the published catalog (name grammar, no `blss__` prefix,
> `inputSchema` of `"type": "object"`), which fails the server rather than shipping a broken tool.

**Declare `"additionalProperties": false`** on tool input schemas. The MCP SDK validates `tools/call`
arguments against the declared schema before invoking the handler, so this turns an attempt to
smuggle in identity-shaped arguments (`userId`, `sub`) into a hard rejection instead of something
each handler must remember to ignore.

## 7. Errors and Availability

BLSS MCP should distinguish:

- invalid/missing token (`401`);
- authenticated user without permission;
- unknown native tool;
- invalid arguments;
- internal/backend dependency unavailable;
- tool execution failure.

Errors must not expose access tokens, internal credentials, stack traces, or sensitive backend URLs.
The Gateway defines its own routing/catalog categories and may map backend transport errors to
`backend_unavailable`.

## 8. Logging and Audit Inputs

BLSS MCP must never log raw `USER_TOKEN` or `MCP_ACCESS_TOKEN` values. It should propagate the
Gateway correlation/trace ID and make the following non-sensitive values available for audit:

- `sub` or resolved BLSS user ID;
- native tool name;
- outcome category;
- duration;
- backend/business error category.

Raw tool arguments and results should not be audited by default because they may contain business
or personal data.

## 9. Contract Verification

The BLSS MCP repository should include tests proving:

1. Every MCP request independently validates its Bearer token.
2. A token from user B cannot inherit user A's initialized session identity.
3. `tools/list` is identical for two valid users with different RBAC.
4. `tools/call` applies each current user's RBAC and data scope.
5. Native tool names are unprefixed (`query_asset`, never `blss__query_asset`), match
   `[a-zA-Z0-9_-]+`, and every tool carries an object `inputSchema`.
6. Invalid/expired/wrong-audience tokens are rejected.
7. Raw tokens never appear in logs or errors.
8. Streamable HTTP session creation and termination, if required, do not leak identity or resources.

> *Status:* 1, 2, 6, 7 and 8 are covered. 3, 4 and 5 need real tools before they can be meaningful —
> only a `whoami` diagnostic tool exists so far.

## 10. Token Lifetime Caveat

Master's signing key is generated **in memory at startup** and is not persisted (single node). A
master restart therefore invalidates every outstanding `MCP_ACCESS_TOKEN`, and BLSS MCP will reject
tokens it accepted moments earlier. This is expected: clients recover by re-running the OBO exchange.
Do not treat a burst of `401`s after a restart as a BLSS MCP fault.

## 11. Deferred Superset Contract

This contract does not assert that Superset MCP already supports master-issued tokens. Superset is a
later integration and must satisfy an equivalent authentication, identity-independent catalog, and
per-request authorization contract before being added to the static Gateway registry.
