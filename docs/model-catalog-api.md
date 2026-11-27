# 模型目录与降级

> 状态：已实现。
> 涉及代码：`modules/model`、`modules/admin/model`，以及对话、智能体、Canvas、图片和 SmartEdit 执行链路。
> 迁移：`V68__model_fallback_groups.sql`、`V69__model_fallback_member_cascade.sql`。

## 1. 对外模型抽象

前台看到的不是某一条 `llm_models` 记录，而是一个**同版本模型组**。例如：

```text
GPT-Image-2.0
  -> gptimage2
  -> gptimage2-cc
  -> gpt-image-2
  -> gpt-image-2-cc
```

客户端只拿到组 key、组展示名和代表成员的安全展示字段，具体成员、上游 `route` 与尝试顺序不会公开。

未加入任何版本组的模型被放入内部“其他”集合，当前**不会出现在公开目录中**。

## 2. 接口总览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| `GET` | `/api/v1/models?group=&type=` | 否 | 当前站点可见的同版本模型组 |
| `GET` | `/api/v1/models/families?type=` | 否 | 按厂商归组的模型组视图 |
| `GET` | `/api/v1/models/{modelKey}` | 否 | 单个同版本模型组 |

`modelKey`、`groupKey` 和请求执行接口时使用的 key 都是版本组 key，例如 `gpt-image-2.0`。

## 3. 可见性与覆盖规则

一个版本组只有同时满足以下条件才对外显示：

- 组状态为 `ACTIVE`；
- 组内至少有一个可执行成员；
- 成员状态为 `ACTIVE`、上游可用，且类型与组一致；
- 成员作用域允许：全局组只含全局模型，站点组可含全局模型或同站点模型。

站点解析由 `ChannelResolver` 完成（`app-name` 头 -> Host -> 默认站点）。

同 key 的站点组会覆盖全局组，前台只返回站点组。一个模型如果属于多个可见组，执行时按组 key 精确选择。

> 列表、详情和执行路径使用**同一条可见性规则**，因此同一个 `modelKey` 不会出现「目录看得到、执行看不到」或反过来。目录里完全不可见的 key 默认拒绝，例外见本文 §9.1。

## 4. 降级顺序

默认顺序为有效价格升序：

1. `consumePoints` 大于 0 时使用它；
2. 否则使用 `inputPointsPer1k + outputPointsPer1k`；
3. 价格相同再按模型 `sortOrder`、`id` 升序。

管理后台可以把组切换为自定义顺序，此时按成员 `priority`、成员 ID 升序。恢复“价格顺序”会取消自定义标记。

候选在运行时还会：

- 跳过停用、上游不可用、站外或类型不匹配的成员；
- 按规范化后的上游 `route` 去重。

## 5. 模型目录

`GET /api/v1/models?group=gpt-image-2.0&type=IMAGE`

| 参数 | 说明 |
|---|---|
| `group` | 可选，只看一个同版本模型组 key；未知 key 返回空目录 |
| `type` | 可选，按 `CHAT` / `IMAGE` / `VIDEO` / `AUDIO` 过滤 |

```json
{
  "groups": [
    {
      "groupKey": "gpt-image-2.0",
      "label": "GPT-Image-2.0",
      "company": "OpenAI",
      "modelCount": 1
    }
  ],
  "models": [
    {
      "modelKey": "gpt-image-2.0",
      "modelName": "GPT-Image-2.0",
      "modelType": "IMAGE",
      "company": "OpenAI",
      "groupKey": "gpt-image-2.0",
      "iconUrl": null,
      "description": "GPT Image 2",
      "tags": ["绘画"],
      "free": false,
      "thinking": false,
      "pointsOnly": false,
      "pointsAllowed": true,
      "consumePoints": 0.010000
    }
  ],
  "totalModels": 1
}
```

`groups` 与 `models` 是同一批版本组的两种视图，`models[].groupKey` 与对应组 key 相同。`modelCount` 表示该组在前台形成的目录条目数，当前为 `1`；组内有多个可降级成员不会把它们分别暴露出去。

`models[]` 的 `company`、图标、说明、标签和能力字段来自当前排在最前的代表成员。`consumePoints` 是该组中可用成员的最低有效价格。

## 6. 模型详情

`GET /api/v1/models/{modelKey}`

返回与目录 `models[]` 相同的结构。组不存在、已停用、没有有效成员或不属于当前站点时返回 `404`。

## 7. 厂商家族

`GET /api/v1/models/families?type=IMAGE`

家族是目录的派生视图，不是独立表。它把版本组按代表成员的厂商归组：

```json
{
  "families": [
    {
      "familyKey": "openai",
      "displayName": "OpenAI",
      "modelCount": 1,
      "models": [
        {
          "modelKey": "gpt-image-2.0",
          "modelName": "GPT-Image-2.0",
          "modelType": "IMAGE",
          "company": "OpenAI",
          "groupKey": "gpt-image-2.0"
        }
      ]
    }
  ],
  "totalFamilies": 1,
  "totalModels": 1
}
```

厂商为空时归入 `other`，展示名为“其他”。`type` 语义与主目录一致。

## 8. 字段说明

