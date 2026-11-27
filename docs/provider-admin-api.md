# 供应商与模型管理接口（后台）

> 状态：已实现并在真实 MySQL 上跑通（V5/V6/V7/V8 迁移 + 匿名模型广场同步验证）。
> 涉及代码：`modules/admin`（HTTP 管理面）、`modules/model/provider`（供应商配置与凭据解析）、`infra/llm`（上游适配）。
> 迁移：`V6__llm_providers.sql`、`V7__model_type_and_provider_api_key.sql`、`V8__provider_category_and_model_capabilities.sql`。

---

## 1. 接口总览

统一前缀 `/api/admin/v1`，与业务接口的 `/api/v1` 分开，便于网关单独限制。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/providers` | 供应商列表，可按 `protocol` / `status` / `category` 过滤 |
| `GET` | `/providers/{id}` | 供应商详情 |
| `PUT` | `/providers/{id}/video-download-base-url` | **只设置视频成品下载源地址**，其余字段不动 |
| `POST` | `/providers/sync` | 立即匿名同步 New API 公开模型广场 |
| `POST` | `/providers` | 创建供应商（可直接带明文密钥） |
| `PUT` | `/providers/{id}` | 更新供应商 |
| `PUT` | `/providers/{id}/status` | **只切换启停**，其余字段不动 |
| `DELETE` | `/providers/{id}` | 删除供应商 |
| `GET` | `/providers/forms` | 支持的协议模板（OpenAI / Anthropic / Gemini / Ollama / Azure） |
| `GET` | `/providers/{id}/models` | **用该供应商已存的密钥拉取上游可用模型列表** |
| `GET` | `/models` | **后台模型清单**：全部模型（含停用），带类型、`route` 与所属供应商 |
| `POST` | `/models/sync` | 立即匿名同步 New API 公开模型广场 |
| `POST` | `/models` | **创建模型** |
| `PUT` | `/models/{id}` | **更新模型**（整体替换） |
| `DELETE` | `/models/{id}` | **删除模型**（被历史引用时拒绝） |
| `GET` | `/sites` | **站点列表**，模型表单的 `siteId` 可选项 |
| `GET` | `/model-groups` | **模型分组列表**，模型表单的 `groupId` 可选项 |

## 2. 鉴权

```
X-Admin-Key: <app.admin.api-key>
```

所有 `/api/admin/**` 接口都强制校验 `X-Admin-Key`。服务端未配置非空的
`app.admin.api-key`（环境变量 `ADMIN_API_KEY`）时，管理接口一律返回 `401`，不会因为缺少
配置而放行。`app.admin.require-api-key` 仅为旧配置绑定兼容保留，设为 `false` 也不能关闭校验。
这是 admin RBAC 落地前的临时闸门，**生产必须替换成真正的 RBAC**。

```bash
curl -s -H 'X-Admin-Key: local-admin-key' http://localhost:8080/api/admin/v1/models
```

## 3. 模型类型

`llm_models.model_type` 取值：

| 值 | 用途 | 是否有接口 |
|---|---|---|
| `CHAT` | 对话 | `POST /api/v1/chat/stream`、`/completions` |
| `IMAGE` | 图片 | `POST /api/v1/images/generations` |
| `VIDEO` | 视频 | **保留值，暂无接口** |
| `AUDIO` | 音频 | **保留值，暂无接口** |

公开目录也暴露该字段，并可用 `?type=` 过滤（**主列表与模型家族都支持**）：

```bash
curl -s 'http://localhost:8080/api/v1/models?type=IMAGE'
curl -s 'http://localhost:8080/api/v1/models?type=CHAT'
curl -s 'http://localhost:8080/api/v1/models/families?type=CHAT'
```

这样对话选择器不会列出图片模型，图片面板也不会列出对话模型。

**类型不是只有展示作用**：调用时会强制校验——对话接口只跑 `CHAT`、`POST /api/v1/images/generations` 只跑 `IMAGE`，用错返回 `400` 且不产生上游成本。详见 `docs/model-catalog-api.md`「`modelType` 不只是展示字段」。

### 一个供应商通常只承载一种类型

网关按 **group** 隔离密钥与模型，**同一把密钥看不到另一个 group 的模型**（`GET {base}/models` 就是最直接的验证手段）。因此：

- 图像模型要指向**图像供应商**（存图像 group 的那把密钥），指向文本供应商会得到 `404 model_not_found`；
- 判断该用哪把密钥，用 `GET /api/admin/v1/providers/{id}/models` 看一眼它能看到什么模型即可，不必猜。

本机示例：`demo-relay`（文本，4 个 `deepseek-*`）与 `demo-relay-image`（图像，3 个 `gpt-image-*`）两个供应商分开，各挂各的模型。

## 4. 明文密钥

### 存储形式

`llm_providers.api_key` **明文存储**，原样返回给管理接口，便于控制台回填编辑。这是明确的产品取舍：控制台要能读回并修改供应商，运维也不想再引入第二个密钥存储。

### 取值优先级

调用上游时按以下顺序取密钥，第一个非空者生效：

1. `llm_providers.api_key`（控制台手填，明文）
2. `llm_providers.credential_ref` 且形如 `env:NAME` → 读环境变量
3. 服务端配置 `app.llm.api-key`

以上是底层 LLM 客户端的宿主/兼容取值链，只适用于没有显式传入账户凭据的调用。

**面向用户的 AI 调用不走这条密钥链。** 普通对话、Agent、图片生成、智能修图和 AI Canvas 都会通过 `UserRelayCredentialResolver` 注入当前用户的 New API Relay Token，覆盖供应商密钥与 `app.llm.api-key`。因此：

- `app.llm.api-key` 不是用户计费凭据；
- 图片链路没有全局图片 Key，也不会回退到供应商 Key；
- 用户余额、扣费和模型分组由 New API 管理，详见 `docs/new-api-billing.md`。

控制台里的供应商密钥仍用于底层协议验证和宿主兼容调用。把某个模型的 `provider_id` 指向没填密钥的供应商不会再影响用户 AI 调用。

> 实测验证（伪造密钥法）：把供应商密钥改成 `sk-bogus-...` 后，对话调用返回上游
> `401 INVALID_API_KEY`；而服务端配置里的密钥是有效的。这证明调用用的确实是库里存的那把，
> 不是配置里那把。

### `apiKey` 的更新语义

| 提交的值 | 结果 |
|---|---|
| 非空字符串 | 覆盖为新值 |
| `null`（字段缺省） | **保持不变** —— 控制台改个 baseUrl 不必重发密钥 |
| 空字符串 `""` | 清除（回退到下一优先级） |

### ⚠️ 明文存储的代价

必须清楚这几条，它们不是理论风险：

- **数据库可读即可用**：任何能读到该表的途径（备份文件、只读从库、慢查询日志、运维工单导出、误加的日志）都会带出密钥；
- **接口会返回密钥**：抓包、前端日志、截图、浏览器扩展都可能把它带走；
- **备份与快照长期留存**：即使之后改成密文，历史备份里的明文仍在；
- **轮换成本低是唯一的好消息**：改一行即可，因此建议定期轮换。

要收口时的做法（不需要改代码）：把密钥改填成 `credential_ref = env:XXX` 并清空 `api_key`，密钥回到环境变量。规划中的 `credential_ref` + 指纹机制仍然保留着，就是为这一步留的。

## 5. 协议兼容

`llm_providers.protocol` **此前只是存着，没有任何代码读它**：所有供应商都被当成 OpenAI 兼容网关调用，
因此配成 `ANTHROPIC` 或 `GEMINI` 的供应商会收到对方解析不了的请求体。现在这个字段决定实际发出的报文。

| protocol | 路径 | 鉴权 | 流式 |
|---|---|---|---|
| `OPENAI` / `OPENAI_COMPATIBLE` | `{baseUrl}{endpointPath 或 /chat/completions}` | `Authorization: Bearer` | ✅ |
| `OLLAMA` | 同上；**允许不带密钥** | 有密钥时才发 Bearer | ✅ |
| `AZURE_OPENAI` | `{baseUrl}/openai/deployments/{model}/chat/completions?api-version={apiVersion}` | `api-key:` 头 | ✅ |
| `ANTHROPIC` | `{baseUrl}{endpointPath 或 /v1/messages}` | `x-api-key` + `anthropic-version` | ✅ |
| `GEMINI` | `{baseUrl}{endpointPath 或 /v1beta/models}/{model}:generateContent`（流式加 `:streamGenerateContent?alt=sse`） | `x-goog-api-key` 头 | ✅ |

**取值未识别时回退为 `OPENAI_COMPATIBLE`**——也就是改造之前所有供应商的行为，不会因为多打了一个枚举值就报错。

各协议的三处实质差异都已显式处理：

- **Anthropic**：`system` 是顶层字段而非消息（放在 `messages` 里会被拒）；`max_tokens` 必填，缺省补 4096；
  流式帧按 `type` 区分（`content_block_delta` / `message_delta` / `message_stop`，`ping` 等忽略）。
- **Gemini**：消息变成 `contents`，助手角色叫 `model`，system 走 `systemInstruction`，生成参数放 `generationConfig`；
  密钥走 `x-goog-api-key` 头而不是查询串，避免密钥进访问日志。
- **Azure**：路径要带 deployment 与 `api-version`，鉴权用 `api-key` 头。**未配置 `apiVersion` 会直接报错**，
  而不是发出一个必然失败的请求。

> 实测（协议是否真的生效）：把供应商的 `protocol` 改成 `ANTHROPIC` 后，同一个模型请求变成访问
> `{baseUrl}/v1/messages`，中转返回 `404 page not found`；改回 `OPENAI_COMPATIBLE` 后重新命中
> chat completions 路径，错误形态也随之改变。

**图像生成只支持 OpenAI 形态**：给图片模型配一个非 OpenAI 系的供应商会得到一条明确说明，
而不是把 OpenAI 的报文发给 Gemini。

## 6. 获取上游模型列表

`GET /api/admin/v1/providers/{id}/models`

用该供应商存的密钥调 `GET {baseUrl}{app.llm.models-path}`（默认 `/models`），把**这把 key 能用的模型**列出来。

```json
{
  "providerId": 1,
  "providerKey": "demo-relay",
  "baseUrl": "https://api.example.com/v1",
  "count": 4,
  "models": [
    { "id": "deepseek-v4.1-flash", "ownedBy": null, "createdEpochSecond": null,
      "alreadyRegistered": true },
    { "id": "brand-new-model", "ownedBy": null, "createdEpochSecond": null,
      "alreadyRegistered": false }
  ]
}
```

`alreadyRegistered` 标出本地目录里是否已有同名 key，控制台因此可以只对缺的那几个提供「添加」。

失败语义：供应商不存在 → `404`；上游不可达 / 拒绝该密钥 / 本地没有任何可用密钥 → `502`。

## 7. 后台模型清单与写入

`GET /api/admin/v1/models?type=&status=&providerId=`

与公开目录的区别：

| | 公开目录 `/api/v1/models` | 后台清单 `/api/admin/v1/models` |
|---|---|---|
| 范围 | 当前站点可见（全局 + 本站） | **全部站点** |
| 停用模型 | 不返回 | **返回** |
| `route`（真实上游模型名） | **绝不返回** | 返回（未显式设置时即 `modelKey`） |
| 供应商信息 | 无 | `providerId` / `providerKey` / `providerName` |
| 鉴权 | 公开 | 必须携带正确的 `X-Admin-Key` |

### 写入

**手工增删改已下线**：`POST /models`、`PUT /models/{id}`、`DELETE /models/{id}` 都返回 `405`。
模型的创建、删除和路由归 New API 所有，本地只保留「显隐」和「展示字段/本地能力标记」这两类写入，
见下一节。历史上那段整体替换的语义（`modelKey` 站点内唯一、`route` 回退、引用校验、能力字段参与整体替换）
只在本仓库更早的版本里成立，现已随接口一起移除。

### 单字段端点与批量写入

后台模型页的三类写入都是**单字段、单条**的：

| 方法 | 路径 | 说明 |
|---|---|---|
| `PUT` | `/models/{id}/visibility` | 只改本地显隐：`{"status":"ACTIVE\|DISABLED"}` |
| `PUT` | `/models/{id}/display` | 只改展示字段与本地能力标记，字段 `null` = 不改；`company`/`iconUrl`/`description` 传空串表示清空；`thinkingParameter` 传空串清除配置 |
| `PUT` | `/models/batch` | **一次事务改多条**，字段语义与上面两个完全一致，`status` 与展示字段可混在同一条里 |

`PUT /models/batch` 是为了让后台多选编辑不再串行循环（选 50 行原本要 50~100 次请求，且中途失败会留下改一半的状态）：

```json
{
  "items": [
    {"modelId": 23, "status": "DISABLED", "modelName": "Renamed", "company": "",
     "thinking": false, "thinkingParameter": null, "supportsWebSearch": false, "sortOrder": 0},
    {"modelId": 24, "status": "ACTIVE", "sortOrder": 1}
  ]
}
```

响应返回**更新后的完整行**，前端可原地合并，不必重拉列表：

```json
{"requested": 2, "updated": 2, "models": [{ "id": 23, "...": "同 GET /models/{id}" }, { "...": "..." }]}
```

- **全有或全无**：任一 `modelId` 不存在 → `404`，其余模型状态不变。显隐直接决定前台是否展示，
  半成品比整体失败更难排查。
- `items` 为空 / 超过 100 条 / `status` 非法 / `sortOrder` 为负 / `thinkingParameter` 不合标识符规则 /
  同一 `modelId` 重复出现 → `400`，无写入。
- `sortOrder` 由调用方按勾选顺序算好逐条下发，服务端不做隐式编号；返回顺序与提交顺序一致。
- 只接受 `source=NEW_API` 的行：LOCAL 行（含视频自建供应商的模型）同样返回 `404`。
- 单条与批量共用同一段字段写入逻辑（`applyDisplayFields`），避免两处校验规则漂移。

### 想「删掉」一个模型怎么办

删除接口已下线（New API 拥有目录，删了下次同步又回来，且会话/消息的外键会拒绝）。
运营上要的效果一律用显隐表达：

```json
PUT /api/admin/v1/models/batch
{"items":[{"modelId":23,"status":"DISABLED"}]}
```

`DISABLED` 的行仍然留在后台清单里（可继续编辑、可再启用），但公开目录 `/api/v1/models`
和 `/api/v1/models/{key}` 都不再返回它——这正是「下架」该有的行为。

## 8. 供应商启停、分类与图片路径

### 启停：一个字段一个接口

```
PUT /api/admin/v1/providers/{id}/status
{"status":"DISABLED"}
```

返回更新后的供应商。只动 `status` 一个字段，其余字段一律原样保留。

控制台原先的做法是「GET 全量 → 改 `status` → 整个 PUT 回写」，那里有两处坑：

- **读改写竞争**：两个人同时改同一个供应商的不同字段，后写的会丢掉先写的改动；
- **必须记得别把密钥传错**：整行 PUT 下，`apiKey` 的省略语义与"提交新密钥"混在一起，一次失误就把已存的密钥抹了。

新接口只动一个字段，两个坑都不存在。取值不是 `ACTIVE` / `DISABLED` 时返回 `400`，id 不存在返回 `404`。

### 分类：`category` 是列，不是 JSON 里的字段

`llm_providers.category` ∈ `CHAT` / `IMAGE` / `VIDEO` / `AUDIO` / `OTHER`，缺省 `CHAT`，并支持服务端过滤：

```bash
curl -H 'X-Admin-Key: ...' '/api/admin/v1/providers?category=IMAGE'
```

以前分类寄存在 `metadata_json.category` 里、由前端把全量列表拉到本地再筛。两个问题：`metadata_json` 是 `LONGTEXT`，既不可查询也没法建索引；而"分类"是一个稳定的枚举，放在自由文本字段里没有任何约束。

**`category` 是描述性的，不构成约束。** 一个网关完全可以同时承载多类模型，而真正决定某个模型能不能被某个接口调用的是 `llm_models.model_type`（每次调用都校验，见 `docs/model-catalog-api.md`）。分类的作用只是让控制台把"语言供应商 / 绘画供应商"分成两个页签。

> 这也是"要不要给绘画供应商单独建一张表"的答案：不需要，而且不该建。两张表意味着两套增删改查、两套凭据解析、两套删除前的引用检查——正是本项目已经修过两次的"同一件事有两个实现，然后其中一个漏改"的结构。一个分类列让它是**分组维度**，而不是一条平行的代码路径。

### 供应商级图片路径

`llm_providers.images_path`（可空）。为空时图像请求仍走 `app.llm.images-path`，所以**既有供应商的行为完全不变**（本机两个供应商的 `images_path` 都是 `null`，走全局默认）。

```json
{"providerKey":"demo-relay-image", "imagesPath":"/images/generations"}
```

生效点在 `infra/llm` 的 `imagesUrl()`：有供应商就按「供应商 baseUrl + imagesPath」拼，否则回落到服务端配置。图像客户端这一能力本来就支持（`ProviderCredentials.imagesPath` 一直存在），缺的只是那一列。

### 供应商级视频下载源

`llm_providers.video_download_base_url`（可空，V78）。视频任务的状态接口只返回**不含主机名**的相对路径
（xAI 系插件是 `/v1/videos/{id}/content`），这条路径属于产出文件的供应商，不属于提交用的中继。
而中继的内容代理对这类插件取不到字节（new-api 的 `buildContentRequest` 抛
`credentialless artifact request URL must be absolute HTTP(S)`），所以文件必须直连供应商下载。

```http
PUT /api/admin/v1/providers/14/video-download-base-url
X-Admin-Key: <admin key>
Content-Type: application/json

{"value":"https://api.example.com"}
```

- 只能是绝对 `http(s)` 地址，相对值直接 `400`——相对值会又解析回中继，正是这列要修的问题。
- 传 `""` 或 `null` 清除，恢复「按提交地址（中继）解析」，即改动前的行为。
- 只写这一列（单字段端点），不与目录同步抢整行；`POST /providers/sync` 不会重置它。
- 生效后的链路：**提交/轮询仍走中继、仍记调用方账**；只有取文件这一步改为
  「该地址 + 供应商密钥」。下载源只在 URL 主机等于声明的主机时才用供应商密钥，
  避免把供应商密钥发到别的主机。

#### 密钥从哪来（V83 起改为写库，env 只作兼容回落）

`llm_providers.video_download_api_key`（可空，V83）。这一列**与下载源地址配对**，是同一份
数据的另一半。

```http
PUT /api/admin/v1/providers/7/video-download-api-key
X-Admin-Key: <admin key>
Content-Type: application/json

{"value":"sk-..."}
```

- 传 `""` 或 `null` 清除，回落到下一级。
- 含空白字符或带 `Bearer ` 前缀直接 `400`：前者是复制粘贴常带的换行，后者会让请求头变成
  `Bearer Bearer sk-...`。
- 响应**只返回 `videoDownloadApiKeySet` 布尔值，密钥永不回显**（写接口本身也不回显）。
- 同样走单字段端点，不与目录同步抢整行。

**为什么必须是独立的一列，而不是复用 `api_key`。** 因为目录同步（启动时 + 每 5 分钟）
会通过 `ensurePublicProvider()` 重置中继供应商行，重置清单里明确包含
`setApiKey(null)`、`setCredentialFingerprint(null)`、`setCredentialRef("config:new-api-user-relay")`、
`setMetadataJson(null)` 等。写在 `api_key` 上的密钥最多活一个同步周期，之后
`ProviderVideoDownload.usesProviderHost()` 因 `hasVideoDownloadApiKey()` 为空而返回 `false`，
链路**悄悄退回中继密钥**——供应商主机随即 `401/403`，前端只看到「结果获取失败，请重试」。
这个「静默降级」正是过去只能把密钥放环境变量的原因。

现在 `video_download_base_url` 与 `video_download_api_key` 都不在重置清单里，所以
**下载源和它的密钥都是普通行数据**：换供应商是改一行，不需要重新部署。测试
`NewApiCatalogSyncServiceTest#preservesTheVideoDownloadOriginAndKeyAcrossRefreshes` 就是钉住
这一点的回归用例（连跑两次同步后 `api_key` 被清空、这两个字段原样保留）。

密钥解析顺序（`ProviderCredentialResolver.downloadKeyFor`）：

1. `llm_providers.video_download_api_key` —— 新位置，推荐；
2. 该行的提交密钥 —— 自建供应商通常同一把，保持旧行为；
3. `app.llm.video-download-api-key`（环境变量 `LLM_VIDEO_DOWNLOAD_API_KEY`）—— **兼容保留**，
   老部署什么都不改也照旧工作；
4. 无 → 用调用方的中继密钥。

> ⚠️ 顺序上 1 在 2 之前，是因为一行可以同时有「中继提交密钥」和「供应商取件密钥」。反过来
> 会让新列永远轮不到生效。

```bash
# 推荐：两处都写库，env 留空
mysql> UPDATE llm_providers
          SET video_download_base_url='https://api.example.com',
              video_download_api_key='sk-...'
        WHERE id=7;
# 或走管理接口（不需要重启）
curl -X PUT -H 'X-Admin-Key: ...' -H 'Content-Type: application/json' \
     -d '{"value":"sk-..."}' /api/admin/v1/providers/7/video-download-api-key
```

顺带一条同源改造：下载器的 SSRF 白名单过去只认配置里的中继/结果源，供应商若在内网
地址就永远取不到文件。现在 `VideoUrlDownloader.download(url, key, trustedOrigin)` 会把
**该行声明的下载源**作为凭据传入，只有主机与它完全相同的地址才享有豁免；`trustedOrigin`
为 `null` 时行为与以前完全一致。

验证是否真的走了直连：日志里出现 `Generated video download returned HTTP 404` 说明仍在
打中继；成功时该行不出现，且 `uploaded_files.mime_type = video/mp4`、文件头为 `ftypisom`。

#### 为什么必须绕开中继取文件（根因在 New API 的 xAI 插件，2026-11 核查）

「能不能让下载也走中继」这个问题的答案是**当前版本的 New API 做不到**，不是本站偷懒：

1. 中继回传的是**主机无关相对路径**。xAI 任务状态接口里
   `data.video.url = "/v1/videos/{uuid}/content"`，其中 `uuid` 是**上游（Aurora）的 id**，
   不是中继自己的 `task_...`。中继侧希望客户端拿这个相对路径回来请求自己
   （库里的 `private_data.result_url` 就是 `http://localhost:3000/v1/videos/task_.../content`）。
2. 中继的 content 路由会拿相对路径去插件要绝对地址，插件的
   `buildContentRequest` 原样返回 `{url: video.url, credentialless: true}`，于是撞上
   `relay/channel/task/jsplugin/adaptor.go:1309`
   `credentialless artifact request URL must be absolute HTTP(S)`，最终对外
   `410 artifact_gone`。**这是实现缺陷，不是设计取舍。**
   （同类缺陷：hailuo 插件 issue #7706，`buildContentRequest` 指向不存在的 `/v1/files/download`。）
3. 更关键的一条：Aurora的 content 端点**要求鉴权**（无 Bearer 直接 `401`），而插件声明的是
   `credentialless: true`——按定义不带任何凭据。所以即便把相对路径补成绝对地址，
   无凭据请求依然过不去。想让中继转发成品字节，必须改成带渠道密钥的普通
   `fetch` 请求，这要改插件而非改配置。
4. 换现成插件也无效。核对结果：
   - NAS 跑 `new-api:v1.0.0-rc.45`，内置 xAI 插件 **1.1.0**（`tasks.private_data.execution.task_plugin.version` 可查）；
   - 插件市场 `new-api-plugins` 里 xai 的 `latest` **就是 1.1.0**；
   - main 分支 1.2.0 的 `buildContentRequest` 与 1.1.0 **逐字相同**，仍然透传 `video.url`，
     `allowedHosts` 只有 `vidgen.x.ai`，注释假定该 URL 是绝对地址（真实 xAI 的
     `vidgen.x.ai`）。Aurora是 OpenAI 兼容代理，形态不同，换插件不解决问题。
5. 自写覆盖插件**可行**但属于运维动作：rc.45 已内置 JS 插件系统，`task_plugins` 表是
   「编译期内置插件的覆盖层」，可用 `POST /api/plugin/task`（RootAuth）上传覆盖、`dryrun`
   预演、`DELETE /api/plugin/task/:key/versions/:version` 回滚。插件上下文里
   `ctx.baseUrl`（渠道地址）与 `ctx.apiKey`/`ctx.authHeader`（渠道密钥，`channelTypes:[48]`
   且 `auth.type=api_key` 时会填）都可用，足以把 `/v1/videos/{uuid}/content` 拼成绝对地址
   并带上 `Authorization`（此时必须去掉 `credentialless`）。要点见
   `docs/newapi-deployment-checklist.md`。

因此本站的 `video_download_base_url` + `LLM_VIDEO_DOWNLOAD_API_KEY` 组合，是在
**不改动中继二进制、不承担插件维护成本**前提下的最优解；代价是成品字节不经过中继
（计费仍在中继：提交与轮询都在中继，配额已按 `per_call_billing` 扣过）。

## 9. 参考数据（站点与模型分组）

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/sites` | 站点列表，模型表单的 `siteId` 可选项 |
| `GET` | `/model-groups` | 模型分组列表，模型表单的 `groupId` 可选项 |

```json
GET /api/admin/v1/sites
[{"id":1,"siteKey":"default","displayName":"Default Site","domain":null,"status":"ACTIVE","createdAt":"..."}]

GET /api/admin/v1/model-groups
[{"id":1,"siteId":null,"groupKey":"demo-relay","label":"示例中转","company":"Jiguang","status":"ACTIVE","sortOrder":1}]
```

在此之前，表单里的 `siteId` / `groupId` 是数字输入框，"留空"表示"全局"——一个说不出那些数字代表什么的表单，而且只有到写入时才会因为"引用不存在"而失败。

两点刻意的语义：

- **站点列表返回全部站点（含停用）**：运维管理的是整个平台，停用站点里的模型仍然需要维护；
- **分组列表返回全部分组**，不是公开目录那套"按站点可见"的子集：给一个平台级模型选一个属于别的站点的分组，仍然是合法选择。

## 10. 模型降级管理

模型降级管理器使用统一前缀 `/api/admin/v1/model-fallbacks`，所有接口都需要 `X-Admin-Key`。

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/` | 版本组列表；支持 `type`、`siteId`、`globalOnly` |
| `GET` | `/{id}` | 版本组详情与实际可用状态 |
| `POST` | `/` | 创建版本组 |
| `PUT` | `/{id}` | 完整更新版本组 |
| `DELETE` | `/{id}` | 删除组和成员关系，不删除模型 |
| `GET` | `/candidates?type=&siteId=` | 查询可加入组的模型 |
| `POST` | `/{id}/members` | 加入具体模型 |
| `DELETE` | `/{id}/members/{modelId}` | 移除成员 |
| `PUT` | `/{id}/members/{modelId}/enabled` | 启用或暂停成员 |
| `PUT` | `/{id}/members/batch-enabled` | **批量启用/暂停成员**（部分成功） |
| `POST` | `/{id}/members/batch-remove` | **批量移除成员**（部分成功 + 幂等） |
| `PUT` | `/{id}/members/order` | 保存自定义降级顺序 |
| `PUT` | `/{id}/order/price` | 恢复默认价格顺序 |

创建组：

```json
POST /api/admin/v1/model-fallbacks
{
  "siteId": null,
  "groupKey": "gpt-image-2.0",
  "displayName": "GPT-Image-2.0",
  "modelType": "IMAGE",
  "status": "ACTIVE",
  "sortOrder": 10
}
```

`siteId` 为空表示全局组。全局组只能加入全局模型；站点组可以加入全局模型或同站点模型。成员允许来自不同产品系列，但必须与组类型一致，且状态为 `ACTIVE`、上游可用。

加入成员与自定义顺序：

```json
POST /api/admin/v1/model-fallbacks/12/members
{"modelId": 41, "enabled": true}

PUT /api/admin/v1/model-fallbacks/12/members/order
{"modelIds": [42, 41, 43]}
```

`modelIds` 必须与当前全部成员一一对应。保存后 `orderingMode` 变为 `CUSTOM`；调用 `/order/price` 后恢复 `PRICE_ASC` 并取消自定义标记。

批量启停与批量移除（成员列表多选）：

```json
PUT /api/admin/v1/model-fallbacks/12/members/batch-enabled
{"modelIds": [23, 24, 25], "enabled": false}

POST /api/admin/v1/model-fallbacks/12/members/batch-remove
{"modelIds": [23, 24]}
```

两者返回同一个结构，且都是**部分成功**语义——一条不合法不该拖垮其余几十条：

```json
{
  "group": { "id": 12, "...": "整组最新快照" },
  "requested": 3,
  "applied": 1,
  "unchanged": 1,
  "skipped": [{"modelId": 25, "reason": "upstream is unavailable"}]
}
```

- `applied`：状态真的变了；`unchanged`：本来已是目标状态，**算成功不算失败**。
- `skipped`：未执行且未报错。启用时未通过 `memberProblem` 校验的成员会带上原因为
  `upstream is unavailable` / `model status is DISABLED` / `model type ... does not match group type ...` /
  作用域不匹配；停用永远不会因业务约束被跳过。
- `modelId` 不属于该组 → 计入 `skipped`（`not a member of this group`），**不返回 404**：
  同一次批量里混入已被别人删掉的成员不该让整批失败。
- 批量移除是**幂等**的：重复提交同一批 ID，第二次全部 `skipped`、`memberCount` 不再变化。
  删除后 `priority` 在批量结束、`flush()` 之后**统一重排一次**（不是每删一个重排一次）。
- `modelIds` 为空 / 超过 100 条 / `enabled` 为 null → `400`；组不存在 → `404`。

对比：单条接口 `/{id}/members/{modelId}/enabled` 与 `DELETE /{id}/members/{modelId}`
在「不是成员」时返回 `404`——单条调用时那确实是调用方的错误。

默认价格顺序（后台展示与 `/order/price` 共用）：

1. 匿名读取 `GET /api/v1/model-plaza`，同名模型聚合所有公开分组；
2. token、缓存、图片和阶梯价格由 USD/token 换算为 USD/百万 tokens，按次价格保持原值；
3. 每个价格字段取所有公开分组中的最低非空值，`0` 是有效免费价，`null` 表示未公开；
4. 组内按最低公开价格升序，没有公开价格的模型排在最后，再按模型 `sortOrder`、ID 升序。

价格快照缓存 5 分钟。匿名广场不可用或返回空列表时保留上一份价格快照；首次失败时
模型显示为未定价。价格只用于排序和展示，不会写回 New API，也不等同于站内点数。

> 这里的价格**不是**站内积分。`llm_models.consume_points` 是早期积分体系的占位字段，
> 后台没有编辑入口、同步任务也不写入它，所以不能用来展示 —— 否则图片模型整列都是 0。
> 公开模型目录 `/api/v1/models` 里的 `consumePoints` 仍按旧口径返回，与后台展示无关。

`/candidates` 只返回状态为 `ACTIVE` 且上游可用的模型。`siteId` 为空时只返回全局候选；指定站点时返回全局候选和该站点候选。响应中的模型主键字段名为 `modelId`。

详情响应中的成员包含：

- `priority`、`enabled` 和当前 `orderingMode`；
- `pricing`：New API 的实际计价方式与单价，展示以它为准；
- `effectivePrice`：排序用的数值（USD），仅为兼容旧前端保留；
- `eligible` 与 `ineligibleReason`，用于后台直接说明停用、类型不匹配、作用域不匹配或上游不可用。

`pricing` 的结构：

```json
{
  "mode": "EXPRESSION",            // TOKEN | EXPRESSION | PER_REQUEST | UNPRICED
  "inputUsdPerMillion": 5,         // token / 动态计费的输入单价（USD/百万 tokens）
  "outputUsdPerMillion": 30,       // 输出单价
  "usdPerRequest": null,           // 按次计费时的每次价格（USD）
  "sortPrice": 5                   // 仅用于排序；无价时为 null
}
```

`mode` 的判定顺序：`quota_type = 1` 或 `model_price > 0` → `PER_REQUEST`；
`billing_mode = tiered_expr` → `EXPRESSION`（从表达式里取 `p` / `c` 的系数）；
`model_ratio > 0` → `TOKEN`（输入价 = `model_ratio × 2`，输出价再乘 `completion_ratio`）；
其余 → `UNPRICED`。

价格目录在服务端缓存 5 分钟；New API 不可用时后台仍可打开，价格列退回上一次快照
（首次失败则为「未定价」），不会让整个列表 5xx。

初始化迁移会创建全局版本组 `gpt-image-2.0`（展示名 `GPT-Image-2.0`），并把当前已有的 `gptimage2`、`gptimage2-cc`、`gpt-image-2`、`gpt-image-2-cc` 按价格从低到高加入。之后新增模型可在后台加入该组。

未加入任何版本组的模型归入内部“其他”集合，当前不会出现在公开模型目录。

## 11. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `401` | 缺少或错误的 `X-Admin-Key`；或 `app.admin.api-key` 未配置 |
| `400` | 字段校验失败（`providerKey` 需为 URL 安全、`baseUrl` 非空、`status` 必须是 `ACTIVE`/`DISABLED`、`category` 必须是五类之一、`contextWindow` 必须为正、`consumePoints` 不得为负等）；批量接口另有：`items`/`modelIds` 为空、超过 100 条、`modelId` 重复、`enabled` 为 null |
| `404` | 供应商不存在；模型不存在（含 `source=LOCAL` 的行）；版本组不存在；**批量模型写入中任一 `modelId` 不存在时整体回滚** |
| `405` | 试图创建/整体更新/删除模型（`POST /models`、`PUT /models/{id}`、`DELETE /models/{id}`），目录归 New API |
| `409` | `providerKey` 已存在；删除仍被模型引用的供应商 |
| `502` | 拉取上游模型列表时上游不可达或拒绝该密钥 |

## 12. 测试

| 用例类 | 覆盖 |
|---|---|
| `LlmProviderAdminControllerTest` | 供应商生命周期、缺少管理密钥时 401、**切换状态不动密钥与其他字段**、非法状态 400、未知供应商 404、切换状态需管理密钥 401、**按分类过滤（服务端，大小写不敏感、未知分类返回空）**、缺省分类为 `CHAT` |
| `LlmModelAdminControllerTest` | 后台清单只含 `NEW_API` 行（含停用与 `route`）、按类型/状态/供应商过滤、详情不回落 LOCAL 行、显隐与展示字段、`thinkingParameter` 非法 400、LOCAL 行写入 404、公开目录过滤、401、目录同步、OpenAPI 路径、**手工 CRUD 路由已移除（405）**；**批量：一次改多条并返回完整行、`null` 跳过而空串清空、空数组/超 100 条/重复 id/非法 status/负 sortOrder 400、缺一个 id 时整体回滚且其余行状态未变、LOCAL 行 404** |
| `ModelFallbackAdminControllerTest` | 全局/站点作用域、跨系列成员、类型校验、候选过滤、自定义顺序与恢复价格顺序、OpenAPI 路径；**批量启停：混入上游下线成员 → `skipped` 带原因、其余照常；重复调用 → `unchanged` 且不写库；非成员 → `skipped` 而非 404**；**批量移除：重复提交幂等、priority 重排后连续无空洞**；**空数组/缺 `enabled` 400、组不存在 404、缺密钥 401** |

| `NewApiCatalogSyncServiceTest` | 匿名广场完整快照、新模型进入目录、消失模型保留历史行并标记上游不可用、空响应与异常不改本地快照、不读取或写入管理凭据 |
| `NewApiCatalogSyncSchedulerTest` | 周期同步只调用匿名模型广场、关闭同步时不访问 New API、同步失败时保留最后快照 |
| `ModelPriceServiceTest` | 同名模型跨公开分组取最低非空价格、`0` 与 `null` 语义、token 价格换算、阶梯价格聚合、广场异常时保留价格快照 |
| `AdminReferenceControllerTest` | 站点列表与模型分组列表可读、缺管理密钥时 401 |
| `ModelFallbackAdminControllerTest` | 全局/站点作用域、跨系列成员、类型校验、候选过滤、自定义顺序与恢复价格顺序、OpenAPI 路径 |
| `ProtocolAwareLlmClientProtocolTest` | 本地桩服务器逐协议验证报文：OpenAI 兼容路径与 Bearer、Azure 的 deployment 路径 / `api-key` / `api-version`（缺 version 报错）、Anthropic 的 `/v1/messages` / `x-api-key` / `anthropic-version` / system 外置 / max_tokens 补默认 / 流式帧解析、Gemini 的 `:generateContent` / `x-goog-api-key` / `contents` 角色映射 / `systemInstruction` / 流式帧解析、未知协议回退、无供应商时回退配置、错误不回显密钥 |
| `ProtocolAwareLlmClientTest` | 供应商凭据生效（密钥与路径都走供应商，覆盖配置）、供应商无密钥时回退配置 |
| `OpenAiCompatibleImageGenerationClientTest` | 图像侧同样优先用供应商凭据；**供应商声明了 `imagesPath` 时按它取路径，未声明时回落到 `app.llm.images-path`** |

```powershell
.\mvnw.cmd test
```

## 13. 公开模型自动同步

服务启动后会立即同步一次公开模型广场，随后由后台任务按固定间隔继续刷新。同步只调用：

```http
GET {NEW_API_BASE_URL}/api/v1/model-plaza
```

请求不发送 `Authorization`，也不读取任何全局管理凭据。每轮快照会同步以下状态：

- 新公开模型进入本站目录，并加入同名公开降级组；
- 仍在广场公开的模型标记为 `upstreamAvailable=true`；
- 从广场消失的模型保留历史行，但标记为 `upstreamAvailable=false`；
- 不读取 New API 私有渠道，不探测渠道 Key，不修改渠道模型列表；
- 不写 New API 价格、用户、兑换码或渠道配置；
- 不覆盖运营已有的展示名、说明、启停和人工排序字段。

“渠道状态”在这里只表示匿名广场可见性，不表示 New API 后台某个渠道本身是否启用。
管理员在 New API 中把渠道、分组或模型设为不公开后，下一轮同步会把对应模型标为不可用。

空响应和请求异常不会清空目录，也不会把全部模型误标为下线；系统保留上一份成功快照。

配置项：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `NEW_API_CATALOG_SYNC_ON_STARTUP` | `true` | 启动完成后是否立即同步一次 |
| `NEW_API_CATALOG_SYNC_ENABLED` | `true` | 是否启用周期同步 |
| `NEW_API_CATALOG_SYNC_INTERVAL_MS` | `300000` | 一轮同步完成后的等待时间，默认 5 分钟 |
| `NEW_API_CATALOG_SYNC_INITIAL_DELAY_MS` | `300000` | 应用启动后首次周期同步的延迟，默认 5 分钟 |

周期任务使用 `fixedDelay`，上游响应慢时不会叠加下一轮；同一进程内的同步器也会串行执行。
管理端仍可调用 `POST /providers/sync` 或 `POST /models/sync` 立即重试。

同步响应保留一些历史统计字段以兼容现有控制台。`providersLive` 在匿名模式下最多为
`1`，表示公开广场快照可用；`providersFallback`、`providersWriteBackSucceeded`、
`providersWriteBackFailed`、`pricingCopied` 和 `pricingCreated` 均不再代表远程管理操作。
`pricingUnpriced` 表示公开广场没有给出任何显式价格的模型数。

### 匿名价格

模型降级组的价格排序由 `ModelPriceService` 独立读取同一份匿名广场数据，不与目录同步共用
写入事务。若模型同时出现在多个公开分组，服务端对每个字段取最低非空值。公开价格为
USD/token；token、缓存、图片和阶梯 token 价会换算为 USD/百万 tokens，按次价格保持
USD/次。最终 `sortPrice` 取该模型所有公开可比较价格中的最小值。

匿名广场不可用、返回空数据或缺少某个价格字段时，不推测价格，也不读取私有配置补价。
缓存过期且刷新失败时继续使用上一份价格快照；没有历史快照的模型排在已定价模型之后。

## 14. 尚未覆盖

- **管理端账号体系（RBAC）**：现在只保留强制启用的单一管理密钥 `X-Admin-Key`；拿到密钥即全权，没有管理员账号、没有角色划分。规划的 RBAC（账号表 + 登录 + viewer/operator/superadmin 角色）落地后应替换它，届时 `AdminGuard` 是唯一的改动入口。
- **列表分页**：`/providers` 与 `/models` 都返回全量数组，由调用方本地过滤与排序。当前量级（个位数供应商、十位数模型）足够，且改成 `{items,total,page,size}` 会破坏现有调用方，因此**暂不改**；等目录到几百行再加，届时建议一次性给两个接口都加上并同步前端。
- **协议只覆盖对话**：图像/视频/音频仍只有 OpenAI 形态的上游适配（生图配非 OpenAI 系供应商会得到明确说明，而不是静默发错报文）。
- **密钥加密**：如需要，`api_key` 可换成信封加密，接口语义不变。
- **站点可见性未参与写入校验**：后台可以给任意站点建模型；写入只校验"该站点存在"，不校验其它业务规则（如"该站点是否对该模型有权益"）。
- **`VIDEO` / `AUDIO` 模型仍无接口**：类型与分类都支持，但没有端点能真正调用它们。
