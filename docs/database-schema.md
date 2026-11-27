# `/newchat` 与智能体数据库结构

该结构按 `https://ai1foo.com/newchat`、`https://ai1foo.com/customAgent/index` 的公开功能和仓库中的接口规划设计，目标数据库为 MySQL 8.0.46，库名 `backend`，字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`。

迁移文件为 `src/main/resources/db/migration/` 下的 `V1`…`V22`，通过 Flyway 执行。脚本只创建表、索引和外键以及幂等的种子数据，不删除或重命名已有对象。

`V2` 预填充内容（全部幂等，重复执行安全）：

- `user_accounts`：站点 `default` 下的 `demo` 种子账号，用于本地数据和历史兼容路径。HTTP 请求身份只由 `Authorization: Bearer` 解析，不会在未认证时回退到该账号。
- `llm_model_groups`：`demo-relay` 分组（示例中转）。
- `llm_models`：`deepseek-v4.1-flash`、`deepseek-v4.1-flash-0910`、`deepseek-v4-flash-0731`、`deepseek-v4-pro-0813`，`route` 与实际上游模型名一致。

`V3` 新增内容：

- `app_site_settings`：站点级运行时设置（`site_id` + `setting_key` 唯一），`feature.` 前缀为功能开关命名空间，另有 `announcement`、`default_model`。同时为 `default` 站点预置了默认开关、公告与默认模型。
- 该表替代直接读写 `app_sites.config_json`：JSON 列在 Hibernate 与 MySQL/H2 之间存在类型码不一致，`ddl-auto=validate` 下会启动失败，且整块 JSON 无法按 key 更新与审计。`config_json` 保留给低频、无需查询的站点载荷。

`V4` 新增内容：

- `llm_model_tags`：模型的展示标签（`model_id` + `tag` 唯一，带 `sort_order`），并为已预填充的 4 个模型写入了开发用标签。
- 同理替代 `llm_models.tags_json`：JSON 列既无法可靠映射，也无法按标签过滤。`tags_json` 保留但**不使用**。

`V5` ~ `V8` 新增内容：

| 版本 | 内容 |
|---|---|
| `V5` | `conversation_shares.snapshot_json`（`LONGTEXT`）：分享时落一份冻结快照 |
| `V6` | 新建 `llm_providers`：供应商配置（`provider_key` 唯一、`protocol`/`base_url`/`endpoint_path`/`api_version`、`auth_type`、`credential_ref` + `credential_fingerprint`、`status`、`metadata_json`） |
| `V7` | `llm_models` 加 `model_type`（`CHAT`/`IMAGE`/`VIDEO`/`AUDIO`，默认 `CHAT`）与 `provider_id`（外键指向 `llm_providers`）；`llm_providers` 加 **`api_key TEXT`（明文）**；种子一条 `demo-relay` 供应商（密钥留空）并把 `gpt-image-2` 登记为 `IMAGE` 模型 |
| `V8` | `llm_providers` 加 `category`（`CHAT`/`IMAGE`/`VIDEO`/`AUDIO`/`OTHER`，默认 `CHAT`）与 `images_path`（可空，为空则用 `app.llm.images-path`）；`llm_models` 加 `context_window`、`max_output_tokens`、`supports_vision`、`supports_tools`、`input_points_per_1k`、`output_points_per_1k` |
| `V72` | `llm_models.supports_web_search`：上游原生联网门控；并对 `gpt-%` CHAT 模型默认打开 |
| `V78` | `llm_providers.video_download_base_url`（可空，`VARCHAR(1024)`）：视频成品下载源。任务插件只返回不含主机名的相对路径（`/v1/videos/{id}/content`），中继又不代理这类内容，所以在供应商行声明真正的文件宿主；为空则维持"按提交地址（中继）解析"，行为与之前完全一致。⚠️ 配套密钥不要写在同一行的 `api_key` 上——中继供应商行每次目录同步都会被 `setApiKey(null)`，活不过 5 分钟 |
| `V79` | `ai_canvas_assets.source_file_id`（`BIGINT UNSIGNED`，可空）：画布素材改为可以只存引用，不再复制字节；配套把 `storage_key` 改为可空，两类行互斥（自有字节的有 `storage_key`，引用型的有 `source_file_id`） |
| `V80` | 清理 V25 留下的占位视频目录：只删 `provider_key = 'video'` 下的四个演示模型（`seedance-2.0`/`kling-2.5`/`wan-2.5`/`hailuo-02`），无剩余模型时才移除合成供应商 |
| `V81` | 绘画工具库（复用 `commerce_tools`，靠 `scene` 区分场景）：4 分类 × 4 工具 |
| `V82` | 绘画工具库第二批 6 个工具，`form_schema` 与 V81 同结构 |
| `V83` | `llm_providers.video_download_api_key`（可空，`TEXT`）：与 `video_download_base_url` 配对的取件密钥。**不在 `ensurePublicProvider()` 的重置清单里**，因此 origin 与 key 都成为可持久的行数据，`LLM_VIDEO_DOWNLOAD_API_KEY` 降级为老部署的兼容回落（解析顺序：本列 → 行上提交密钥 → 环境变量 → 无）。配套管理端点 `PUT /api/admin/v1/providers/{id}/video-download-api-key`，响应只回 `videoDownloadApiKeySet` 布尔值，密钥永不回显 |

`V9` ~ `V13` 新增内容：

| 版本 | 内容 |
|---|---|
| `V9` | 建立 `https://ai1foo.com/customAgent/index` 对应的智能体目录、标签、能力、可选模型、开场问题、收藏和独立会话结构；扩展 `agents`，并在 `agent_runs` 上增加独立智能体会话/消息关联 |
| `V10` | 导入 `https://github.com/bjlida/AI-IDE-Agent` 的 72 个提示词，按 7 个来源目录建立分类；源 front matter 有 `description` 时写入简介，没有时写空字符串 |
| `V11` | 以 `VARCHAR` 过渡方式增加 `agents.ui_type`；前端打包代码确认页面值实际为 `1=common、2=custom`，最终类型在 `V12` 修正 |
| `V12` | 将 `ui_type` 修正为 `TINYINT UNSIGNED`，补齐 `scope`、`classify`、`child_classify`、`chat_suggest`、`ui_bg`；回填导入记录的 `sn = agent_key`、`classify = source_path` 一级目录，并把已发布公开记录设为 `scope=1` |
| `V13` | 导入 `https://github.com/momozi1996/awesome-ai-persona-skills` 的 64 个人格，按 4 个来源分类建立 `agent_categories`，并写入标签、简介、开场白与每个 3 条开场问题 |

