# MCP OBO 平台 — 高层 User Stories

## 术语 / 环境标识符（proposed, pending team review）

| 标识符 | 值（示例，无异议即转生产） | 说明 |
|--------|--------------------------|------|
| `issuer`（master AS） | `https://auth.blss.local` | 签发 `USER_TOKEN` / `MCP_ACCESS_TOKEN`；托管 `jwks_uri` + RFC 8414 metadata |
| `<mcp-resource>`（gateway `aud`） | `https://mcp.blss.local` | `MCP_ACCESS_TOKEN.aud`；gateway 与所有 backend 共用（方案 A） |

> 两侧必须逐字一致：master AS 配置 = gateway `sidecar.issuer-uri` / `sidecar.mcp-resource`。

## 角色塌缩（现实拓扑）

- **master** 同时扮演 Core（签 `USER_TOKEN`）+ Authorization Server（RFC 8693 exchange）+ Backend REST（`/rest/**` 收 Bearer）+ 未来内置 BLSS MCP server。
- **mcp-spike** 是纯 **MCP Gateway**（Resource Server）：校验 token、聚合并路由多个 backend MCP 的工具、按 OBO 透传用户身份。
- **Superset MCP** 为平级后端；其认证同样依赖 master（单一 AS / 单一 JWKS）。

---

## Story 1 — master 成为 MCP 的身份源与授权服务器（AS + Token 签发）

**As** the BLSS platform,
**I want** master to mint a session-bound MCP `USER_TOKEN` and exchange it for `MCP_ACCESS_TOKEN`/`MCP_REFRESH_TOKEN`,
**so that** an already-authenticated user's identity can be delegated to MCP calls without a browser login.

**维度**：仓库 `bldc-blss-master-service` · change `mcp-obo-token-issuance`（能力 `mcp-user-token`、`mcp-token-exchange`）

**范围（来自 design D1/D2/D3 + tasks 1-3）**
- 引入 `spring-security-oauth2-authorization-server`（AS 角色从 mcp-spike 迁入）。
- 单一 master 签名密钥（RSA/EC），发布 `jwks_uri` + RFC 8414 metadata。
- 每个 master 登录 session 有一个固定 `USER_TOKEN`，独立于 OTT session-handoff；`sub` 来自当前 session，`aud=<agent-resource>`，带 `sid`，exchange 时检查 session 仍有效。
- RFC 8693 token exchange + refresh-token grant。

**Acceptance Criteria**
- [ ] AC1.1 已登录浏览器可为当前 master session mint 固定 `USER_TOKEN`：`sub` 强制来自当前用户，`token_use=mcp_user`，`aud=<agent-resource>`，带 `sid`，`exp` 不晚于 session；请求不能指定他人。
- [ ] AC1.2 `USER_TOKEN` 铸造走独立认证链，不触发 OTT session-handoff；同一 session 复用同一 token，不同 session 相互独立。
- [ ] AC1.3 token endpoint 接受 token-exchange grant：校验 `USER_TOKEN` 签名/`iss`/`aud`/`exp`/`token_use`，确认 `sid` session 仍有效，再读取 `sub`；不要求额外 Agent client authentication。
- [ ] AC1.4 交换产出 `MCP_ACCESS_TOKEN`（`aud=<mcp-resource>`、保留 `sub`、`exp`≈30min）+ `MCP_REFRESH_TOKEN`（`exp`≈8h）。
- [ ] AC1.5 支持 refresh-token grant，保留 `sub`/`aud`。
- [ ] AC1.6 `jwks_uri` 发布公钥；`/.well-known/oauth-authorization-server`（RFC 8414）返回 token endpoint、jwks_uri、支持的 grant（含 token-exchange + refresh）。
- [ ] AC1.7 签名密钥在启动时**内存生成**并进程内持有（单节点，不落库）；重启后旧 USER/access/refresh token 失效属可接受，浏览器在有效/新登录 session 下重新 mint。

**依赖 / 未决**：签名密钥单节点内存生成、浏览器直接 mint、同 session 固定 token、exchange 检查 `sid` 均已定；待定环境 `<agent-resource>` 与 mint endpoint 形状。

### 子 Story 拆分（S1 = 5 点 → 3 个各 2 点，可并行/独立验证）

拆分目的：把 R1（遗留 XML 安全配置 vs Spring Authorization Server）与 R2（mint 链隔离）的风险局部化，各子 story 可独立交付与验证。

