# MCP OBO 平台 — Epics（组件维度）

> 组织方式：**按组件 / 可部署边界划分 Epic**（区别于 `mcp-obo-user-stories.md` 的功能维度）。
> 每条 AC 直接引用其来源 spec 的 **Requirement + Scenario**，不引入新行为。

## Epic ↔ 组件 ↔ 功能 Story 映射

| Epic | 组件 / 可部署 | 承载能力（spec） | 对应功能 Story | Jira | 点数 |
|------|--------------|-----------------|---------------|------|------|
| **E1** MCP Gateway | `mcp-spike`（独立服务） | gateway-token-validation, obo-identity-passthrough, mcp-tool-aggregation | S3 + S4 | [BDCSPM-80471](https://eaton-corp.atlassian.net/browse/BDCSPM-80471) | 3 + 3 = **6** |
| **E2** Master OBO（AS/Core + REST 边界） | `master`（同一可部署组件） | mcp-user-token, mcp-token-exchange, mcp-rest-bearer | S1 + S2 | — | 5 + 2 = **7** |
| **E3** Master 内置 BLSS MCP 服务器 | `master`（backend MCP 角色） | mcp-blss-server（change 已创建） | S5（S5-spike + S5-impl） | — | **5** |

**总量（参考）**：`6 + 7 + 5 = 18` 点（与功能维度一致，仅重新分组）。

> **E2 内部结构（能力组）**：master 作为单一可部署组件合为一个 Epic，内部保留两个角色边界：
> **E2.A — 授权服务器与 Token 签发**（AS/Core，token 生产端：mcp-user-token + mcp-token-exchange）；
> **E2.B — REST OBO 边界**（Resource Server，token 消费端：mcp-rest-bearer，另服务于客户第三方软件）。

---

## Epic 1 — MCP Gateway（`mcp-spike`，独立服务）

> **Jira Epic**: [BDCSPM-80471 — [Phoenix] BLSS MCP API Gateway / Facade Read-Only Access Entry](https://eaton-corp.atlassian.net/browse/BDCSPM-80471) (project BDCSPM, labels: MCP / Nexus / PI14 / Phoenix / Platform).
> Delivery boundary (from Jira): *BLSS owns the MCP Facade, authorization, routing, read-only enforcement, and audit. Nexus must access BLSS-owned data and operations only through this entry point.* Here **Nexus = the Agent / MCP client**.

**组件目标**：把 `mcp-spike` 从自包含 AS+RS 改造为纯 MCP Gateway——校验外部 token、向 Agent 广播 master 作为 AS、把用户身份 OBO 透传给下游、并聚合多个 backend MCP 的工具。

**承载能力（change `gateway-obo-token-exchange`）**：`gateway-token-validation`、`obo-identity-passthrough`、`mcp-tool-aggregation`。

### Jira AC ↔ E1 spec-derived AC Mapping (BDCSPM-80471)

Mapping the Jira Epic's Acceptance Criteria to our spec-derived E1 ACs. **Two Jira ACs are NOT yet covered by the current `gateway-obo-token-exchange` spec** and are flagged as gaps to be added as new requirements — not fabricated as existing coverage.

| Jira AC | Intent | E1 coverage | Status |
|---------|--------|-------------|--------|
| AC1 | Valid token → accept and route to authorized service | E1-AC3 (validate token) + E1-AC12 (prefix routing) | ✅ Covered; v1 intentionally has no OAuth scope layer |
| AC2 | Unauthorized request → rejected with a clear error code | E1-AC1 (401 challenge), E1-AC4 (wrong `aud` → 401), E1-AC5 (expired/bad-sig → 401) | ✅ Covered |
| AC3 | Write operation → direct execution rejected (read-only) | — | ❌ **Gap G1 — read-only enforcement not in spec** |
| AC4 | On completion → record caller, user, data domain, result | Separate `gateway-audit` change | ⚠️ Current delivery scope, intentionally separated from token/routing spec |
| AC5 | Downstream unavailable → handled error + preserve audit evidence | E1-AC18/AC19 (graceful degradation) | ⚠️ Partial — degradation ✅, but audit-evidence part depends on Gap G2 |

**Gaps to add as new requirements (not yet in the `gateway-obo-token-exchange` spec):**
- **G1 — Read-only enforcement**: the Facade must classify and reject write operations before routing. Our spec today validates identity/audience and routes, but does not classify read vs. write. *New capability/requirement needed* (e.g., `gateway-read-only-enforcement`).
- **G2 — Audit fields**: audit is confirmed as current delivery scope but will be specified and implemented through a separate `gateway-audit` change. It must define caller assurance, subject, backend/tool/data domain, outcome, duration, correlation ID, retention/sink, and sensitive argument/result handling.
- **Scope decision**: v1 does not advertise, request, or enforce OAuth scopes. Gateway authentication uses the resource-bound token; backend RBAC provides business authorization.

> These gaps are surfaced honestly for backlog grooming; they are **not** represented as passing/covered ACs below. The AC list that follows reflects only what the current spec actually specifies.

### Acceptance Criteria（每条标注来源 spec Requirement / Scenario）

**能力 gateway-token-validation**
- [ ] E1-AC1 无 `Authorization` 的 `POST /mcp` 返回 `401` + `WWW-Authenticate: Bearer resource_metadata="…"`。
  *来源：Requirement「Challenge unauthenticated MCP requests with external AS discovery」→ Scenario「Unauthenticated request receives a challenge」*
- [ ] E1-AC2 `/.well-known/oauth-protected-resource` 的 `authorization_servers` 含外部 AS（master）URL，`resource` = 配置的 MCP resource。
  *来源：同 Requirement → Scenario「Metadata points to the external Authorization Server」*
- [ ] E1-AC3 呈现由外部 AS 签名、`iss`=配置 issuer、`aud`⊇MCP resource、未过期的 `Bearer` token 时，gateway 认证通过并处理该 MCP 调用。
  *来源：Requirement「Validate externally-issued MCP access tokens」→ Scenario「Valid token is accepted」*
- [ ] E1-AC4 `aud` 不含配置 MCP resource 的有效 AS 签名 token → 返回 `401`。
  *来源：同 Requirement → Scenario「Token for a different audience is rejected」*
- [ ] E1-AC5 过期或验签失败的 token → 返回 `401`。
  *来源：同 Requirement → Scenario「Expired or wrongly-signed token is rejected」*
- [ ] E1-AC6 请求 `/oauth2/authorize`、`/oauth2/token`、`/connect/register`、`/login` 或 consent 页时，gateway 不提供 AS 响应。
  *来源：同 Requirement → Scenario「No authorization-server endpoints are served」*

**能力 obo-identity-passthrough**
- [ ] E1-AC7 已认证 MCP 工具调用触发下游请求时，出站请求携带 `Authorization: Bearer <校验后的 access token>`，且不发送静态 `blss_token`/`Basic`。
  *来源：Requirement「Forward the delegated user identity to master REST」→ Scenario「Delegated identity is forwarded on a tool call」*
- [ ] E1-AC8 无委托身份时，gateway 不以共享或配置凭证替代。
  *来源：同 Requirement → Scenario「Missing identity does not fall back to a shared credential」*
- [ ] E1-AC9 日志中 token 仅以掩码形式出现，绝不写出完整值。
  *来源：Requirement「Do not leak the delegated token in logs」→ Scenario「Token is masked in diagnostics」*

**能力 mcp-tool-aggregation**
- [ ] E1-AC10 `tools/list` 返回每个可达 backend 的工具，重命名为 `<backend-prefix>__<原始名>`；不同 backend 的同名工具因前缀不冲突。
  *来源：Requirement「Aggregate downstream tools into a single namespaced catalog」→ Scenario「Tools from multiple backends are merged with prefixes」*
- [ ] E1-AC11 暴露名以 `__` 分隔、仅含 `[a-zA-Z0-9_-]`，绝不含 `.`。
  *来源：同 Requirement → Scenario「Dotted namespaces are not used」*
- [ ] E1-AC12 `tools/call name=superset__run_sql` 转发为 `run_sql` 给 Superset backend，携带 `Authorization: Bearer <校验后的 access token>`，结果原样返回。
  *来源：Requirement「Route tool calls to the owning backend by prefix」→ Scenario「A prefixed tool call is routed and de-prefixed」*
- [ ] E1-AC13 前缀不匹配任何配置 backend 的 `tools/call` 返回错误且不转发。
  *来源：同 Requirement → Scenario「Unknown prefix is rejected」*
- [ ] E1-AC14 gateway 启动时不访问 backend；首个已认证 `tools/list` 或 catalog 不存在时的 `tools/call` 使用当前请求 token 懒加载，`initialize`/metadata/未认证及非 tools 请求不触发发现。
  *来源：Requirement「Lazily discover downstream tools on authenticated demand」的四个 Scenario*
- [ ] E1-AC15 catalog 全局共享且与用户身份无关；不同合法用户看到相同工具定义，RBAC 只在 backend `tools/call` 时生效。
  *来源：Requirement「Keep the global catalog identity-independent」→ Scenario「Different users observe the same tool definitions」*
- [ ] E1-AC16 freshness TTL 到期后，下一个已认证 catalog-dependent 请求使用当前 token 刷新；不保留用户 token、不执行依赖 token 的后台刷新。
  *来源：Requirement「Refresh a stale catalog on authenticated demand」的两个 Scenario*
- [ ] E1-AC17 并发初始化/刷新共享一次 in-flight 操作，catalog + 路由表作为一致 snapshot 原子发布。
  *来源：Requirement「Serialize discovery and publish an atomic snapshot」的两个 Scenario*
- [ ] E1-AC18 首次发现部分成功时返回 partial catalog；全部失败时返回 MCP error 并保持可重试；后续刷新失败保留 last-known-good 工具和路由。
  *来源：Requirement「Degrade gracefully when a backend is unavailable」的前三个 Scenario*
- [ ] E1-AC19 调用当前不可达 backend 的工具仅该调用返回错误，可达 backend 的调用继续成功。
  *来源：同 Requirement → Scenario「A call to a down backend fails only that call」*
- [ ] E1-AC20 `initialize` 握手中广播的 capabilities 含 `tools`，不含 `resources`/`prompts`/`logging`/`completions`。
  *来源：Requirement「Advertise only the tools capability in v1」→ Scenario「Only tools is advertised at handshake」*
- [ ] E1-AC21 Agent 请求非 `tools` 能力（如 `resources/list`、`prompts/list`）时，gateway 不聚合、不转发给任何 backend。
  *来源：同 Requirement → Scenario「A non-tools capability request is not routed downstream」*

**依赖**：E1-AC7/AC12 的端到端验证依赖 E2（master 能签发 `MCP_ACCESS_TOKEN`）；E1-AC14 至 AC19 依赖至少一个可达 backend（E3 或 Superset MCP）。

---

## Epic 2 — Master OBO（`master`，单一可部署组件）

**组件目标**：master 作为单一可部署组件，在一个 change（`mcp-obo-token-issuance`）内同时承担两个角色边界：**E2.A** token 生产端（AS/Core）与 **E2.B** token 消费端（REST OBO 边界）。两个能力组各自有独立受众（E2.A→Agent；E2.B→MCP gateway + 客户第三方软件），但同属 master 进程，故合为一个 Epic。

**承载能力（change `mcp-obo-token-issuance`）**：`mcp-user-token`、`mcp-token-exchange`（E2.A）、`mcp-rest-bearer`（E2.B）。

---

### E2.A — 授权服务器与 Token 签发（AS / Core 角色，token 生产端）

**目标**：master 承担 Core（签 `USER_TOKEN`）+ Authorization Server（RFC 8693 exchange + refresh + JWKS/metadata），作为平台唯一 token 签发方。受众：Agent。

#### Acceptance Criteria（每条标注来源 spec Requirement / Scenario）

**能力 mcp-user-token**
- [ ] E2-AC1 已登录浏览器为当前 session mint `USER_TOKEN` 时，master 返回 `sub`=当前 principal、`sid`=当前 session、`aud=<agent-resource>` 且 `exp<=session expiry` 的签名 JWT。
  *来源：mcp-user-token 的 session-bound browser mint requirement*
- [ ] E2-AC2 未认证调用者请求 `USER_TOKEN` 时，master 返回 `401` 且不签发 token。
  *来源：同 Requirement → Scenario「Unauthenticated request is rejected」*
- [ ] E2-AC3 同一 session 重复 mint 时返回/复用同一 `USER_TOKEN`；同一用户的不同登录 session 使用不同 `sid` 和 token。
  *来源：mcp-user-token 的 session identity requirement*
- [ ] E2-AC4 用户持有活跃 OTT session-handoff token 时 mint `USER_TOKEN`，OTT handoff token 保持有效且不变。
  *来源：同 Requirement → Scenario「Minting a USER_TOKEN does not affect the OTT handoff token」*
- [ ] E2-AC5 mint `USER_TOKEN` 不为该 principal 创建交互式登录会话。
  *来源：同 Requirement → Scenario「USER_TOKEN does not create a login session」*

**能力 mcp-token-exchange**
- [ ] E2-AC6 以有效 `USER_TOKEN` + `resource=<mcp-resource>` 发起 token-exchange grant 时，master 返回 `aud` 含 `<mcp-resource>`、`sub` 相同的 `MCP_ACCESS_TOKEN`，并返回 `MCP_REFRESH_TOKEN`。
  *来源：Requirement「Exchange USER_TOKEN for MCP access and refresh tokens (RFC 8693)」→ Scenario「Valid USER_TOKEN is exchanged」*
- [ ] E2-AC7 `subject_token` 过期或验签失败时，master 以 OAuth error 拒绝交换且不签发任何 token。
  *来源：同 Requirement → Scenario「Invalid or expired USER_TOKEN is rejected」*
- [ ] E2-AC8 呈现有效 `MCP_REFRESH_TOKEN` 时，master 返回 `sub`/`aud` 相同的新 `MCP_ACCESS_TOKEN`。
  *来源：Requirement「Refresh the MCP access token」→ Scenario「Valid refresh token yields a new access token」*
- [ ] E2-AC9 呈现过期 `MCP_REFRESH_TOKEN` 时，master 拒绝请求且不签发 access token。
  *来源：同 Requirement → Scenario「Expired refresh token is rejected」*
- [ ] E2-AC10 resource server 拉取 `jwks_uri` 时，master 返回用于验证 `USER_TOKEN` 与 `MCP_ACCESS_TOKEN` 的公钥集。
  *来源：Requirement「Publish JWKS and authorization-server metadata」→ Scenario「JWKS is retrievable」*
- [ ] E2-AC11 client 拉取 RFC 8414 AS metadata 时，响应列出 token endpoint、`jwks_uri` 与 token-exchange grant 类型。
  *来源：同 Requirement → Scenario「Metadata advertises token-exchange support」*

**关键实现约束（已定）**：单节点内存签名密钥；已登录浏览器为当前 session mint 固定 USER_TOKEN；`sub` 来自 session、`aud=<agent-resource>`、带 `sid`；exchange 检查 session 有效；无额外 Agent client authentication/scope。
**风险**：R1（遗留 XML 安全配置 vs Spring Authorization Server 混合兼容）、R2（mint 链与 OTT/LoginWorker 的隔离落地）。

---

### E2.B — REST OBO 边界（Resource Server 角色，token 消费端）

**目标**：`/rest/**` 在既有 HTTP Basic 之上**附加**接受 `Bearer` JWT，从 `sub` 重建 `UserContext`，使 OBO 委托调用与客户第三方软件都被承认，同时 Basic 行为不变。受众：MCP gateway + 客户第三方软件。

**承载能力**：`mcp-rest-bearer`。

#### Acceptance Criteria（每条标注来源 spec Requirement / Scenario）

- [ ] E2-AC12 `/rest/**` 请求呈现有效 `MCP_ACCESS_TOKEN` 作为 `Bearer` 时，master 认证并处理该请求。
  *来源：Requirement「Accept a Bearer JWT on the REST API in addition to HTTP Basic」→ Scenario「Valid bearer token authenticates a REST call」*
- [ ] E2-AC13 `/rest/**` 请求呈现有效 HTTP Basic 且无 bearer token 时，master 与之前完全一致地以 Basic 认证。
  *来源：同 Requirement → Scenario「HTTP Basic still works」*
- [ ] E2-AC14 bearer token 过期、验签失败或缺少所需 `aud` 时，master 返回 `401`。
  *来源：同 Requirement → Scenario「Invalid bearer token is rejected」*
- [ ] E2-AC15 `sub=peter` 的 bearer token 认证 `/rest/**` 调用时，master 施加与 `peter` 在 HTTP Basic 下相同的 authorities 与 RBAC。
  *来源：Requirement「Rebuild the user context from the token subject」→ Scenario「RBAC matches the Basic-authenticated equivalent」*
- [ ] E2-AC16 bearer token 的 `sub` 无法解析为已知 master 用户时，master 返回 `401` 或 `403` 且不处理请求。
  *来源：同 Requirement → Scenario「Unknown subject is rejected」*

**组件说明**：`/rest Bearer` 为独立一等能力（客户第三方软件亦会使用，design D4）；进程内 BLSS MCP（E3）不经由此路径。
**安全约束（来自 design，已定）**：即便 gateway 与 master REST 同容器 loopback、无 mTLS，master REST 仍独立验签后才信任 `sub`（D5/D6）。

---

## Epic 3 — Master 内置 BLSS MCP 服务器（`master`，backend MCP 角色）

> ✅ **change 已创建**：`bldc-blss-master-service/openspec/changes/mcp-blss-server`（能力 `mcp-blss-server`，validate --strict 通过）。以下 AC 已从「design 派生」升级为引用新 spec 的 Requirement / Scenario。

**组件目标**：在 master 进程内暴露 `/mcp` 端点，承载 BLSS 工具；工具定义在 service 层之上的 `@Tool` facade，身份取自被校验的入站 token。

### 技术选型（基于已核实事实，R7 收敛）

**master 栈事实（已核实）**：Java 21 / Spring Boot 3.4.x / Spring Framework 6.2.x / Spring Security 6.5.x；打包 **WAR 部署到外部 Tomcat**（非 Boot fat-jar）；已有 `oauth2-jose`（JWT 编解码）；尚无 Spring AI / MCP 任何依赖。

| 选型项 | 结论 | 依据 |
|--------|------|------|
| 框架 | **Spring AI MCP Server**（保留 `@Tool` 注解 + schema 推导） | Boot 3.4 / Java 21 正好满足 Spring AI 1.0 GA 下限；与 E2 同属 Spring Security 生态，复用成本最低 |
| 装配方式 | **手动 `@Configuration` 注册 bean**（不用 starter auto-config） | **已验证：Tomcat/WAR 下 Spring AI starter 的 auto-configuration 不工作**；而通用的手动 `@Configuration` 在本项目 Tomcat 下已知可工作 |
| 传输 | **webmvc**（同步 servlet 线程） | 贴合 WAR/servlet 容器；使身份机制 A 成立 |
| 身份机制（D8） | **机制 A（`ThreadLocal`）** | webmvc 同步线程执行 tool，`McpBearerAuthFilter` 建 ThreadLocal → `@Tool` 读取 |

**收敛后的 R7**：不再是「框架三选一」，而是一条明确假设 + 一个聚焦验证点（见下方 spike）。退路：若手动装配 MCP bean 受阻，仍用 Spring AI core，仅自行实现 transport 注册，不退回 MCP Java SDK。

**尚未验证（S5-spike 头号项）**：通用 `@Configuration` 已知可工作，但 **MCP server bean 的手动装配尚未专门验证**——尤其是把 MCP 的 webmvc endpoint 接进现有 `dispatcher-servlet.xml` / servlet 注册体系。

**先决 spike（S5-spike）**：在上述选型下做两件事——（1）用手动 `@Configuration` 将 Spring AI MCP webmvc endpoint 接进遗留 servlet/XML 栈并能启动；（2）用一个最小 `@Tool` 验证 servlet 线程上 `ThreadLocal` 身份打通（机制 A）。已作为 change `mcp-blss-server` 的 **tasks 第 1 节**（风险前置）。

### Acceptance Criteria（来自 change `mcp-blss-server` 的 spec）

- [ ] E3-AC1 BLSS MCP 工具定义在独立 `@Tool` facade（`@Component`）上并复用现有 service，不在 `@RestController` 方法上加 `@Tool`。
  *来源：Requirement「Bind tools on a dedicated facade over the service layer」→ Scenario「A tool reuses the service layer」*
- [ ] E3-AC2 端点在执行任何工具前校验入站 `MCP_ACCESS_TOKEN`（验签/`iss`/`aud`/`exp`），并从 `sub` 解析出 master `userId`。
  *来源：Requirement「Validate the inbound access token before executing tools」→ Scenario「Valid token is accepted and identity resolved」/「Invalid or wrong-audience token is rejected」*
- [ ] E3-AC3 工具从 MCP 调用上下文获取身份；`userId`/`sub` 不作为工具入参（LLM 无法声明身份）。
  *来源：Requirement「Establish identity from the token, never from tool input」→ Scenario「Tool reads identity from context」/「Caller-supplied identity is not honored」*
- [ ] E3-AC4 工具以解析出的 `userId` 调用 service，RBAC 与该用户一致。
  *来源：Requirement「Execute tools with the end user's RBAC」→ Scenario「RBAC matches the end user」/「Unknown subject is rejected」*
- [ ] E3-AC5 BLSS MCP 路径不依赖 `/rest Bearer`（进程内直调 service）。
  *来源：Requirement「Do not depend on the REST bearer path」→ Scenario「Tool call does not traverse /rest bearer」*
- [ ] E3-AC6 `initialize` 握手仅广播 `tools` 能力，不含 `resources`/`prompts`/`logging`/`completions`。
  *来源：Requirement「Expose an in-process BLSS MCP endpoint advertising only tools」→ Scenario「Endpoint advertises only tools at handshake」*

**风险**：R7（已收敛：框架=Spring AI + 手动装配 + 机制 A；剩余验证点=MCP bean 手动装配接入遗留 servlet/XML 栈，已落为 change tasks 第 1 节 spike）、R8（tool 数量决定工作量，spec 不钉 tool 清单）、R9（`@Tool` schema 与现有 DTO 适配）。R10（change 未创建）✅ 已解决。

---

## 实现顺序（组件维度）

```
E2.A (Master AS/Token) ──┬──▶ E1 端到端 (E1-AC7/AC12 依赖 E2.A 签发)
E2.B (Master REST)    ──┘
E2.A ──▶ E3 (BLSS MCP backend，其 token 由 E2.A 签发；先 S5-spike)
E1 ◀── E3 / Superset MCP (聚合与路由需要可达 backend)
```

- **最小 OBO 全链路（单 backend）**：E2（E2.A + E2.B） + E1（gateway 校验/透传部分）。
- **多 backend Gateway（MVP）**：+ E1 的 mcp-tool-aggregation + 至少一个 backend（E3 或 Superset MCP）。
- **BLSS MCP 真正内置**：E3（先 S5-spike 消解 R7，再据结果补 spec 与点数）。

### 并行开发（前提：先冻结跨 Epic 接口契约）

依赖分两类：**接口依赖（编译期）** 冻结契约后即可并行；**运行时依赖（集成期）** 无法靠冻结消除，只能在集成阶段合流。冻结契约后，三个 Epic 的**开发阶段可高度并行**，各用 stub / 自签测试 token 解耦。

**待冻结的 4 个跨 Epic 契约**（详见 `mcp-obo-interface-contracts.md`）：
1. Token 契约：`MCP_ACCESS_TOKEN` 的 claims（`iss` / `aud=<mcp-resource>` / `sub` / `exp`）与验签方式（E2 产出，E1/E3 消费）。
2. JWKS / AS metadata 端点形状：`jwks_uri`、`/.well-known/oauth-authorization-server`（E2 产出）。
3. MCP 协议契约：`initialize` / `tools/list` / `tools/call` 标准形状 + 双下划线前缀约定（E1↔E3）。
4. 身份透传契约：gateway 原样透传 `Bearer <MCP_ACCESS_TOKEN>`（方案 A，E1→E3）。

**并行度**：

| Epic | 开发阶段 | 说明 |
|------|---------|------|
| E2 | ✅ 完全独立 | token 源头，不依赖任何人 |
| E1 | ✅ 几乎全并行 | 用 E2 契约 + 自签测试 token 开发验签/metadata/聚合/路由/降级 |
| E3 | ⚠️ 大部分并行 | facade/身份 filter/工具用自签 token 开发；**但 spike 硬串行且须最先做** |

**一个硬串行**：E3 的 tasks 第 1 节 spike（WAR/Tomcat 手动装配 MCP 端点）不依赖 E1/E2，但它是 E3 一切的前提，也是全局最大技术风险，**应先于三线并行启动**（第 0 步）。

**一段集成期串行**（运行时依赖，冻结消除不了，但可用契约测试/stub 压缩到最后）：真实 token 端到端须 `E2 发 token → E1 验签路由 → E3 执行 tool` 合流。

**推荐节奏**：
```
第 0 步：E3 spike 证实 WAR 装配可行 + 冻结 4 个契约
并行窗口：E2 / E1 / E3 三线同时开工（各用 stub / 自签 token）
集成窗口：E2→E1→E3 真实 token 端到端合流
```

## 跨 Epic 未决项（★=阻塞）

- E3 选型已定（Spring AI + 手动 `@Configuration` + webmvc + 机制 A）；剩余验证点=MCP bean 手动装配能否接进遗留 servlet/XML 栈（S5-spike 头号项）。
- `issuer` / `<mcp-resource>` 示例值待 team 评审（跨 E1/E2）。
- E1 刷新间隔定值、backend registry 配置形态。
- `MCP_ACCESS_TOKEN` 是否携带 `scope`（v1 靠 RBAC 兜底；细粒度授权为 v2，影响 E1/E2）。