`V14` ~ `V22` 新增内容：

| 版本 | 内容 |
|---|---|
| `V14` | 新增 `smart_edit_records`，记录智能修图任务与结果资源 |
| `V15` | `uploaded_files` 新增 `asset_type`，按 `reference` / `generated` / `edit_result` / `chat_attachment` 区分资源用途；旧数据按服务端生成文件名回填，并新增 `(user_id, asset_type, created_at)` 索引 |
| `V16` | 新增 AI Canvas 工作流、素材、灵感会话/消息与异步任务五张表，并预置公开示例工作流 |
| `V17` | 新增 `site_banners`，保存站点级或全局活动 Banner、投放时间、状态与排序 |
| `V18` | 新增 `user_gateway_tokens`，保存平台用户到 New API Relay Token 的绑定、加密凭据、创建租约、重试状态和额度快照 |
| `V19` | 将 Relay Token 密文改为 `TINYBLOB`，与 AES-GCM 密文的 JPA 映射保持一致 |
| `V20` | 将 nonce 与认证标签改为二进制列，避免 Base64 与二进制映射不一致 |
| `V21` | 将 `model_limits` 改为 `TINYTEXT`，与 New API 返回的 JSON 文本长度及 JPA 映射对齐 |
| `V22` | 为用户网关绑定补充 `newapi_user_id`、PAT 及其加密字段、唯一约束和额度查询错误状态 |
| `V72` | 新增 `newapi_accounts`，保存平台账号到 New API 用户名的唯一映射与加密的镜像密码（本站密码即 New API 密码） |
| `V73` | 用户名改为与本站登录名同源，清空旧的 `hub-u{id}` 派生映射（`newapi_accounts` 与 `user_gateway_tokens`），由下次登录自动重建 |
| `V74` | `newapi_accounts` 增加 `trusted`：区分"归属已证明的绑定"与"仅同名"，后者不得用于改写账号密码 |
| `V104` | `ppt_jobs` 增加 `lease_owner` / `lease_until` 与 `(status, updated_at)` 索引，让重启后能被重新捡起来 |
| `V105` | 新增 `task_queue`：事务性发件箱式的通用任务队列（claim / 租约 / 重试） |
| `V106` | 新增 `shedlock`：定时任务的多实例互斥表 |
| `V107` | 新增 `newapi_groups`（站点的可选分组、倍率与是否仍可用）与 `llm_models.upstream_groups`（模型自己的 `enable_groups`） |
| `V108` | 新增 `newapi_group_tokens`：每用户每分组一把 Relay Token 的加密密文、租约与重试状态（用到该分组的模型时才建） |