#### S1a — 内存签名密钥 + JWKS + AS metadata（2 点）
**As** the platform, **I want** master to hold an in-memory RSA signing key and publish `jwks_uri` + RFC 8414 metadata, **so that** the gateway and REST can verify tokens offline against one key.
- 对应 tasks 1.1–1.4；design D2/D3。
- [ ] AC1a.1 启动时内存生成 RSA 密钥并进程内持有（单节点，不落库），带稳定 `kid`。
- [ ] AC1a.2 `jwks_uri` 发布公钥（含 `kid`）。
- [ ] AC1a.3 `/.well-known/oauth-authorization-server`（RFC 8414）返回 token endpoint、`jwks_uri`、支持的 grant（token-exchange + refresh）。
- [ ] AC1a.4 引入 `spring-security-oauth2-authorization-server`，且未接入任何 chain 前 `jwks_uri`/metadata 可解析（验证 R1）。

#### S1b — USER_TOKEN mint（浏览器 + session 绑定）（2 点）
**As** an authenticated browser user, **I want** to mint one MCP `USER_TOKEN` for my current login session, **so that** I can call the Agent directly and let it perform OBO.
- 对应 tasks 2.1–2.4；design D1。
- [ ] AC1b.1 已登录浏览器可 mint；`sub` 强制=当前 principal，`sid`=当前 session，不接受调用方身份字段。
- [ ] AC1b.2 `USER_TOKEN` 为 session-bound JWT：`token_use=mcp_user`、`aud=<agent-resource>`、`exp<=session expiry`，同一 session 复用同一 token。
- [ ] AC1b.3 mint 走独立认证链，不触发 OTT session-handoff；浏览器将 token 直接作为 Bearer 调 Agent。

#### S1c — RFC 8693 token-exchange + refresh grant（2 点）
**As** the Agent, **I want** to exchange a `USER_TOKEN` for an `MCP_ACCESS_TOKEN` (+refresh) and refresh it, **so that** I hold a resource-bound token for the gateway.
- 对应 tasks 3.1–3.4；design D2。
- [ ] AC1c.1 token endpoint 接受 token-exchange grant：校验 `USER_TOKEN` 签名/`iss`/Agent audience/`exp`/`token_use` 和 `sid` session，读取 `sub`；`USER_TOKEN` 是唯一凭据。
- [ ] AC1c.2 产出 `MCP_ACCESS_TOKEN`（`aud=<mcp-resource>`、保留 `sub`、`exp`≈30min）+ `MCP_REFRESH_TOKEN`（`exp`≈8h）。
- [ ] AC1c.3 支持 refresh-token grant，保留 `sub`/`aud`。

> 原 AC1.1–AC1.7 映射：AC1.1/1.2→S1b；AC1.3/1.4/1.5→S1c；AC1.6/1.7→S1a。

---

## Story 2 — master REST 接受委托身份（Bearer，附加于 Basic）

**As** the MCP gateway **and** customer third-party software,
**I want** `/rest/**` to also accept a `Bearer` JWT and rebuild the real user's `UserContext`,
**so that** delegated (OBO) calls and external token-based clients are honored while existing Basic auth is unchanged.

**维度**：仓库 `bldc-blss-master-service` · change `mcp-obo-token-issuance`（能力 `mcp-rest-bearer`）

**范围（来自 design D4/D5/D6 + tasks 4）**
- `/rest/**` 新增 bearer 路径，仅在 `Authorization: Bearer` 时启用，否则回落到 HTTP Basic。
- 校验 token（master JWKS 验签、`iss`、`aud`⊇`<mcp-resource>`、`exp`），从 `sub` 重建 `UserContext`/authorities，复用现有用户加载服务使 RBAC 与 Basic 路径一致。
- 保留 `/rest Bearer` 作为独立一等能力（客户第三方软件也会使用），非仅供 MCP。

**Acceptance Criteria**
- [ ] AC2.1 当请求带合法 `Bearer` JWT（正确 `iss`/`aud`/未过期/验签通过）时，`/rest/**` 认证成功并重建正确的 `UserContext` 与 RBAC。
- [ ] AC2.2 无 bearer token 时，HTTP Basic 与今日完全一致地工作（precedence + fall-through）。
- [ ] AC2.3 过期、错误 `aud`、验签失败或未知 `sub` 的 token 被拒（`401`/`403`）。
- [ ] AC2.4 即便 gateway 与 master REST 同容器 loopback、无 mTLS，master REST 仍独立验签后才信任 `sub`（loopback 非信任边界）。

