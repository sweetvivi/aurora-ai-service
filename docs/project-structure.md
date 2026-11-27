# backend-service 项目结构规划

> 规划基线：`https://ai1foo.com/newchat` 页面所依赖的后端接口面
> 技术基线：Spring Boot 3.5.16 / Java 17 / Spring Data JPA / MySQL 8.0.46
> 状态：基础工程与 MySQL/Flyway schema 已落地；Chat、Agent、会话、模型目录、图片生成、
> 智能修图、AI Canvas、用户级 New API Token 计费和管理端均已形成可运行切片。本文保留最初
> `/newchat` 规划基线，同时在“实施进度”和基础设施章节记录当前实现。

---

## 1. 范围界定

本节保留最初 `/newchat` 对话站的规划范围。当前实现已经通过扩展模块加入 Agent、图片生成、
智能修图和 AI Canvas；其余扩展仍按相同分层增量接入。

### 纳入范围

| 能力域 | 说明 |
|---|---|
| 应用配置 | 站点初始化配置下发、多域名白标（`app-name`）、广告位 |
| 登录鉴权 | 账号密码、短信/邮箱验证码、微信扫码、小程序登录、绑定手机号 |
| 用户中心 | 用户信息、VIP 状态、签到体系、账号迁移检查 |
| 模型目录 | 可用模型列表、模型配置、模型消耗说明 |
| 对话核心 | SSE 流式对话（3 个入口：主接口 / 智谱 / 指定模型） |
| 会话管理 | 会话分组、群组对话、智能体对话、消息记录增删查 |
| 文件能力 | 文件上传、文件转文本（对话附件） |
| Token 计费 | 平台用户绑定 New API 用户与 Relay Token；调用前实时查询 `user_quota.total_available`，不建立本地积分、余额或流水 |
| 分享 | 对话分享页生成、微信链接生成 |
| 异步任务 | 任务列表查询（长耗时操作的统一入口） |

### 不纳入但预留扩展位

文档翻译、PDF 工具集、PPT 生成、视频生成、音乐与声音克隆、TTS 语音、VIP 与订单支付、
积分商城、分销活动、导航收藏。绘画与图像处理已经作为扩展模块实现。

> 这些能力统一挂到 `modules/` 下新增同级模块即可，不需要改动既有分层。详见 §8。

---

## 2. 设计原则

1. **按功能垂直切分（package-by-feature）**
   沿用现有 `product` 模块的组织方式：一个业务能力的所有代码（Controller / Service / Entity / Repository / DTO）放在同一个包内，而不是按技术分层横向铺开。改一个功能只动一个目录。

2. **三层目录语义固定**
   - `common/` —— 与业务无关的通用能力（响应体、异常、鉴权上下文、全局配置、工具）
   - `modules/` —— 业务模块，可依赖 `common` 与 `infra`
   - `infra/` —— 外部依赖适配层（上游大模型、对象存储、短信、微信），只被 `modules` 调用，不反向依赖

3. **依赖方向单向**
   `modules` → `infra` → 外部系统；`modules` → `common`。
   `infra` 不依赖 `modules`，`common` 不依赖任何业务包。

4. **接口路径与源码结构解耦**
   外部路径保持与线上一致（`/api/v1/...`、`/api/v2/...`、`/api/v3/...`），由 Controller 的 `@RequestMapping` 声明；包结构按业务域划分，不按 URL 版本号划分。

