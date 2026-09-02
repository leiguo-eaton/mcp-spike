# MCP OBO 平台 — 跨 Epic 接口契约（并行开发前提）

> 目的：冻结三个 Epic（E1 Gateway / E2 Master AS+REST / E3 BLSS MCP）之间的**接口契约**，
> 消除**编译期依赖**，使三线开发可并行（各用 stub / 自签测试 token 解耦）。
> 真相来源：`gateway-obo-token-exchange`、`mcp-obo-token-issuance`、`mcp-blss-server` 三个 change 的 spec/design。
> 本文不引入新行为，仅把散落在 spec/design 的跨 Epic 约定汇总为可冻结的契约。
>
> **标识符示例值仍为 proposed（pending team review）**：`issuer=https://auth.blss.local`、`<mcp-resource>=https://mcp.blss.local`。

## 契约总览

| # | 契约 | 生产方 | 消费方 | 来源 |
|---|------|--------|--------|------|
| C1 | Token claims 与验签 | E2 | E1, E3 | mcp-token-exchange, gateway-token-validation, mcp-rest-bearer, mcp-blss-server |
| C2 | JWKS / AS metadata 端点 | E2 | E1, E3 | mcp-token-exchange (JWKS/RFC8414), gateway-token-validation (RFC9728) |
| C3 | MCP 协议 + 前缀命名 | E1 ↔ E3 | E1, E3 | mcp-tool-aggregation, mcp-blss-server |
| C4 | 身份透传（Bearer 方案 A） | E1 | E3 | obo-identity-passthrough, gateway design D8, mcp-blss-server BD3/BD4 |

---

## C1 — Token claims 与验签契约

**生产方 E2**：签发 `MCP_ACCESS_TOKEN`（token-exchange）与 `MCP_REFRESH_TOKEN`（refresh）。
**消费方 E1**（gateway 验签）、**E3**（BLSS MCP 端点验签）、**E2.B**（`/rest` bearer 验签）。

**`MCP_ACCESS_TOKEN`（JWT）claims**（来源：mcp-token-exchange「Exchange USER_TOKEN…」）：

| claim | 值 / 约束 |
|-------|-----------|
| `iss` | 配置 issuer（示例 `https://auth.blss.local`） |
| `aud` | 含 `<mcp-resource>`（示例 `https://mcp.blss.local`）— 所有 backend 共享（方案 A） |
| `sub` | 真实用户（从 `USER_TOKEN` 保留） |
| `exp` | 约 30 分钟 |
| 签名 | 由 master 单一签名密钥（RS256）签，公钥经 JWKS 发布 |

**`USER_TOKEN`（JWT）claims**：一个 master 登录 session 对应一个固定 token；`sub`=当前登录用户且不可由请求指定，`token_use=mcp_user`，`aud`=`<agent-resource>`，`sid`=当前 session，`exp` 不晚于 session。浏览器直接发送给 Agent；Agent完整验签。Token exchange 还必须确认 `sid` session 仍有效。Logout 阻止后续 exchange，已签发 MCP access token 自然过期。

**验签规则（三处消费方一致）**：校验签名（master JWKS）、`iss`=配置 issuer、`aud`⊇`<mcp-resource>`、`exp`/`nbf`（含小幅 clock skew）。

**解耦方式（并行开发）**：E1/E3 用**符合本契约的自签测试 token**（本地测试密钥）开发验签逻辑，不必等 E2 跑起来；集成期换成 E2 真实签发的 token。

---

## C2 — JWKS / AS metadata 端点契约

**生产方 E2**。**消费方 E1**（发现元数据 + 验签配置）、**E3**（验签配置）。

**E2 暴露**（来源：mcp-token-exchange「Publish JWKS and authorization-server metadata」）：
- `jwks_uri`：返回验证 `USER_TOKEN` / `MCP_ACCESS_TOKEN` 的公钥集（含 `kid`）。
- `/.well-known/oauth-authorization-server`（RFC 8414）：列出 token endpoint、`jwks_uri`、支持的 grant（token-exchange + refresh）。
- Token endpoint 以 `USER_TOKEN` 为唯一 exchange 凭据，无额外 Agent client authentication；metadata 声明 `token_endpoint_auth_methods_supported=["none"]`。V1 不声明/请求 OAuth scope。

**E1 额外暴露给 Agent**（来源：gateway-token-validation「Challenge unauthenticated…」，RFC 9728）：
- `401` + `WWW-Authenticate: Bearer resource_metadata="…"`。
- `/.well-known/oauth-protected-resource`：`authorization_servers` 指向 E2（master），`resource`=`<mcp-resource>`。

**解耦方式**：端点响应结构一冻结，E1 的 metadata 控制器、E3 的 `JwtDecoder` 配置即可各自按形状开发；E1/E3 本地用 stub JWKS（测试公钥）验签。

---

## C3 — MCP 协议 + 前缀命名契约