**依赖 / 未决**：无（能力自洽）；与 Story 4 的关系见 D7（进程内 BLSS MCP 不经由 `/rest Bearer`）。

---

## Story 3 — mcp-spike 转为纯 MCP Gateway（Resource Server + OBO 透传）

**As** the Agent Service (public MCP client),
**I want** the gateway to validate externally-issued `MCP_ACCESS_TOKEN`s, advertise master for discovery, and forward my delegated identity downstream,
**so that** I can reach MCP tools on behalf of the real user without the gateway being an Authorization Server.

**维度**：仓库 `mcp-spike` · change `gateway-obo-token-exchange`（能力 `gateway-token-validation`、`obo-identity-passthrough`）— **多数已实现**

**范围（来自 design D1-D7 + tasks 1-6）**
- 移除 mcp-spike 的 AS 角色（`/login`、PKCE、DCR、consent、本地 JWK、seeded clients、H2/AS schema）。
- 单一无状态 resource-server chain 校验外部 token（AS `jwks_uri` 验签、`iss`、`aud`=`<mcp-resource>`、`exp`/`nbf` 含 clock skew）。
- `401` + RFC 9728 protected-resource metadata 指向**外部 AS（master）**。
- Leg B 从固定 `blss_token` Basic 改为按用户身份透传：把校验后的 token 作为 `Authorization: Bearer` 转发；token 在日志中掩码。

**Acceptance Criteria**
- [ ] AC3.1 无 `Authorization` 的 `POST /mcp` 返回 `401` + `WWW-Authenticate: Bearer resource_metadata="…"`。
- [ ] AC3.2 `/.well-known/oauth-protected-resource` 的 `authorization_servers` 指向 master，`resource`=`<mcp-resource>`。
- [ ] AC3.3 合法 token（`iss`=配置 issuer、`aud`⊇`<mcp-resource>`、未过期、验签通过）被接受并处理 MCP 调用。
- [ ] AC3.4 `aud` 不含 `<mcp-resource>`、过期、或验签失败的 token 返回 `401`。
- [ ] AC3.5 gateway 不签发自有 token，也不暴露任何 authorize/token/register/login/consent 端点。
- [ ] AC3.6 触发下游调用时，出站请求携带 `Authorization: Bearer <校验后的 token>`，绝不发送静态 `blss_token`/`Basic`。
- [ ] AC3.7 无委托身份时，不回退到共享/配置凭证。
- [ ] AC3.8 日志中 token 仅以掩码形式出现，绝不写出完整值。
- [ ] AC3.9 端到端：针对签发 `MCP_ACCESS_TOKEN` 的 master 走通 Agent 流程（tasks 6.3，依赖 Story 1，当前 blocked）。

**依赖 / 未决**：AC3.9 依赖 Story 1 的 master AS 环境；环境 `issuer`/`<mcp-resource>` 需与 master 对齐。

---

## Story 4 — mcp-spike 作为多后端 MCP Gateway（工具聚合与路由）

**As** the Agent,
**I want** a single unified MCP endpoint that aggregates tools from multiple backend MCP servers and routes my calls to the right one,
**so that** I see "one MCP" even though BLSS MCP and Superset MCP are separate backends.

**维度**：功能 · 仓库 `mcp-spike` · change `gateway-obo-token-exchange`（能力 `mcp-tool-aggregation`，tasks 7）— **未实现**

**范围（来自 design D8-D12 + specs/mcp-tool-aggregation + tasks 7）**
- 单一共享 audience（方案 A）：所有 backend 共用 `<mcp-resource>`；gateway 原样透传同一 token；backend 每请求再验签并执行 RBAC。V1 不使用 OAuth scope。
- 命名空间：下游工具以双下划线前缀暴露（`blss__query_asset`、`superset__run_sql`）；前缀兼作路由键（MCP tool name 不允许 `.`）。
- 发现：启动时不访问 backend；首个需要 catalog 的已认证 `tools/list` 或 `tools/call` 使用当前请求 token 懒加载 `initialize`+`tools/list`。目录全局共享且与用户身份无关。
- 刷新：刷新间隔是 freshness TTL；过期后由下一个需要 catalog 的已认证请求触发，不保留用户 token、不做依赖 token 的后台刷新；并发请求 single-flight，目录+路由表原子发布。
- 降级：首次部分成功则发布 partial catalog，首次全部失败返回 MCP error；后续刷新失败保留该 backend 的 last-known-good 工具；调用仅失败当前不可用的 backend。
- v1 仅聚合 `tools` capability（BLSS MCP 现只有 tools）；不暴露/不路由 `resources`/`prompts`/`logging`/`completions`。

