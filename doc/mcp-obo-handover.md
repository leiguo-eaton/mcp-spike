# MCP OBO 交接文档

> 状态时间点：本文档写于 `gateway-obo-token-exchange` 基本完成、master 侧 AS 与 BLSS MCP 打通认证链之时。
> 涉及两个仓库，**两边都尚未 commit**。

---

## 1. 一句话现状

网关（mcp-spike）**已完成**；master 侧的 **BLSS MCP 端点已能验签并解析出真实用户**，但**还没有任何东西能签发 `MCP_ACCESS_TOKEN`** —— token 的来源（USER_TOKEN 签发 + token endpoint）是下一块地基，也是打通端到端的唯一阻塞项。

## 2. 进度

| 仓库 | OpenSpec change | 进度 | 测试 |
|---|---|---|---|
| `mcp-spike` | `gateway-obo-token-exchange` | **41/43** | 74 通过 |
| `bldc-blss-master-service` | `mcp-blss-server` | 15/33 | 74 通过¹ |
| `bldc-blss-master-service` | `mcp-obo-token-issuance` | 6/31 | 同上¹ |

> ¹ 这 74 个是 master 侧 **MCP 相关**测试（`Mcp*Test` / `Jwt*Test` / `Jdbc*Test` / `AuthorizationServerMetadataTest` / `XmlAndJavaSecurityChainInteropTest`）。
> **master 的全量测试套件从未跑过** —— 接手后第一件事建议先跑一次全量，确认没有踩到既有测试。

两个仓库剩余的未勾任务，直接看各自的 `openspec/changes/<name>/tasks.md`，里面标注了"为什么没做"。

## 3. 代码状态与构建

### 未提交

**两个仓库都没有 commit。** 接手前先决定怎么落库。

- `mcp-spike`：在默认分支上，工作区有大量改动（含删除的 POC 类）
- `bldc-blss-master-service`：在分支 `work/spike-blss-mcp-server` 上，部分文件已 `git add`

### 怎么构建

两个仓库**都没有 Maven wrapper**，且 `mvn` 不在 PATH 上。我用的是 IntelliJ 自带的：

```
C:\tool\ideaIC-2024.2.0.2.win\plugins\maven\lib\maven3\bin\mvn.cmd
JAVA_HOME=C:\tool\jdk-21.0.7
```

master 侧跑测试建议加参数，否则会被无关的插件拖慢或阻断：

```
mvn -o -Dcheckstyle.skip=true -Djacoco.skip=true -Dtest=Mcp*Test,Jwt*Test,Jdbc*Test,AuthorizationServerMetadataTest,XmlAndJavaSecurityChainInteropTest -DfailIfNoSpecifiedTests=false test
```

> `mvn package` 在 master 上**会失败**：ZKM 混淆插件在离线模式下解析不到 `com.zelix:zelix-klass-master`。与本次改动无关，但别被它误导。

---

## 4. 架构

```
Browser ──USER_TOKEN──▶ Agent Service ──token-exchange──▶ master (AS)
                             │                              ↑ ② 还没做
                             │ Bearer MCP_ACCESS_TOKEN
                             ▼
                     mcp-spike (Gateway / RS)          ← ① 已完成
                       验签(master JWKS)/iss/aud/exp
                       聚合 tools/list，按 blss__ 前缀路由
                             │ 同一个 token 原样透传
                             ▼
                     master /mcp (BLSS MCP)            ← ③ 认证链已通，工具还没有
                       每请求独立验签 → sub → userId
```

关键约定（Option A 共享受众）：网关和所有后端**共用同一个 `aud`** = `<mcp-resource>`。token 证明的是"这个用户是谁"，不是"能调哪个后端"；后端隔离靠各自的 RBAC。

---

## 5. 已完成的部分

### mcp-spike（网关）

从"自带授权服务器的 POC"改成了**纯 MCP Gateway / Resource Server**：

