# 图片生成接口（`/api/v1/images`）

> 状态：已实现，契约由本地桩验证。**上游生图工具当前报错**，真实链路尚无法跑通，见 §6。
> 涉及代码：`modules/image`（接口与编排）、`infra/llm`（上游适配）、`modules/file`（落库与存储）。
> 对应 `docs/ai1foo-rebuild-plan.md` 首页「图像/视频工具」与 §10 契约表。

---

## 1. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/images/generations` | 文生图 |

## 2. 请求

```json
{
  "prompt": "一只戴帽子的猫，极简插画",
  "model": "gpt-image-2",
  "size": "1024x1024",
  "quality": "medium",
  "count": 1,
  "conversationId": null
}
```

| 字段 | 必填 | 约束 | 说明 |
|---|---|---|---|
| `prompt` | 是 | 1–4000 字符 | 提示词 |
| `model` | 否 | ≤128 字符 | 缺省取 `app.llm.image-model`；必须是 `IMAGE` 类型的模型，见 §4 |
| `size` | 否 | `auto` 或 `WxH` | 缺省由上游决定；具体支持哪些尺寸以上游为准 |
| `quality` | 否 | `auto`/`low`/`medium`/`high` | |
| `count` | 否 | 1–4 | 缺省 1；上游返回多于该数的部分会被丢弃 |
| `conversationId` | 否 | 必须存在且属于当前账号 | 把生成的图片挂到该会话 |

`size` 的正则刻意宽松（只校验形状），因为可用尺寸取决于具体图像模型，写死白名单反而会在换模型时误伤。

## 3. 响应