**Acceptance Criteria**
- [ ] AC4.1 `tools/list` 返回所有可达 backend 的工具，每个重命名为 `<backend-prefix>__<原始名>`；同名工具因前缀不冲突。
- [ ] AC4.2 暴露名仅含合法字符（`[a-zA-Z0-9_-]`），使用 `__` 分隔，绝不含 `.`。
- [ ] AC4.3 `tools/call name=superset__run_sql` 被路由到 Superset backend，剥前缀还原为 `run_sql`，携带同一 `MCP_ACCESS_TOKEN`（OBO 透传），结果原样返回。
- [ ] AC4.4 未知前缀的 `tools/call` 返回错误且不转发任何 backend。
- [ ] AC4.5 gateway 启动时不访问 backend；首个已认证 `tools/list` 或 catalog 尚不存在时的 `tools/call` 使用当前请求 token 完成懒加载，`initialize`/metadata/未认证及非 tools 请求不触发发现。
- [ ] AC4.6 catalog 对所有合法用户返回相同工具定义；用户身份不存入 catalog/路由表，RBAC 只在 backend 执行 `tools/call` 时生效。
- [ ] AC4.7 freshness TTL 到期后，由下一个已认证 `tools/list`/`tools/call` 触发刷新；gateway 不保存用户 token、不执行依赖 token 的后台刷新。
- [ ] AC4.8 并发初始化/刷新共享一次 in-flight 操作，catalog 与路由表作为同一 snapshot 原子发布。
- [ ] AC4.9 首次发现部分成功时返回 partial catalog；全部失败时返回 MCP error 并保持未初始化；后续刷新失败保留 last-known-good 工具和路由。
- [ ] AC4.10 调用当前不可达 backend 的工具仅该调用失败，其余 backend 的调用继续成功。
- [ ] AC4.11 `initialize` 握手仅声明 `tools` capability，不含 `resources`/`prompts`/`logging`/`completions`；非 `tools` 请求不聚合/不下发。

**依赖 / 未决**：静态 registry、10m TTL、30s failure backoff、BLSS-first 已定；Superset 认证/用户映射未知，后置集成；`list_changed` 仅为未来可选项。

---

## Story 5 — master 内置 BLSS MCP 端点（`@Tool` facade + 委托身份）

**As** the platform,
**I want** BLSS MCP hosted in-process inside master, exposing tools over a dedicated facade that reuses the service layer with identity taken from the validated token,
**so that** BLSS business capabilities are available as MCP tools without duplicating logic or trusting LLM-supplied identity.

**维度**：仓库 `bldc-blss-master-service` · **独立后续 change（尚未创建）** · 依据 design D7/D8

**范围（来自 design D7/D8）**
- BLSS MCP 在 master 进程内暴露 `/mcp` 端点，承载工具目录。
- 工具声明在**专用 `@Tool` facade**（`@Component`）上，进程内直调现有 service 层；**不**在 `@RestController` 上加 `@Tool`（反例：`HierarchyController` 依赖 `UserContextSupport.getId()` 的 servlet ThreadLocal，进程内调用失效；MVC 注解污染 tool schema；`Response` 包装契约不匹配）。
- 身份在端点校验入站 `MCP_ACCESS_TOKEN` 时建立（`sub`→`userId`→MCP 调用上下文），tool 只读；机制 A（HTTP filter + `ThreadLocal`）或 B（SDK exchange 注入）。
- 因进程内直调 service，BLSS MCP 路径**不经由** `/rest Bearer`（Story 2 保留给外部/第三方消费者）。

**Acceptance Criteria**
- [ ] AC5.1 BLSS MCP 工具定义在独立 `@Tool` facade 上并复用现有 service，不修改/不复用现有 `@RestController` 方法作为工具。
- [ ] AC5.2 端点在执行任何工具前校验入站 `MCP_ACCESS_TOKEN`（验签/`iss`/`aud`/`exp`）并从 `sub` 解析出 master `userId`。
- [ ] AC5.3 工具从 MCP 调用上下文获取身份；`userId`/`sub` **不作为工具入参**（LLM 无法声明身份）。
- [ ] AC5.4 工具以解析出的 `userId` 调用 service，RBAC 与该用户一致。
- [ ] AC5.5 BLSS MCP 路径不依赖 `/rest Bearer`。