- 删掉了 AS 角色（`AuthorizationServerConfig`、登录页、DCR、consent、本地 JWK）、POC 工具（`TopologyTools` / `DeviceSearchTools`）、直连 REST 的 `MasterClient`、以及 Spring AI MCP server starter
- `/mcp` 现在是**自己实现的 stateless Streamable HTTP JSON-RPC controller**（`gateway/McpGatewayController`）
- 多后端聚合：静态注册表 → 懒加载目录（10min TTL / 30s 失败退避 / single-flight / 优雅降级）→ `blss__` 前缀路由
- 稳定的错误分类：`unknown_prefix` / `backend_unavailable` / `unknown_tool` / `invalid_backend_catalog` / `catalog_unavailable`

剩 2 个任务（6.3、7.20），**都是需要真实 master + BLSS MCP 环境的端到端验证**，本地无法推进。

### master — `mcp-obo-token-issuance`（第 1 节）

签名密钥 + JWKS + RFC 8414 元数据，**零新依赖**（`spring-security-oauth2-jose` 和 `nimbus-jose-jwt` 本来就在 classpath 上）。

- `McpSigningKey` —— 2048 位 RSA，**启动时内存生成、不落库**（单节点）。重启会让所有已签发 token 失效，这是已接受的行为
- `GET /.well-known/jwks.json`、`GET /.well-known/oauth-authorization-server`
- `/.well-known/**` 在 `opi-security-common.xml` 里声明为 `security="none"`

### master — `mcp-blss-server`（第 1、2 节）

- `/mcp` 端点跑起来了：MCP Java SDK 2.0.0 + `HttpServletStatelessServerTransport`，`web.xml` 精确映射
- `opi-security.xml` 里有专用的 stateless `/mcp` 链
- **每请求独立认证**：`JwtMcpIdentityResolver` 验签 + `iss` + `aud` + `exp`/`nbf`，`JdbcMcpUserLookup` 把 `sub` 映射成 `sec.user.se_id`
- 身份通过 `McpTransportContext` 作为**方法参数**传给工具 handler
- 目前只有一个 `whoami` 诊断工具，**没有任何真实业务工具**

---

## 6. 关键决策（以及为什么）

这些决策都写在各仓库的 `openspec/changes/*/design.md` 里，此处只列最容易被"好心改回去"的几条。

| 决策 | 理由 |
|---|---|
| 网关自己实现 `/mcp`，不用 Spring AI MCP server | 需要**按请求 token 懒加载**目录、控制 `initialize` 只广播 `tools`、控制错误分类。Spring AI 的 `tools/list` 来自启动时静态注册，没有 hook |
| master 用 MCP Java SDK，**不用 Spring AI** | Spring AI 2.0 依赖 `spring-webmvc:7.0.8`（Spring Boot 4）。master 是 Spring 6.2 且**没有 Spring Boot** |
| master **不引入** Spring Authorization Server | 见第 7 节"AS 库为什么不能用" |
| 网关**跳过**非法工具定义，而不是整个后端发现失败 | 一个坏工具不该让整个 `blss__` 命名空间下线。代价：坏工具会**静默消失**，所以 BLSS 必须自己校验目录 |
| USER_TOKEN **一用户一个 + 绑定登录 session** | 否则 logout 后 MCP 权限最长可再存活 8 小时（refresh token TTL）。这是产品级安全决策，已与 master spec 对齐 |
| v1 **不使用 OAuth scope** | 授权 = `sub` + 后端 RBAC。网关的 protected-resource metadata 不声明 `scopes_supported` |
| BLSS MCP 认证放在 `McpServlet` 而不是 SDK 的 `contextExtractor` | 见第 7 节 |

---

## 7. 踩过的坑 —— 这节最值钱

按"如果不知道会浪费半天"排序。

### 7.1 master 不是 Spring Boot 应用

传统 Servlet 3.1 WAR + `ContextLoaderListener`，pom 里 **36 处显式排除 `spring-boot-*`**，构建产物里 0 个 Boot jar。后果：

- **`ServletRegistrationBean` 不在 classpath 上**
- `web.xml` 是 `metadata-complete="true"` → **`@WebServlet` 也被禁用**
- 组件扫描只覆盖 `com.eaton` → 新的 `@Configuration` **必须**在 `opi-service.xml` 里手工声明成 bean，否则静默不生效

注册 servlet **只有 `web.xml` 一条路**，而且要同时改 `web.xml` 和部署模板 `.web.xml.tmpl`（`McpDeploymentDescriptorTest` 会挡住漂移）。