> `V7` 的 `api_key` 是显式的产品取舍（明文，便于控制台回填），详见 `docs/provider-admin-api.md` §4。
> `credential_ref` 机制保留：密钥改填 `env:XXX` 并清空 `api_key` 即可回到环境变量，无需改代码。

## 智能体目录与独立会话

智能体页面沿用已有 `agents` 表作为唯一主体，不另建一套重复的 Agent 表。页面字段与数据库字段的映射如下：

| `customAgent` 页面字段 | 数据库落点 | 说明 |
|---|---|---|
| `sn` | `agents.sn` | 详情页/前端路由使用的稳定标识 |
| `scope` | `agents.scope` | `0=私密、1=公开` |
| `classify` / `child_classify` | `agents.classify` / `agents.child_classify` | 页面级一级、二级分类原始值；内部规范化分类仍用 `agents.category_id` |
| `name` | `agents.name` | 智能体名称 |
| `avatar` | `agents.icon_url` | 头像 URL；导入提示词时留空 |
| `intro` | `agents.description` | 简介；源数据缺失时写空字符串 |
| `prompt` | `agents.system_prompt` | 完整系统提示词 |
| `labels` | `agent_tags` | 独立表，便于按标签筛选 |
| `call_tools` | `agent_abilities` | 工具白名单及可选配置 |
| 多模型选择 | `agent_models` | 关联 `llm_models`，每项可标记默认模型 |
| `chat_questions` | `agent_questions`，`question_type='OPENING'` | 聊天开场问题 |
| `chat_suggest` | `agents.chat_suggest` | 是否启用后续问题生成，布尔值；不是问题内容列表 |
| `ui_config.chat_prologue` | `agents.chat_prologue` | 聊天开场白 |
| `ui_config.forms`、`tip_struct`、`exampleUrls` | `agents.ui_config_json` | 低频展示 JSON，不做 JPA 默认映射 |
| `ui_bg` | `agents.ui_bg` | 页面背景，可为 URL 或短 CSS 值 |
| `ui_type` | `agents.ui_type` | `1=common、2=custom`；导入记录默认 `1` |
| `type` | `agents.agent_type` | `OFFICIAL` 或 `CUSTOM` |
| `score` / `seeCount` | `agents.score` / `agents.see_count` | 评分与浏览统计 |
| `is_collect` | `agent_favorites` | 收藏明细是事实表，`agents.favorite_count` 只是计数缓存 |
| `owner_user_id` | `agents.owner_user_id` | 空值表示官方记录，用于“我创建的”筛选 |

导入来源和历史版本单独保留：

- `agents.source_model`：源 front matter 中的 `model`，例如 `sonnet`。
- `agents.source_path`：源仓库相对路径，例如 `编程语言专家/Python专家.md`。
- `agents.publish_status`、`visibility`、`is_hot`、`published_at`：目录发布、可见性和热门排序所需状态。

### 表关系

```text
agent_categories 1 ───── N agents
agents           1 ───── N agent_tags
agents           1 ───── N agent_abilities
agents           1 ───── N agent_models N ───── 1 llm_models
agents           1 ───── N agent_questions
agents           1 ───── N agent_favorites N ───── 1 user_accounts

user_accounts      1 ───── N agent_conversations
agents             1 ───── N agent_conversations
agent_conversations 1 ─── N agent_chat_messages
agent_chat_messages 1 ─── N agent_message_feedback

agent_runs ── agent_conversation_id ──> agent_conversations
           └─ agent_message_id      ──> agent_chat_messages
```