**依赖 / 未决**：BLSS MCP 端点框架/传输/线程模型选型（Spring AI MCP vs MCP Java SDK；streamable-http/SSE/stdio；同步 vs 响应式）——决定身份机制用 A 还是 B（open）。

### 子 Story 拆分（S5 = 5 点 → spike 先行，消解 R7 后再估余量）

拆分目的：R7（框架/传输/线程模型选型未定）是估点最大不确定源。先用 spike 定选型 + 打通身份，再据结果给余下实现可靠点数。

#### S5-spike — 框架/传输选型 + 最小 @Tool 身份打通（1~2 点，spike）
**As** the team, **I want** to pick the MCP server framework/transport and prove one tool end-to-end with delegated identity, **so that** the identity mechanism (A vs B) and the remaining scope become estimable.
- [ ] AC5s.1 选定 MCP server 框架（Spring AI MCP vs MCP Java SDK）、传输（streamable-http/SSE/stdio）与线程模型，并记录决策。
- [ ] AC5s.2 在 master 进程内暴露一个最小 `/mcp` 端点，承载**一个**示例 `@Tool`，复用现有某个 service。
- [ ] AC5s.3 打通身份机制：端点校验 `MCP_ACCESS_TOKEN` → `sub`→`userId` → tool 读取；确定用机制 A（`ThreadLocal`）还是 B（exchange 注入）。
- [ ] AC5s.4 创建 openspec change 固化 S5 范围（消解 R10），并据 spike 结果重新估余下点数。

#### S5-impl — 完整 BLSS MCP 端点 + 工具集（点数待 spike 后确定）
**As** the platform, **I want** the full BLSS MCP tool facade over the service layer, **so that** BLSS capabilities are usable as MCP tools.
- 承接原 AC5.1–AC5.5（facade 绑定、端点验 token、身份只读不入参、RBAC 一致、不经由 `/rest Bearer`）。
- 工作量随暴露 tool 数线性增长（R8）；`@Tool` schema 与现有 DTO 的适配成本待验证（R9）。
- **点数**：待 S5-spike 完成后据实评估（当前置信度不足以给定值）。

---

## 依赖与实现顺序

```
Story 1 (S1a → S1b → S1c) ─┬─▶ Story 3 端到端 (AC3.9)
Story 2 (/rest Bearer)     ─┘
Story 1 ─▶ Story 4 (多 backend 聚合，透传依赖 Story 3)
Story 1 + Story 2 语义 ─▶ Story 5 (S5-spike → S5-impl，独立后续 change)

S1 子 story：S1a(密钥/JWKS) → S1b(mint) → S1c(exchange)  可并行，S1c 依赖 S1a 的密钥
S5 子 story：S5-spike(选型+身份) → S5-impl(完整工具集)
```

- **最小 OBO 全链路（单 backend）**：Story 1 + Story 2 + Story 3。
- **MVP（含多 backend 聚合）**：+ Story 4。
- **BLSS MCP 真正内置**：Story 5（S5-spike 先行，尚未创建 change）。

## 阻塞实现的关键未决项（★）

- BLSS MCP 端点技术选型（决定 Story 5 身份机制 A/B）。
- Superset MCP 认证/用户映射契约（BLSS MCP 完成后再评估）。
- Audit 独立 change 的存储、retention、caller assurance 与敏感字段策略。

> 已解决：单节点内存签名密钥；浏览器为当前 session mint 固定 USER_TOKEN；Agent audience；exchange 检查 `sid`；静态 registry；10m/30s；V1 无 scope。

---

## 估点（Story Points）

采用斐波那契档位（1 / 2 / 3 / 5 / 8 / 13）。**锚点：Story 2 = 3 点**（基准略提高以对冲整体不确定性），其余相对锚点估算。

### 档位基准（以 S2=3 为锚）

| 点数 | 含义 | 代表 |
|------|------|------|
| 1 | 几乎已完成 / 平凡验证，无新建生产代码 | — |
| 3 | 单一清晰能力，模式已知，需接遗留链但无未知 | **S2（锚点）** |
| 5 | 多组件新功能，模式清晰、无框架级未知 | S3、S4 |
| 8 | 大能力 + 框架/遗留摩擦或重大未知，**建议拆分/spike** | S1、S5 |