5. **接口文档由运行时自己提供**
   应用启动即可访问，无需额外 profile、也没有拦截器挡在前面：

   | 地址 | 说明 |
   |---|---|
   | `/doc.html` | Knife4j 控制台（中文分组、调试、离线导出），静态资源来自 `knife4j-openapi3-ui` webjar |
   | `/v3/api-docs` | 原始 OpenAPI 文档，`mvnw -Popenapi verify` 也导出同一份 |

   **只有 Knife4j 一个控制台**：springdoc 走 `springdoc-openapi-starter-webmvc-api` 而不是
   `-webmvc-ui`，Swagger UI 的 webjar 不在 classpath 上，`/swagger-ui/*` 返回 404。
   两个控制台的结果是修好一个、另一个还在发过期的安全方案。

   ⚠️ **但 Knife4j 依赖 `/v3/api-docs/swagger-config`**（它的前端代码里写死了这个路径，用来查文档地址），
   而注册这个端点的是 swagger-ui 的自动配置——`-webmvc-ui` 没了它就没，`springdoc.swagger-ui.enabled=false`
   同样会让它没。此时 `/doc.html` **仍然返回 200**（它只是个静态页），背后所有请求 404，控制台打开是空白，
   表现得像"站点挂了"而不是配置错了。因此该端点由 `SwaggerConfigEndpoint` 自己提供，
   并由 `DocumentationConsoleTest#theConsoleCanActuallyBootstrapItsDocument` 整条链路验证
   （引导报文 → 它指向的文档），而不是只验 `/doc.html` 一个地址。

   文档里声明的安全方案是 **HTTP bearer**，console 上的 Authorize 会发出真实的
   `Authorization: Bearer <accessToken>`。分组用 Controller 上的中文 `@Tag`，**不要引入
   `GroupedOpenApi` bean**（会让 `/v3/api-docs` 变空，破坏契约导出与测试断言）。
   可达性与安全方案都由 `DocumentationConsoleTest` 锁住。

---

## 3. 顶层目录结构

```
D:\hub\backend-service
├── .mvn/wrapper/                     # Maven Wrapper（已存在）
├── docs/                             # 设计与接口文档
│   ├── project-structure.md          # 本文档
│   ├── architecture.md               # 架构说明（待补）
│   └── api-reference.md              # 接口清单（待补）
├── src/
│   ├── main/
│   │   ├── java/com/hub/backend/
│   │   │   ├── BackendServiceApplication.java
│   │   │   ├── common/               # 通用能力（无业务语义）
│   │   │   ├── modules/              # 业务模块
│   │   │   └── infra/                # 外部依赖适配
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── application-mysql.yml
│   │       ├── application-prod.yml
│   │       ├── db/migration/         # Flyway 数据库迁移脚本
│   │       ├── prompts/              # 提示词 / 系统词模板
│   │       └── logback-spring.xml
│   └── test/java/com/hub/backend/
│       ├── support/                  # 测试基类、夹具、容器配置
│       └── modules/                  # 与 main 镜像的模块测试
├── mvnw / mvnw.cmd
├── pom.xml
└── README.md
```

---

## 4. 包结构与模块职责

### 4.1 common —— 通用能力层

```
common/
├── api/                    # 统一出入口契约
│   ├── ApiResponse           统一响应体 {code, message, data}
│   ├── PageQuery             分页入参（page / rows / keyword）
│   ├── PageResult            分页出参
│   └── ErrorCode             错误码枚举（对齐线上 4001 / 4005 等）
├── exception/              # 异常体系
│   ├── BizException          业务异常基类
│   ├── NotFoundException
│   ├── UnauthorizedException 未登录 / Token 失效
│   ├── PaymentRequiredException New API 用户余额不足
│   ├── ServiceUnavailableException 计费查询不可用或凭据准备中
│   └── GlobalExceptionHandler 全局异常兜底
├── security/               # 鉴权基础设施
│   ├── AuthContext           当前登录用户上下文（ThreadLocal）
│   ├── @CurrentUser          控制器参数注解，注入当前用户
│   ├── AuthInterceptor       统一拦截校验
│   ├── TokenResolver         从请求头解析 Token / Access-Token / Uid
│   └── ChannelResolver       解析 app-name，识别来源站点
├── config/                 # 全局配置
│   ├── WebMvcConfig          拦截器、参数解析器注册
│   ├── AsyncConfig           SSE 与异步任务线程池
│   ├── JacksonConfig         时间格式、null 序列化策略
│   ├── CorsConfig
│   ├── OpenApiConfig
│   ├── JpaAuditingConfig     创建时间 / 更新时间自动填充
│   └── properties/           配置属性绑定
│       ├── AppProperties         站点与白标配置
│       ├── LlmProperties         上游模型接入参数
│       ├── NewApiProperties      New API 管理、Token 与余额查询参数
│       └── StorageProperties     对象存储参数
└── util/                   # 无状态工具
    ├── IdGenerator           业务 ID 生成
    ├── JsonUtils
    └── DateTimeUtils
```