```json
{
  "created": "2026-10-15T02:14:07Z",
  "model": "gpt-image-2",
  "count": 1,
  "images": [
    {
      "fileId": 12,
      "url": "/api/v1/files/12/content",
      "owned": true,
      "assetType": "generated",
      "mimeType": "image/png",
      "sizeBytes": 245871,
      "revisedPrompt": "a cat wearing a hat, minimal illustration"
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `owned` | `true` 表示图片已存入本平台存储；`false` 表示这是上游给的临时直链 |
| `fileId` / `url` | `owned=true` 时 `url` 就是 `/api/v1/files/{id}/content`，与普通上传附件完全一致 |
| `assetType` | 文生图结果固定为 `generated`；上游临时直链未落库时仍保留该业务语义 |
| `revisedPrompt` | 上游实际使用的提示词，可能为 `null` |

### 为什么落成本平台的附件

上游以内联 base64 返回的图片会写入 `FileStorage` 并登记为 `uploaded_files` 的一行（归属当前账号）；上游只返回临时链接时，服务端会先下载图片，再按同样的流程落库。因此：

- 取图走的是**已有的、做过归属校验的** `/api/v1/files/{id}/content`；
- 生成的图片可以直接作为附件挂进会话，不需要第二条链路；
- 落库记录的 `assetType` 为 `generated`，可与参考图 `reference`、修图结果 `edit_result` 和普通附件 `chat_attachment` 区分；
- 别人的账号取这张图会得到 `404`（有专门用例覆盖）。

只有下载临时直链失败时，才原样透传且 `owned=false`。这类回退结果通常会随上游链接过期而失效，
因此它是容错路径，不是正常的历史存储路径。

## 4. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | `prompt` 为空或超长、`size` / `quality` 格式非法、`count` 越界 |
| `400` | `model` 指向的不是 `IMAGE` 类型的模型，例如语言模型：`Model 'deepseek-v4.1-flash' is registered as type CHAT and cannot be used for image generation` |
| `404` | `model` 已下架（`status != ACTIVE`）；`conversationId` 不存在或不属于当前账号 |
| `502` | 上游不可达 / 返回非 2xx / 未配置图像密钥 / 返回体里没有可用图片 |

`502` 的文案来自上游，最多保留 500 字符且不含密钥。

### 为什么模型类型要拦住

`model_type` 一度只用于目录筛选，执行路径不读它，于是**语言模型可以被送到生图接口**，请求打到上游后只会得到一个含糊的上游错误；反向也一样，绘画模型能被当对话模型调用，并**以图像模型的行写进对话历史**（`chat_messages.model_id`），让这条会话在按模型统计时落到另一套数据上。

现在生图与对话都走 `modules/model/ModelUseResolver`，类型与状态只有一份判断。两点语义需要留意：

- **拒绝发生在调用上游之前**，所以填错模型不会产生任何生成成本；
- 类型校验**只在目录里查得到该 key 时生效**；未登记的 key 默认 `404`（与对话一致），例外见 `docs/model-catalog-api.md` §6.1。要在「上游刚上线、目录还没同步」的窗口期放开，设 `app.llm.allow-unlisted-models=true`。

## 5. 配置与计费凭据

| 配置 | 默认值 | 说明 |
|---|---|---|
| `NEW_API_GROUP` | `default` | 创建用户 Relay Token 时使用的 New API 分组，必须允许当前图像模型 |
| `app.llm.images-path` | `/images/generations` | 全局上游路径；供应商可用 `images_path` 覆盖 |
| `app.llm.image-model` | `gpt-image-2` | 缺省模型 |
| `app.llm.image-timeout` | `4m` | 生图比对话慢得多 |

面向用户的生图请求在调用前统一检查 New API 余额：

1. 解密当前用户的 Relay Token；
2. 实时读取 New API `data.user_quota.total_available`；
3. 余额 `<= 0` 时返回 `402`，不访问图像上游；
4. 余额可用时以该 Relay Token 调用 `LLM_BASE_URL + app.llm.images-path`。

因此图像链路不再使用全局图片 Key、供应商 Key 或可选的 `APP_LLM_API_KEY`。`llm_providers.images_path` 仍保留给底层兼容调用，但用户生图入口会注入账户级凭据，路径取全局 `app.llm.images-path`。

### 为什么必须使用账户 Relay Token

New API 按 Token 所属分组判断模型权限并扣除该用户余额。使用服务端共享密钥虽然能完成请求，但无法归属到当前用户，也会绕过余额检查。

`NEW_API_GROUP` 必须同时包含平台开放的文本与图像模型。若返回 `404 model_not_found`，应先检查该分组是否包含当前模型，而不是回退到共享图片 Key。

完整绑定、余额查询和部署配置见 `docs/new-api-billing.md`。

## 6. 上游可用性

在当前测试分组中，真实图像链路曾因上游账号容量不足而失败。凭据与路由已通过本地桩验证，错误来自 New API 后端的图像 group：

```
POST https://api.example.com/v1/images/generations   （当前用户 Relay Token，gpt-image-2）
→ HTTP 503  {"error":{"message":"No available compatible accounts","type":"api_error"}}
```

`No available compatible accounts` 是网关侧的账号容量问题，不是本服务计费或鉴权失败。若 New API 账号和模型分组已恢复可用，本服务无需改代码。

契约由本地桩验证：`OpenAiCompatibleImageGenerationClientTest` 覆盖 base64 / 直链两种返回、请求字段和错误映射；`ImageGenerationControllerTest` 覆盖余额拦截、落库、归属、参数校验与 `502` 映射。

## 7. 测试

| 用例类 | 覆盖 |
|---|---|
| `OpenAiCompatibleImageGenerationClientTest` | 本地 `HttpServer` 桩：base64 / 直链两种响应、发送字段、可选字段省略、错误映射且不回显密钥、空结果、缺少账户 Relay Token 时拒绝调用 |
| `ImageGenerationControllerTest` | 余额为零 `402` 且不调用网关、落库为附件并可通过 `/content` 取回、直链下载落库且能被 `/files` 列表读到、下载失败才回退直链、参数透传、不超过请求张数、挂到会话、他人会话 404（且不调用网关）、参数校验、502 映射、跨账号取图 404、**拒绝语言模型且不调用网关**、**未登记的图像模型在目录为空时放行、目录已知该类型时拒绝**、OpenAPI 路径 |

```powershell
.\mvnw.cmd test
```

## 8. 尚未覆盖

- **图像编辑 / 图生图**（`images/edits`）与局部重绘；
- **本地费用估算**：不在本服务记录积分或金额，实际扣费只以 New API 为准；
- **限流**：生图是重接口，应按账号限流（当前只有注册限流）；
- **协议**：生图只实现了 OpenAI 形态，给图像模型配 Anthropic / Gemini 供应商不会被适配（会得到明确说明而不是静默发错报文）；
- 内容审核（规划 §9）。

> 图像模型**已经进目录**：`llm_models.model_type = IMAGE`，`GET /api/v1/models?type=IMAGE` 可以列出，
  按 `modelKey` 走的是与对话一致的站点可见性规则。见 `docs/model-catalog-api.md` 与 `docs/provider-admin-api.md`。