### 各 Story 点数

| Story | 点数 | 相对锚点(S2=3)的理由 | 置信度 |
|-------|------|---------------------|--------|
| **S1** master AS + token 签发 | **8** | 约 S2 的 2.6×：3 个子能力（内存密钥+JWKS、mint、exchange+refresh）叠加 **AS 集成进遗留 XML 安全配置**的已知摩擦。建议拆分。 | 中 |
| **S2** /rest Bearer | **3**（锚点） | 基准：resource-server 模式已知，但要与 Basic 共存 + 重建 `UserContext` 接遗留链。 | 高 |
| **S3** mcp-spike 纯 Gateway | **5** | 约 S2 的 1.6×（按从零做估算，不因已完成而打折）：移除整个 AS 角色（login/PKCE/DCR/consent/JWK/H2 schema）+ 重建单一 stateless resource-server 链 + 外部 JWKS 验签 + 401/RFC9728 元数据 + OBO Bearer 透传改造 + 全套测试与端到端。*实际代码多数已完成，仅剩 tasks 6.3 端到端联调（被 S1 阻塞）。* | 高 |
| **S4** 多 backend 聚合 | **5** | 约 S2 的 1.6×：全新功能，含下游 MCP client、前缀路由、懒加载、请求驱动 TTL 刷新、single-flight、原子 snapshot 与降级——多组件但模式清晰。 | 中 |
| **S5** BLSS MCP 端点 | **8**（下限） | 与 S1 相当或更大：**连 change 都没建 + 框架选型未定（A/B 身份机制）**，工作量随暴露的 tool 数线性增长。建议先做技术选型 spike。 | 低 |

**总量（参考）**：`8 + 3 + 5 + 5 + 8 = 29` 点（均按从零做的总规模估算，不因部分已完成而打折）。

> 说明：S1/S5=8 不是「更多的活」，而是「活 + 未知/框架摩擦」，因此跳到高档并触发拆分/spike 建议。

### 风险点（不清楚的地方）

**S1 — master AS（高风险）**
- R1 XML-namespace 安全配置 vs Spring Authorization Server（Java-config 导向）的混合兼容性（design 已标注风险），实际难度未验证。
- R2 独立 mint 链要复用当前 APP_SESSION 身份但不触发 `LoginWorker` 或创建额外登录 session；同 session 固定 token 的缓存/复用方式待实现验证。
- R3 内存密钥重启 → 8h refresh 全失效，是否被运维/用户接受（已记为 accepted，但未与干系人确认）。

**S4 — 多 backend 聚合（中风险）**
- R4 backend registry 形态未定（静态 yml vs 动态注册）→ 影响配置与热更新设计。
- R5 freshness TTL 定值 + 是否支持 `list_changed` 将 catalog 标记为 stale → 影响刷新触发实现。
- R6 下游用哪个 MCP client 库、SSE/streamable 连接与降级的交互未验证。

**S5 — BLSS MCP 端点（高风险，估点置信度最低）**
- R7 ★ 框架/传输/线程模型选型未定（Spring AI MCP vs MCP Java SDK；同步 vs 响应式）→ 决定身份机制 A(ThreadLocal)/B(exchange)，是估点最大不确定源。
- R8 要暴露多少个 tool？工作量随 tool 数线性增长，当前无清单。
- R9 `@Tool` schema 从 POJO 推导 vs 现有 DTO（如 `HierarchyGroupInfoDTO`）的适配成本未知。
- R10 连 openspec change 都未创建，范围尚未固化。

**跨 Story（环境）**
- R11 `issuer` / `<mcp-resource>` 仍是「pending team review」的示例值，未最终确认。
- R12 `MCP_ACCESS_TOKEN` 是否带 `scope`（影响 S1 签发 + S4 未来隔离）。

### 拆分建议（降低 5 点的不确定性）

```
S1 (5) 拆成：
  S1a 内存密钥 + JWKS + RFC8414 metadata        → 2
  S1b USER_TOKEN mint（浏览器 + session 绑定）    → 2
  S1c RFC8693 token-exchange + refresh grant     → 2
  （拆后可并行/独立验证，把 R1/R2 风险局部化）

S5 (5+) 先做：
  S5-spike 框架/传输选型 + 一个最小 @Tool 打通身份  → 1~2（spike）
  再据结果估余下实现（消解 R7，才敢给可靠点数）
```