### 4.2 modules —— 业务模块层

| 模块 | 职责 | 对接接口（对应线上路径） |
|---|---|---|
| `appconfig` | 站点初始化配置、开关下发、模型配置、广告位、多域名白标 | `POST /api/v2/config/init`、`GET /api/v2/config/controller`、`POST /api/v2/config/down`、`GET /api/v1/config/model/web`、`GET /api/v1/app/adverts` |
| `auth` | 登录注册全链路、验证码、微信扫码与小程序登录、绑定手机号 | `POST /api/v3/user/auth/login`、`send_sms`、`send_email`、`GET /wx.qrcode`、`POST /wx.query`、`wxMiniQrcode`、`wxMiniQuery`、`POST /api/v3/user/bind_phone` |
| `user` | 用户资料、VIP 状态、签到体系、账号迁移检查 | `GET /api/v3/user/info`、`GET /api/v1/user/info`、`GET /api/v3/user/vips`、`GET /api/v1/user/signin`、`signin.info`、`check.signin`、`POST /api/v2/user/signin/do`、`GET /api/v2/user/signin/ranking`、`GET /api/v3/user/system/check_migration` |
| `model` | 模型目录、模型元数据、消耗说明 | `POST /api/v1/chat2/getModels`、`POST /api/v2/points/models-intro` |
| `chat` | 对话核心：上下文组装、额度校验、流式转发、用量落库 | `POST /api/v1/chat2/v35`（主）、`POST /api/v1/chat2/zhipu`、`POST /api/v1/chat2/completionsModel` |
| `conversation` | 会话分组、群组对话、智能体对话、消息记录持久化 | `POST /api/v2/chat/groups/add`、`add_models`、`delete`、`reset`、`record/lists`、`record/delete`、`comment/lists`、`POST /api/v2/chat/agents/records_lists`、`delete_records`、`POST /api/v1/chat/record/add`、`list`、`delete`、`clear` |
| `file` | 文件上传、类型校验、文件转文本 | `POST /api/v2/file/upload`、`POST /api/v1/file/yfoo.file2text` |
| `gateway` | 平台用户到 New API 用户、PAT、Relay Token 的绑定；加密凭据、额度查询与快照 | `GET /api/v1/usage/token` |
| `share` | 分享页生成、微信链接生成 | `POST /api/v1/publics/share_html`、`POST /api/v1/wechat/generate_urllink` |
| `task` | 异步任务统一登记与查询 | `POST /api/v1/task/list` |

**实施进度**（按上表顺序推进）：