`agent_conversations` 在开始时写入 `agent_snapshot_json`，冻结当时的提示词、开场白和模型配置，避免之后编辑智能体改变历史会话语义。`agent_chat_messages.sequence_no` 与普通消息一样按会话内序号唯一。

### 为什么智能体对话不写入 `conversations`

普通 `/newchat` 与智能体聊天使用两套会话边界：

| 业务 | 会话表 | 消息表 | 反馈表 |
|---|---|---|---|
| 普通聊天/群聊 | `conversations` | `chat_messages` | `message_feedback` |
| 智能体聊天 | `agent_conversations` | `agent_chat_messages` | `agent_message_feedback` |

这样可以分别实现智能体历史列表、按智能体清空、删除单个智能体会话等接口，不会误删普通聊天；反过来清理普通聊天也不会碰到智能体会话。V1 中为兼容保留的 `conversations.agent_id` / `agent_key` 不再作为新智能体流程的落点。`agent_runs` 的新增字段只连接独立的智能体会话与消息，旧 `conversation_id` 变为可空。

### 提示词与人格库导入

`tools/generate-agent-library-sql.ps1` 读取 `bjlida/AI-IDE-Agent` 的分类 Markdown，生成 `V10__seed_agent_library.sql`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate-agent-library-sql.ps1 `
  -LibraryRoot D:\hub\AI-IDE-Agent
```

生成物使用 UTF-8、无 BOM，按 `(site_id, agent_key)` 幂等 upsert。每条记录使用 front matter 的 `name` 同时写入 `agent_key` 和 `sn`。已执行的 `V10` 没有写这两个页面查询所需的分类字段，因此 `V12` 会为现有 72 条记录回填 `sn`，并从 `source_path` 的一级目录回填 `classify`，使其可直接由 `/customDetail/index?id={sn}` 打开和按一级分类筛选。导入负责名称、简介、提示词、来源模型和来源路径；头像、标签、工具、可选模型未包含在源仓库中，保持为空，由运营后台补充。

`tools/generate-persona-library-sql.ps1` 读取 `momozi1996/awesome-ai-persona-skills` 的人格 Markdown，生成 `V13__seed_persona_library.sql`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate-persona-library-sql.ps1 `
  -LibraryRoot D:\hub\awesome-ai-persona-skills -Version V13
```

分类清单与中文展示值放在 `tools/persona-library-categories.json`：源 Markdown 与脚本保持 ASCII，脚本显式以 UTF-8 读取 JSON 和 Markdown，避免 Windows PowerShell 5.1 按 ANSI 代码页误读。生成物同样使用 UTF-8、无 BOM，按 `(site_id, agent_key)` 幂等 upsert，可重复执行。

本批共导入 64 个人格，按 4 个来源分类建立目录：

| `category_key` | 名称 | 数量 | `classify` / `child_classify` |
|---|---|---|---|
| `director-personas` | 导演思维 | 31 | 创作人格 / 导演思维 |
| `writer-personas` | 作家思维 | 17 | 创作人格 / 作家思维 |
| `media-personas` | 自媒体人格 | 12 | 内容行业 / 自媒体人格 |
| `tianya-personas` | 天涯大神 | 4 | 社会观察 / 天涯大神 |

写入规则：`agent_type='OFFICIAL'`、`ui_type=1`、`scope=1`、`chat_suggest=1`，`visibility='PUBLIC'`、`publish_status='PUBLISHED'`、`status='ACTIVE'`、`score=5.00`；`sn` 取 `agent_key`；`name` 取来源标题；`description` 取 front matter 摘要（导演类按分类模板回退为全中文简介）；`system_prompt` 为完整的 Markdown 正文；标签来自 front matter 的“擅长”字段或导演核心创作标题；`chat_prologue` 优先取正文引语，缺失时使用分类模板；每个新人格写入 3 条 `SUGGEST` 问题。头像不在来源仓库中，保持为空，由运营后台补充。

去重与范围：排除 `Novelists/SKILL.md`、`tianya-gods-framework/gods/dangnianmingyue.md` 与 `tianya-gods-framework/gods/README.md`，保留对应子目录人格，避免同一人格重复入库；跳过 `EQ` 与外部项目清单。

`V8` 的两处语义值得记一笔：