| 字段 | 说明 |
|---|---|
| `modelKey` | 同版本模型组 key，调用执行接口时传该值 |
| `modelName` | 同版本模型组展示名 |
| `modelType` | `CHAT` / `IMAGE` / `VIDEO` / `AUDIO`，执行时强制校验 |
| `company` | 代表成员厂商 |
| `groupKey` | 所属同版本模型组 key |
| `iconUrl` / `description` | 代表成员展示资料，可为 `null` |
| `tags` | 代表成员标签，按展示顺序 |
| `free` / `thinking` | 是否免费、是否具备可配置思考模式 |
| `pointsOnly` / `pointsAllowed` | 历史兼容展示字段，不参与当前授权 |
| `consumePoints` | 组内可用成员的最低有效价格 |
| `contextWindow` | 上下文窗口，未登记为 `null` |
| `maxOutputTokens` | 最大输出 token 数，未登记为 `null` |
| `supportsVision` / `supportsTools` | 代表成员能力 |
| `inputPointsPer1k` / `outputPointsPer1k` | 代表成员分价，历史兼容展示字段 |

上游 `route` 不进入任何公开 DTO，结构上无法通过目录泄漏。

## 9. 执行时的降级边界

已接入统一组解析和降级的链路：

- 普通对话与流式对话；
- 智能体对话与工具调用；
- AI Canvas 文本调用；
- 普通图片生成与图片编辑；
- AI Canvas 图片生成；
- SmartEdit 图片编辑、重绘和生成。

只有 `LlmInvocationException` 会触发组内切换。参数校验、权限错误、取消和客户端连接错误不切换模型。

流式调用在发布第一条正文、思考或工具帧之前可以安全切换；一旦有内容发布后上游失败，则直接以失败结束，不会在另一个模型上重放并拼接出混合回答。

普通图片链路还保留一个兼容兜底：当 New API 返回 `model_price_error` 等“模型尚未定价”错误，且组内已全部失败时，会再尝试配置的默认图片模型；同一上游 route 不会重复调用。

这条规则此前**没有生效**：`model_type` 只被目录用来筛选，执行路径完全不读它。后果是绘画模型能被当对话模型调用，并且那一轮会以图像模型的行写进 `chat_messages.model_id`——同一个会话按模型统计、按 `model_id` 查单价时会落到另一套数据上。

现在对话与生图都经过 `modules/model/ModelUseResolver`，**类型与状态的判断只有一份**（此处刻意不重复实现：本表已被两条路径以不同规则读过一次并出过站点越权，见 §2）。类型校验只在目录里查得到该行时生效。

### 9.1 目录里没有这个 key 时怎么办

`app.llm.allow-unlisted-models` 默认 `false`，即**未登记的 key 直接 404，不原样透传上游**——否则客户端可以递一个上游路由名（如 `yunwu/gpt-image-2`）绕过目录、类型校验和站点可见性。

但它只在「目录已经认识这个模型类型」时才生效。三种情况必须分开看，混成一个答案会出事：

| 情况 | 行为 | 为什么 |
|---|---|---|
| key 为空（没人指定、也没配默认值） | `503`，文案「{默认绘图模型}未指定，且站点没有配置可用的默认模型」 | 报 `Model 'null' is not available` 会让人去找一个从没被提到过的模型 |
| 目录里**从来没有过**该类型的任何一行 | 原样透传 + `WARN` | 首次部署、同步失败、两次同步之间，表里一条都没有，**每个 key 都算未登记**；此时拒绝等于把对话、图片、视频一起下线，而网关是可用的 |
| 目录**认识**该类型，只是没有这个 key | 按开关处理（默认拒绝） | 目录可作准，拒绝才拦得住绕目录的调用 |

判断用 `LlmModelRepository.countByTypeForResolution`，**不筛状态、不筛站点**：

- 不筛状态：被停用/下架的行仍算「认识」。否则把某一类模型全部停用就等于放开透传。
- 不筛站点：别的站点的行必须继续不可见，而不是变成「未知」被转发上游（跨站点越权就是这么来的，见 §2）。

`allow-unlisted-models=true` 只留给「上游刚上线、目录还没同步」的窗口期，不要长期开着。

> 2026-10-29：该开关首次落地时只区分了「未登记」与「已登记」，把空目录也拒成了 404，
> SmartEdit / AiCanvas 共 14 例从 200/400/503 变成 404。上面的三分支就是那次修的。

## 10. 数据来源

| 表 | 用途 |
|---|---|
| `llm_models` | 具体模型、展示字段、上游 route、状态和价格 |
| `llm_model_fallback_groups` | 对外同版本模型组、类型、状态和展示顺序 |
| `llm_model_fallback_members` | 组成员、启停状态和自定义优先级 |
| `llm_model_tags` | 代表成员标签 |
| `llm_model_groups` | 旧渠道分组，仅保留给模型管理表单；不再决定公开目录分组 |

## 11. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `400` | 用错模型类型，例如把 `IMAGE` 组传给对话接口 |
| `404` | 组不存在、已停用、没有有效成员或对当前站点不可见 |

公开目录的未知 `group` 筛选和空类型结果返回空数组，不报错。

## 12. 测试

重点用例：

- `ModelFallbackCatalogServiceTest`：价格顺序、自定义顺序、站点覆盖、作用域和 route 去重；
- `ModelFallbackInvokerTest`：错误类型、重试与流式发布边界；
- `ModelFallbackAdminControllerTest`：后台增删改、候选过滤、跨系列成员和排序切换；
- `ModelCatalogControllerTest`：只展示版本组、旧渠道组不影响目录、未分组隐藏；
- 对话、智能体、Canvas、图片和 SmartEdit 的定向回归测试。

```powershell
.\mvnw.cmd test
```