| 模块 | 状态 | 接口 |
|---|---|---|
| `appconfig` | 已实现 | `GET /api/v1/app/config`、`POST /api/v2/config/init`；广告位待办 |
| `chat` | 已实现（含 `infra/llm`） | `POST /api/v1/chat/completions`、`POST /api/v1/chat/stream`、`POST /api/v1/chat/requests/{requestId}/cancel` |
| `conversation` | 已实现（历史查询） | `GET/PATCH/DELETE /api/v1/conversations`、`GET /api/v1/conversations/{id}/messages`；分组与群组待办 |
| `auth` | 已实现（账号密码） | `POST /api/v1/auth/{register,login,refresh,logout}`、`GET /api/v1/auth/me`；验证码/第三方登录、限流与审计待办 |
| `file` | 已实现（上传/下载） | `POST/GET /api/v1/files`、`GET/DELETE /api/v1/files/{id}`、`GET /api/v1/files/{id}/content`；文件转文本与预签名直传待办 |
| `model` | 已实现（目录读取 + 统一模型解析） | `GET /api/v1/models`（支持 `?type=`）、`GET /api/v1/models/families`、`GET /api/v1/models/{modelKey}`；`ModelUseResolver` 是**唯一**的 key→上游解析点，类型与状态只判一次，对话与生图共用；缓存与同 key 多行优先级待办 |
| `gateway` | 已实现（用户级 New API Token 计费） | `GET /api/v1/usage/token`；首次调用自动创建 New API 用户、PAT 和 Relay Token；调用前检查用户余额，凭据 AES-256-GCM 加密落库 |
| `image` | 已实现（计费已接入，上游容量待恢复） | `POST /api/v1/images/generations`；仅接受 `IMAGE` 类型模型；使用当前用户 Relay Token；图生图、非 OpenAI 协议待办 |
| `share` | 已实现 | `POST/GET /api/v1/conversations/{id}/shares`、`GET /api/v1/shares/{token}`、`DELETE .../shares/{shareId}` |
| `admin` | 已实现（单一管理密钥） | `/api/admin/v1/providers/**`（含 `PUT /providers/{id}/status`、`?category=`）、`/api/admin/v1/models`（只读清单 + 显隐/展示字段单条与 `PUT /models/batch` 批量写入；目录增删改归 New API）、`/api/admin/v1/model-fallbacks/**`（含成员批量启停与批量移除）、`/api/admin/v1/sites`、`/api/admin/v1/model-groups`；RBAC、列表分页待办 |
| `infra/llm` 协议 | 已实现 6 种 | `OPENAI` / `OPENAI_COMPATIBLE` / `OLLAMA` / `AZURE_OPENAI` / `ANTHROPIC` / `GEMINI`；图像仍仅 OpenAI 形态 |
| `infra/queue` | 已实现（DB 表 + 派发器） | `publish` / `claim` / `complete` / `fail` / `release`，语义照着 AMQP 定；首个消费方是 `storage.delete`（删文件）。换 RabbitMQ 只改实现，见 `docs/queue.md` |
| `infra/redis` | 已实现（默认关闭） | 限流计数与进行中生成请求的跨实例账本、取消广播；`app.redis.enabled=true` 才启用，语义与失败行为见 `docs/redis.md` |
| 其余模块 | 未开始 | — |

> 账号归属：请求接口只通过 `Authorization: Bearer` 访问令牌解析账号，不接受 `X-User-Id`，也不会回退到演示账号；所有用户资源查询都按 `(资源 ID, 账号 ID)` 联合匹配，避免越权（见 `docs/auth-api.md` §8、`docs/conversation-api.md` §1）。
>
> 存储抽象：`file` 模块只依赖 `infra/storage/FileStorage` 端口，当前实现为本地磁盘 `LocalFileStorage`；接 S3/MinIO 时替换 Bean 即可，业务代码不动（见 `docs/file-api.md` §7）。
>
> `points_accounts`、`points_ledger`、`chat_usage` 及旧 `modules/points` 路径属于历史脚手架，
> 不参与当前运行时授权、扣费或余额计算。现行计费口径见 `docs/new-api-billing.md`。

### 4.3 infra —— 外部依赖适配层

| 适配器 | 职责 |
|---|---|
| `llm` | 上游大模型网关：多 provider 路由（DeepSeek / 智谱 / OpenAI 兼容）、流式增量解析、超时与重试、异常统一转换 |
| `newapi` | New API 管理端适配：用户/PAT/Relay Token 生命周期、Token usage 查询、错误与响应校验 |
| `storage` | 对象存储（图片/文件上传落地、签名 URL 生成） |
| `sms` | 短信与邮件发送通道 |
| `wechat` | 微信开放平台：扫码登录轮询、小程序登录、JS-SDK 配置 |

---

## 5. 模块内部约定

每个业务模块内部统一采用如下子包结构（以 `chat` 为例）：

```
modules/chat/
├── ChatController.java          # 主对话入口（SSE）
├── ChatZhipuController.java     # 智谱模型入口（SSE）
├── ChatCompletionsController.java
├── ChatService.java             # 业务编排
├── ChatStreamService.java       # 流式转发与分片下发
├── ChatContextAssembler.java    # 组装历史上下文
├── dto/                         # 出入参对象
│   ├── ChatRequest.java
│   ├── ChatStreamEvent.java
│   └── ChatModelVO.java
├── entity/                      # JPA 实体
│   └── ChatModelEntity.java
└── repository/                  # 数据访问
    └── ChatModelRepository.java
```

