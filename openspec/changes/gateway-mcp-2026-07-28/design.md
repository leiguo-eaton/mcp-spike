## Context

The MCP gateway implements protocol revision `2025-06-18`: an `initialize` handshake, `ping`, a
GET endpoint answering `405`, and `DELETE` answering `204`. Revision `2026-07-28` — the **current**
revision — restructures the protocol:

| Concern | `2025-06-18` (what we do) | `2026-07-28` |
|---|---|---|
| Version negotiation | `initialize` handshake | Per-request `_meta` + `MCP-Protocol-Version` header |
| Sessions | `Mcp-Session-Id`, `DELETE` to end | Removed |
| Server identity/capabilities | `initialize` result | `server/discover` (mandatory) and per-result `_meta` |
| Request headers | `MCP-Protocol-Version` only | Also `Mcp-Method`, and `Mcp-Name` for `tools/call` |
| Unknown method | JSON-RPC error in `200` | `404` + `-32601` |
| Header/body mismatch | — | `400` + `-32020` |
| Unsupported version | — | `400` + `-32022` listing supported versions |
| Result envelope | plain result | `resultType: "complete"` required |
| List freshness | — | `ttlMs` + `cacheScope` required on `tools/list` |
| `ping` | present | removed |
| Server→client requests | on SSE streams | MRTR `InputRequiredResult` |
| Change notifications | GET stream | `subscriptions/listen` |

Two facts decide the shape of this change:

1. **No Java MCP SDK implements `2026-07-28`.** `mcp-core:2.0.1` (2026-08-19, three weeks after the
   revision) declares `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`. BLSS MCP is built on it
   and therefore cannot be modern by upgrading.
2. **Our gateway does not use an SDK on either side.** Both the `/mcp` server and the backend client
   are hand-written JSON-RPC. Nothing prevents the gateway from implementing the new revision.

The specification names three implementation eras — *modern* (per-request metadata, `2026-07-28`+),
*legacy* (`initialize` handshake, `2025-11-25` and earlier), and *dual-era* — and publishes a
compatibility matrix. Two rows matter here:

- **Dual-era client → Legacy server: Works.** (gateway → BLSS MCP)
- **Modern or Legacy client → Dual-era server: Works.** (Agent → gateway)

## Goals / Non-Goals

**Goals:**
- Serve `2026-07-28` and the existing legacy revisions from the same `/mcp` endpoint.
- Consume both eras from backends, detecting the era per backend origin.
- Keep BLSS MCP unchanged and unblocked.

**Non-Goals:**
- `subscriptions/listen` — the gateway pushes no notifications and advertises no `listChanged`.
- MRTR / `InputRequiredResult` — the gateway needs no client input; a backend that returned one
  would be relayed as an ordinary result and is out of scope until a backend does so.
- The Tasks extension.
- `x-mcp-header` parameter mirroring — see D19.
- Making BLSS MCP modern.

## Decisions

### D16 — The gateway is dual-era; the version boundary lives there
The gateway serves both eras and consumes both eras. BLSS MCP stays legacy.
- **Why here**: a gateway is already a protocol boundary — it re-publishes one catalog assembled from
  several backends. Absorbing a revision difference is the same kind of work. Putting it anywhere
  else would mean either blocking the Agent on a Java SDK release we do not control, or hand-rolling
  a modern server inside master and discarding the SDK's schema validation and transport handling.
- **Why not wait for the SDK**: the revision is current, the Agent is being written now, and the SDK
  had three weeks and did not ship it — the removal of `initialize` and sessions is a large change
  for them too. This is not a short wait.
- **Cost**: two dispatch paths in the gateway and an era probe in the backend client, both of which
  become deletable once every peer is modern.

### D17 — Era is detected from the request, not configured
A request is **modern** when it carries `_meta.io.modelcontextprotocol/protocolVersion`. An
`initialize` request selects **legacy**. This is the rule the specification gives for a dual-era
server, and it needs no configuration or per-client registration.
- **Consequence**: header validation, `resultType`, cache hints and the `404`/`400` status semantics
  apply **only** on the modern path. Applying them to legacy requests would break existing clients.
- **Alternatives**: keying off the `MCP-Protocol-Version` header alone — rejected: `2025-06-18` also
  defines that header, so it does not discriminate. A configured per-deployment era — rejected: the
  gateway may serve both kinds of client at once.