**E1 ↔ E3**（也适用于 Superset MCP 等其它 backend）。MCP 为标准协议，前缀为本平台约定。

**MCP 方法形状**（标准）：`initialize`、`tools/list`、`tools/call`。

**能力范围**（来源：mcp-tool-aggregation「Advertise only the tools capability in v1」+ mcp-blss-server BD6）：`initialize` 仅广播 `tools`；不含 `resources`/`prompts`/`logging`/`completions`。

**前缀命名 + 路由**（来源：mcp-tool-aggregation「Aggregate…」「Route…」+ gateway design D9）：
- Gateway 对外暴露名 = `<backend-prefix>__<原始工具名>`，分隔符为**双下划线** `__`（不可用 `.`，MCP tool name 仅允许 `[a-zA-Z0-9_-]`）。
- 前缀唯一标识 backend，兼作路由键：`blss__query_asset` → BLSS MCP（E3）、`superset__run_sql` → Superset MCP。
- Gateway 路由时剥前缀还原为 backend 原生名（`blss__query_asset` → `query_asset`）转发给 E3；E3 的 `tools/list` 返回**未加前缀**的原生名，前缀由 gateway 施加。

**约定的 backend prefix**：`blss`（E3）、`superset`（Superset MCP）。

**懒加载 + catalog 契约**（来源：gateway design D10/D11）：
- Gateway 启动时不访问 backend。首个需要 catalog 的已认证 `tools/list` 或 `tools/call` 使用该请求的 `MCP_ACCESS_TOKEN` 调 backend `initialize` + `tools/list`。
- Backend 的 `tools/list` 对所有合法用户返回相同工具名与 schema；用户 RBAC 只在 `tools/call` 执行时生效。
- freshness TTL 到期不自动联网；下一个需要 catalog 的已认证请求触发刷新。Gateway 不保留用户 token。
- 并发加载 single-flight，catalog + 路由表原子发布。
- 首次部分成功发布 partial catalog；首次全部失败返回 MCP error；后续刷新失败保留 backend 的 last-known-good 定义。
- Freshness TTL 默认 10 分钟；失败 backoff 默认 30 秒。已有 snapshot 时全量 refresh 失败仍成功返回 last-known-good，且不推进 last-successful 时间。

**BLSS MCP backend 契约**：详见 [blss-mcp-backend-contract.md](blss-mcp-backend-contract.md)。V1 静态 registry 先只要求 `blss`；Superset 在认证/用户映射明确后另行接入。

**解耦方式**：E1 按标准 MCP + 前缀规则实现聚合/路由，E3 按标准 MCP 实现端点/工具，双方各按协议独立开发，用 mock backend / mock gateway 自测。

---

## C4 — 身份透传契约（Bearer，方案 A）

**E1 → E3**（同理 E1 → 任意 backend）。

**透传规则**（来源：obo-identity-passthrough「Forward the delegated user identity」+ gateway design D4/D8 + mcp-blss-server BD3/BD4）：
- Gateway 校验入站 `MCP_ACCESS_TOKEN` 后，**原样**以 `Authorization: Bearer <同一个 MCP_ACCESS_TOKEN>` 转发给目标 backend（不做二次 token exchange，方案 A）。
- Gateway 不发送任何静态 / 共享凭证；无委托身份时不回退到共享凭证。
- Backend（E3）**独立再验签**同一个 token（C1 规则），从 `sub` 解析 `userId` 建立 MCP 调用上下文；**身份只来自 token，绝不来自工具入参**。
- Token 在日志中掩码。

**信任边界**（来源：gateway design D7 + mcp-obo-token-issuance D5/D6）：gateway 与 master 同容器 loopback、无 mTLS 时，backend 仍须独立验签后才信任 `sub`——loopback 非信任边界。

**解耦方式**：E3 只需假设「入站带一个符合 C1 的合法 `Bearer`」，用自签测试 token 直接打端点开发，不关心 E1 如何获得/转发。

---

## 冻结这些契约后的并行结论

- **E2** 完全独立（token 源头），可立即开工。
- **E1** 用 C1 自签 token + C2 stub JWKS 开发验签/metadata/聚合/路由/降级。
- **E3** 用 C1 自签 token + C4 假设开发 facade/身份/工具；但 **tasks 第 1 节 spike（WAR 手动装配）须最先做**（硬串行、全局最大技术风险）。
- **集成期**（运行时依赖，冻结消除不了）：`E2 发真 token → E1 验签路由 → E3 执行 tool` 合流；用契约测试压缩串行窗口。

## 待定 / 需评审

- `issuer` / `<mcp-resource>` 示例值 pending team review（影响 C1/C2）。
- catalog freshness TTL、backend registry 配置形态（E1 侧，不影响协议形状）。
- `MCP_ACCESS_TOKEN` 是否加 `scope`（v2；若加会扩展 C1，v1 不影响）。