### 7.2 `UserContextSupport.getId()` 在无 Authentication 时返回 1 = admin

```java
if (authentication == null) {
    return 1;   // ← 默认 admin
}
```

MCP 路径**不走** servlet 安全链，所以任何工具误用它，都会**静默地以管理员身份执行所有人的请求**。这是本次改动里最危险的失败模式。

已有防护：`McpIdentitySafetyTest` 扫描 `com.optimumpathinc.mcp` 下的源码，检测到 `UserContextSupport` 的 import 就失败。**不要删这个测试。**

### 7.3 MCP SDK 的 `contextExtractor` 抛异常会变成容器错误页

`HttpServletStatelessServerTransport.doPost` 里这行**没有 try/catch**：

```java
McpTransportContext transportContext = this.contextExtractor.extract(request);
```

所以认证不能放在 extractor 里，否则拒绝会变成 500 而不是 401。现在的做法是在 `McpServlet.service()` 里先认证再委托 —— 顺带好处是 `initialize` / `tools/list` / `tools/call` 全部统一鉴权，而不只是工具执行时。

### 7.4 Jackson 在 master 是 `provided`

由 Tomcat 的 `lib/` 提供，**不打进 `WEB-INF/lib`**。所以 `mcp-json-jackson2` 里的 `jackson-databind` 被显式 exclude 了。以后加任何带 Jackson 的依赖都要检查这点。

新增打包的 jar：`mcp-core`、`mcp-json-jackson2`、`reactor-core`、`json-schema-validator`、`jackson-dataformat-yaml`。

### 7.5 MCP SDK 默认开启 tool input schema 校验

试图注入 `{"userId":1,"sub":"admin"}` 会被 `additionalProperties:false` **直接拒绝**（不是忽略）。所以：**所有工具都应该声明 `additionalProperties: false`**。

另外 `McpSchema.Tool` 的构造器自己就拒绝 null `inputSchema`，所以目录校验只需要检查 schema 的**形状**（`"type": "object"`），不用检查存在性。

### 7.6 Spring Security 7 自带 RFC 9728 端点（网关侧）

Spring Security 7 的 `OAuth2ResourceServerConfigurer` 会注册一个 filter 处理 `/.well-known/oauth-protected-resource/**`，**遮蔽掉自己写的 controller**。

这曾经导致网关的 metadata 实际返回 `resource: "http://localhost"` 且**完全没有 `authorization_servers`** —— 任务 4.1 名义上"完成"了但运行时是坏的。现在改成用框架的 `.protectedResourceMetadata(...)` 定制，controller 已删除。

### 7.7 Spring Boot 4 用的是 Jackson 3

网关侧是 `tools.jackson.databind`，不是 `com.fasterxml.jackson.databind`。`asText()` 已废弃，用 `asString()`。

### 7.8 XML 与 Java 安全链可以共存，但必须标 `@Order`

我原以为两者会抢 `springSecurityFilterChain` 这个 bean 名 —— **实测是错的**，`WebSecurityConfiguration` 会把 XML 声明的链一起收集进来。

真正的坑是**顺序**：master 最后一条 `<http>` 没有 `pattern`（匹配任意请求），没标 `@Order` 的 Java 链排在它后面会导致启动直接失败：

```
UnreachableFilterChainException: ... will never get invoked.
```

`@Order(HIGHEST_PRECEDENCE)` 可解。这条约束已固化为 `XmlAndJavaSecurityChainInteropTest`（3 个用例）。

### 7.9 AS 库为什么不能用

翻了 `spring-security-oauth2-authorization-server:1.5.8` 源码，`OAuth2TokenExchangeAuthenticationProvider` 有两处硬约束：

1. `getAuthenticatedClientElseThrowInvalidClient(...)` —— **必须有已认证的注册客户端**，但我们的契约是 `token_endpoint_auth_methods_supported=["none"]`
2. `authorizationService.findByToken(subjectToken, ACCESS_TOKEN)` —— **subject_token 必须是这个 AS 自己签发并存储的 access token**，但我们的 USER_TOKEN 是专用端点签发、绑定 session、格式自由的

要用它就得同时违背两条已定契约，换两个端点。所以 token endpoint **手写**。