### D18 — Client-side era is probed once per backend origin and cached
The backend client sends a modern request first. On `400` it inspects the body: a recognized modern
error (`-32020`, `-32021`, `-32022`) means the backend is modern and the request should be corrected;
anything else means legacy, and the client falls back to `initialize`. The determination is cached
for the backend origin, as the specification requires, and re-probed if the cached assumption later
fails.
- **Why probe rather than configure**: the registry already carries only a prefix and a URL; adding
  an era field would be one more thing to get wrong at deployment, and the probe costs one request
  per origin per process.
- **Note**: against today's BLSS MCP the probe fails over immediately, so the steady state is one
  extra failed request per backend per gateway lifetime.

### D19 — `x-mcp-header` is deliberately deferred
`2026-07-28` requires modern **clients** to mirror tool parameters annotated with `x-mcp-header` into
`Mcp-Param-*` headers, and to reject tool definitions whose annotations are invalid.
- **Deferred because**: the annotation can only originate from a modern backend, and every backend we
  have is legacy. Implementing the mirroring now would be untestable against a real peer.
- **Risk accepted**: if a modern backend later publishes such a tool, calls to it would fail
  header validation at that backend. The catalog validation in D14 does not currently reject these
  definitions, so they would be published and then fail at call time.
- **Trigger to revisit**: the first modern backend added to the registry.

### D20 — `tools/list` cache hints come from the existing catalog TTL
The revision requires `ttlMs` and `cacheScope` on `tools/list`. The gateway already maintains a
catalog freshness TTL (10 minutes) for exactly this purpose, so `ttlMs` is the remaining lifetime of
the published snapshot and `cacheScope` is `"public"` — the catalog is global and identity-independent
(D10), which is precisely what `"public"` asserts.
- **Why this is more than a formality**: publishing `cacheScope: "private"` would be wrong and
  wasteful, and publishing a `ttlMs` unrelated to the snapshot would let a client cache past a
  refresh. Deriving both from the real snapshot keeps them honest.

### D21 — Origin validation is configurable and off by default
The revision requires servers to validate the `Origin` header and answer `403` when it is present and
invalid. The gateway validates only when an allow-list is configured, and only when the header is
present.
- **Why off by default**: the Agent is a server-side client and normally sends no `Origin`. An
  allow-list guessed at build time would reject legitimate deployments; an empty allow-list treated
  as "deny all" would break every deployment that does not configure it.
- **Why configurable at all**: the requirement exists to stop DNS-rebinding from browsers, which is a
  real risk once the gateway is exposed through the public reverse proxy.

## Risks / Trade-offs

- **Two code paths on the server** → a fix applied to one era and not the other. Mitigate: the eras
  share the tool-dispatch layer and differ only in envelope, validation and status mapping; tests
  run the same scenarios through both.
- **Silent legacy fallback masking a modern backend bug** → a modern backend returning a malformed
  `400` would be mistaken for legacy. Mitigate: only a body that is *not* a recognized modern error
  triggers fallback, and the fallback is logged at WARN with the backend prefix.
- **Header/body validation is strict by specification** → a well-meaning client that omits
  `Mcp-Method` gets a `400` rather than being served. This is required behavior, but it will be the
  most common integration failure; the error message names the missing header.
- **The revision may move again** → `2026-07-28` is Current, not Final, and may receive backwards
  compatible changes. Mitigate: supported versions are a list, and `server/discover` advertises them.

## Migration Plan

1. Server side first: era detection, modern dispatch, `server/discover`, header validation, status
   mapping. Legacy behavior is untouched, so nothing regresses for current clients.
2. Client side: modern-first with legacy fallback and per-origin caching. Against BLSS MCP this is
   exercised as a fallback path.
3. Documentation: the Agent guide gains the modern flow; the backend contract records that BLSS MCP
   stays legacy and that the gateway bridges.
4. **Rollback**: legacy dispatch is unchanged throughout, so reverting is removing the modern path.

## Open Questions

- Which protocol revisions to keep on the legacy path. Today the gateway accepts `2025-06-18` and
  `2025-03-26`; `2025-11-25` is unsupported and is what the Java SDK would negotiate to if asked.
- Whether to advertise `2025-11-25` support once the backend client can speak it, so that a future
  SDK-based backend negotiates the newest legacy revision rather than the oldest.