用户级余额校验和 Relay Token 注入位于 `modules/gateway/UserRelayCredentialResolver`，不是
`chat` 内部状态。模型调用的 token 计数只用于会话展示和审计，不再触发本地扣费。

约定细则：

- **不需要持久化的模块**可省略 `entity/` 与 `repository/`（如 `appconfig`、`share`）。
- **DTO 一律放 `dto/`**，不直接暴露 `entity/` 给 Controller。
- **跨模块复用**的接口放到被依赖方的包内，通过 Service 调用，不跨包直接引用 `repository`。
- **流式接口**统一用 Spring MVC 的 `SseEmitter`（项目当前是 WebMVC 栈，不引入 WebFlux），由 `common/config/AsyncConfig` 提供专用线程池。

---

## 6. 资源目录规划

```
src/main/resources/
├── application.yml                 # 公共配置（不含敏感信息）
├── application-local.yml           # 本地开发（H2 或本机 MySQL）
├── application-mysql.yml           # MySQL 连接（已存在，沿用环境变量占位）
├── application-prod.yml            # 生产环境
├── logback-spring.xml              # 日志配置
├── db/migration/                   # Flyway 迁移脚本，命名 V<版本>__<描述>.sql
│   └── (待排期编写)
└── prompts/                        # 提示词模板，按场景分文件
    └── (待排期编写)
```

数据库迁移采用 **Flyway**，与 JPA 的 `ddl-auto` 解耦：`local` 用 `ddl-auto: update` 快速迭代，`mysql/prod` 用 `ddl-auto: validate` + Flyway 管版本。

---

## 7. 基础设施与依赖规划

`pom.xml` 现有依赖已覆盖 Web / Validation / Actuator / JPA / H2 / MySQL / Springdoc。规划新增：

| 依赖 | 用途 | 必要性 |
|---|---|---|
| `flyway-core` + `flyway-mysql` | 数据库版本管理 | 必需 |
| `spring-boot-starter-data-redis` | Token 会话、验证码、限流、热点配置缓存 | 必需 |
| JDK 内置 `java.net.http.HttpClient` | 上游模型流式调用（HTTP/2 + SSE），避免引入 WebFlux | 无需新增依赖 |
| `spring-boot-starter-aop` | 计费检查审计、操作日志切面 | 建议 |
| `mapstruct` 或手写转换器 | DTO ↔ Entity 转换 | 可选 |

---

## 8. 扩展位设计

后续能力接入时的挂载位置，无需调整既有分层：

| 后续能力 | 新增模块 | 说明 |
|---|---|---|
| 绘画 / 图像处理 | `modules/image` | 复用 `infra/storage` 与 `infra/llm` |
| 文档翻译 / PDF 工具 | `modules/document` | 复用 `infra/storage`，异步走 `task` |
| PPT 生成 | `modules/ppt` | 复用 `infra/storage` |
| 视频生成 | `modules/video` | 复用 `task` 做进度轮询 |
| 语音合成 / 声音克隆 | `modules/voice` | 复用 `infra/storage` |
| VIP 与订单支付 | `modules/order` | 订单与权益独立建模；实际 AI 用量和余额由 New API 管理 |
| 积分商城 / 分销活动 | `modules/activity` | 只维护业务活动与订单权益，不复制 New API 余额或扣费流水 |
| 导航与收藏 | `modules/navigation` | 独立模块 |

---

## 9. 已确认事项

1. **鉴权**：HTTP 请求身份只使用 `Authorization: Bearer <accessToken>`；请求接口不接受 `X-User-Id`，也不会回退到演示账号。
2. **多租户**：`app-name`、Host 或默认站点解析出的 `siteId` 是站点隔离边界，用户资源按 `(资源 ID, user_id)` 校验。
3. **计费**：不建立本地积分、余额或扣费流水。每个平台用户绑定一个 New API 用户和 Relay Token，余额与扣费以 New API 为准。
4. **Redis**：仍是后续用于共享限流、验证码和热点缓存的部署依赖；当前注册限流为单进程实现。