- **`category` 是描述性的**：它只决定控制台把供应商归到哪个页签。真正决定"某个模型能不能被某个接口调用"的是 `llm_models.model_type`，每次调用都校验。把分类做成供应商表的一列，而不是给绘画供应商再建一张表，是为了不产生第二套增删改查与凭据解析。
- **`V8` 的 backfill 不是默认值**：迁移会按"该供应商名下是否已有 `IMAGE` 模型"把它标成 `IMAGE`，因此升级后本机的 `demo-relay-image` 自动成为图像分类，`demo-relay` 保持 `CHAT`。
- **能力字段的 `NULL` 与 `0` 意义不同**：`NULL` 表示"未登记"，`0` 表示"确实是零"。控制台对前者留空，避免把一个没填的字段显示成"该模型不支持视觉 / 零成本"。

## 已启用的 V1 表

V1 建的表正在被逐步接入，接入时若列类型与 JPA 默认映射不一致，需要显式声明：

| 表 | 启用阶段 | 注意 |
| --- | --- | --- |
| `conversations` / `chat_messages` | 对话与历史查询 | `chat_messages.content` 为 `LONGTEXT`，实体须显式 `columnDefinition` |
| `uploaded_files` | 附件上传 | `asset_type` 由 `V15` 增加；`checksum_sha256` 为 `CHAR(64)`，实体须显式 `columnDefinition = "CHAR(64)"`；`extracted_text` 暂未映射 |
| `user_accounts` / `app_sites` / `llm_models` | 账号解析、站点识别、模型目录 | — |
| `user_identities` | 账号密码注册/登录 | `provider_profile_json` 为 JSON，**未映射**；`(provider, provider_subject)` 是全局唯一键，密码身份以 `{siteKey}:{username}` 命名空间 |
| `auth_sessions` | 令牌会话 | `token_hash` 为 `CHAR(64)`，须显式 `columnDefinition`；只存 refresh token 的 SHA-256 |
| `llm_models` / `llm_model_groups` / `llm_model_tags` | 模型目录 | `llm_models.tags_json` 与 `llm_model_groups.is_classify`/`is_group` 均未映射；标签走 V4 的独立表 |

> 通用规则：MySQL 的 `CHAR` / `TEXT` / `LONGTEXT` / `JSON` 与 Hibernate 默认推导出的类型存在类型码差异，`ddl-auto=validate` 会直接拒绝启动。凡此类列一律在 `@Column` 上写 `columnDefinition`，不要依赖默认映射。

## 领域表

| 领域 | 表 | 用途 |
| --- | --- | --- |
| 站点与账号 | `app_sites`、`user_accounts`、`user_identities`、`auth_sessions`、`verification_codes` | 多站点配置、账号、微信/第三方身份、登录会话、验证码 |
| 模型与 Agent | `llm_model_groups`、`llm_models`、`agents`、`agent_categories`、`agent_tags`、`agent_abilities`、`agent_models`、`agent_questions`、`agent_favorites` | 模型分类、模型 key、厂商、智能体定义、目录分类、标签、工具、可选模型、问题与收藏 |
| 普通会话 | `conversation_groups`、`conversations`、`conversation_group_models` | 普通聊天、群聊分组及群聊模型组合 |
| 智能体会话 | `agent_conversations`、`agent_chat_messages`、`agent_message_feedback`、`agent_runs` | 智能体独立历史、消息、消息反馈及执行记录 |
| 普通消息 | `chat_messages`、`message_feedback` | 普通聊天的用户/助手/系统消息、思考内容、序号、token 用量、错误状态和评价 |
| 文件 | `uploaded_files`、`message_attachments` | 上传文件、抽取文本及消息附件关联 |
| 用户计费绑定 | `user_gateway_tokens`、`newapi_group_tokens`、`newapi_accounts` | 平台用户与 New API 用户、PAT、Relay Token 的绑定，加密凭据、租约与额度快照；`newapi_group_tokens` 存「该用户在某个 New API 分组上的 Token」，按 `(user_id, group_key)` 唯一；`newapi_accounts` 另存 New API 用户名与加密的镜像密码 |
| AI Canvas | `ai_canvas_workflows`、`ai_canvas_assets`、`ai_canvas_chat_sessions`、`ai_canvas_chat_messages`、`ai_canvas_tasks` | 工作流、素材、灵感会话和生成任务 |
| AI PPT | `ppt_jobs` | 生成任务生命周期、进度与产物；`lease_owner` / `lease_until` 记录执行者与租约，恢复扫描据此判断「执行者已退出」 |
| 站点展示 | `site_banners` | 站点级或全局活动 Banner |
| 异步与分享 | `async_tasks`、`conversation_shares` | 长任务进度、对话分享链接 |
| 通用任务队列 | `task_queue` | 副作用型任务的投递与重试（删文件、发通知、补偿）；`payload` 为 `LONGTEXT`，由调用方自定格式，队列不解析 |
| 定时任务锁 | `shedlock` | ShedLock 的租约表（`V106`），只由它自己读写；多实例下保证定时任务同时只有一个实例在跑，见 `docs/scheduled-tasks.md` |