### 7.10 H2 里 `user` 是保留字

master 生产用 PostgreSQL，`sec.user` 不带引号是合法的。测试里要用 `jdbc:h2:mem:...;NON_KEYWORDS=USER` 模拟。

---

## 8. 剩余工作与建议顺序

### 第一优先：`mcp-obo-token-issuance` 第 2、3 节

这是**端到端唯一的阻塞项** —— 现在除了测试，没有任何东西能签出合法 token。

- 第 2 节：USER_TOKEN 签发（一用户一个、绑定登录 session、`aud=<agent-resource>`、`token_use=mcp_user`、≈5min）
- 第 3 节：手写 token endpoint（RFC 8693 exchange + refresh），**exchange 和 refresh 都要回查登录 session 是否有效**

> **开工前建议先调查**：master 现有的 session store 怎么按 `sid`（或等价关联）回查登录会话有效性。这是"一用户一 token + logout 失效"的地基，我**没有调查过**。
> 参考起点：`opi-security.xml` 里的 `ottTokenService`（`InMemoryOneTimeTokenService`）、`ResetPwdTokenService`、以及兜底链的 session concurrency 配置。
> 注意：OTT handoff 的 store **不能复用**（一用户一个 + 消费后失效 + 桥接成完整登录会话，语义不对）。

### 第二优先：`mcp-blss-server` 第 3 节

真实业务工具。地基已经就绪（认证链通了，`whoami` 证明了整条链）。

- 建议第一个工具从 `HierarchyService` 挑，它有**接受显式 `userId` 参数**的方法（如 `queryRackGroups(long id, ...)`），最适合 MCP 路径
- 别用 `@RestController` 的方法改造（见 `mcp-obo-token-issuance/design.md` D7 的三条具体理由）
- 3.6 待决：手写 `inputSchema` JSON，还是加个 schema 生成器。工具数量会长得快的话值得加

### 第三优先：部署验证

- `mcp-blss-server` 1.9：真实 Tomcat 下 `/mcp` 可达、未认证返回 401 而不是 302
- `mcp-obo-token-issuance` 1.7：`/.well-known/*` 能通过对外 issuer URL 访问到

### 可独立排期

`mcp-obo-token-issuance` 第 4 节（`/rest/**` bearer）—— 网关**已经不调用 `/rest/**`** 了，这是给第三方 REST 消费方的独立能力，**不在 MCP 关键路径上**。

---

## 9. 待拍板 / 已知未知

1. **环境标识的实际值**。两个仓库都提了 `issuer=https://auth.blss.local`、`<mcp-resource>=https://mcp.blss.local`，都还标着"pending review"。`<agent-resource>` 完全没定。
   这三个值必须和网关的 `sidecar.issuer-uri` / `sidecar.mcp-resource` **完全一致** —— 不一致的表现是网关全量 401 且没有任何其他诊断信息（所以 master 启动时会把生效值打进日志）。

2. **反向代理映射**。master 部署在 Tomcat 的 context path 下（如 `/bldc-blss-master-service`），但 issuer 是 `https://auth.blss.local`。中间需要代理做映射，否则 discovery 会失败。

3. **session 回查机制**（见上，第 8 节）。

4. **Superset MCP** 明确推迟，直到它的认证与用户映射契约确认。网关的静态注册表现在只有 `blss` 一项。

---

## 10. 相关文档

| 文档 | 内容 |
|---|---|
| `mcp-spike/doc/master-obo-contract.md` | 给 master 团队的接口契约（中文，含 §10 选型核实表） |
| `mcp-spike/doc/blss-mcp-backend-contract.md` | 给 BLSS MCP 的后端契约（英文） |
| `mcp-spike/openspec/changes/gateway-obo-token-exchange/` | 网关的 proposal / design / specs / tasks |
| `bldc-blss-master-service/openspec/changes/mcp-obo-token-issuance/` | master AS 角色 |
| `bldc-blss-master-service/openspec/changes/mcp-blss-server/` | BLSS MCP 后端 |

三个 change 都通过 `openspec validate --strict`。design.md 里的决策编号（D1–D15 / BD1–BD13）在 tasks 和代码注释里被引用，改行为时记得同步。