`task_queue`（`V105`）是队列适配器自己的存储，不是业务表：`publish` 与业务写在同一个事务里
（业务回滚则消息不存在），`claim` 用条件 UPDATE 抢占，`attempts` 在领取时 +1，租约过期由
`releaseExpired` 回收。语义与换 RabbitMQ 之后的映射见 `docs/queue.md`。

`ppt_jobs` 的任务队列语义（`V104`）：建单只写一行 `queued`，真正执行前先原子领取（`queued → parsing` 并写租约），因此恢复扫描不会把线程池里排队的任务当成丢失。每轮扫描先判死「租约过期却仍在跑」的行，再判死「在队列里躺了超过 `queued-max-age`」的历史残骸，最后才重新提交剩下的任务；租约在每次写进度时顺带续期，所以过期等价于「执行者已退出」而不是「跑得慢」。**租约时长必须大于 `generate-timeout`（默认 45m，租约 90m）**，否则一次缓慢的上游调用会被误判成中断。

计费与余额的权威来源是 New API。`user_gateway_tokens.total_*` 只是 `data.user_quota.total_used` 与 `data.user_quota.total_available` 的展示快照，不参与本地扣费；本地不建立第二套余额或扣费流水。`points_accounts`、`points_ledger`、`chat_usage` 是历史/未使用脚手架，当前运行时不会写入或据此扣费。

`chat_messages` 与 `agent_chat_messages` 中的 token 数只用于会话展示、取消/断连后的审计与用量核对，不作为再次扣费依据。所有业务表使用 InnoDB，时间统一 `DATETIME(6)`；涉及货币或数量的历史字段使用 `DECIMAL`/整数，不使用浮点数。

## 执行方式

PowerShell 中：

```powershell
$env:JAVA_HOME = 'D:\env\java-tools\jdk-17'
$env:DB_HOST = '127.0.0.1'
$env:DB_PORT = '3306'
$env:DB_NAME = 'backend'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = 'root'
.\mvnw.cmd spring-boot:run
```

默认 profile 就是 `mysql`，不设置 `SPRING_PROFILES_ACTIVE` 也会连接本机 `backend` 库。
应用启动时会执行 Flyway 迁移，并使用 `ddl-auto=validate` 校验 JPA 映射；重启不会重建
数据库。测试通过 `src/test/resources/application.properties` 激活 `test` profile，继续使用
内存 H2，不会访问本机 MySQL。迁移历史保存在 `flyway_schema_history`，不要手工修改已执行
版本；新增结构请继续增加后续 `V*.sql`。

**拉取远端后先跑一次 `tools/check-migrations.sh origin/main`**：两个分支各自新增 `V98` 时 git
合并不会有任何提示，两个文件都在、只是版本号相同，要到应用启动才报
「Found more than one migration with version 98」。撞号时的处理是把本地那份顺延到远端最大值
之后（本仓库 `V104`…`V106` 就是这样从 `V98`…`V100` 顺延来的），不要改已执行过的版本号。
另外 `target/classes` 是增量拷贝的，改名后必须 `mvn clean`，否则旧文件仍留在测试/运行用的
类路径上。

查看表：

```powershell
& 'D:\software\MySQL\bin\mysql.exe' --protocol=TCP --host=localhost --port=3306 --user=$env:DB_USERNAME --password=$env:DB_PASSWORD --database=$env:DB_NAME --execute="SHOW TABLES;"
```
