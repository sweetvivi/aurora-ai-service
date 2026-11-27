# AI 画布（TwitCanva）接口对接问题清单

| 项目 | 内容 |
| --- | --- |
| 文档日期 | 2026-10-19 |
| 依据文档 | `D:\hub\web-app\docs\aiCanvasApiCompat.md`（AI 画布子站接口对接与兼容说明） |
| 后端仓库 | `D:\hub\backend-service`（接口实现方） |
| 前端仓库 | `D:\hub\web-app`（子站 `twitcanva/`，接口调用方） |
| 接口前缀 | `/api/v1/ai-canvas` |
| 核对方式 | 逐条比对依据文档第 4 节差异清单与两端源码 |

---

## 1. 总览

| 编号 | 级别 | 问题 | 责任方 | 状态 |
| --- | --- | --- | --- | --- |
| Q1 | P1 | 异步视频任务缺少轮询，`async=true` 时前端直接报错 | 前端 | 已修复（前端，2026-10-19） |
| Q2 | P1 | 视频生成 503 的可读提示在画布上显示不出来 | 前端 | 已修复（前端，2026-10-19） |
| Q3 | P1 | 分镜脚本缺 `cameraMovement` / `lighting` 字段 | 后端 | 已修复 |
| Q4 | P2 | `aspectRatio: "Auto"` 直传上游 | 后端 | 已兼容（原文档描述已过时） |
| Q5 | P2 | 视频裁剪接口只接受 base64 / 公网地址 | 前端 | 已适配，仅记录 |
| Q6 | P2 | 灵感画板消息必填约束 | 前端 | 已适配，仅记录 |
| Q7 | — | 视频生成供应商整体未接入，视频能力不可用 | 后端 / 产品 | 阻塞性依赖 |
| Q8 | P0 | 模型下拉使用前端硬编码列表，与 `GET /api/v1/models` 真实目录不一致 | 前端 | 已修复（前端，2026-10-19） |
| Q9 | P1 | 重开画布丢失上次工作流，残留 `loading` 节点让已生成的图片变回「生成中」 | 前端 | 已修复（前端，2026-10-19），后端无需改动 |
| Q10 | P1 | 图生图：平台地址带 `?t=` 缓存戳被误判为外部直链，返回 502 | 后端 + 前端 | 已修复（后端 + 前端，2026-10-19） |
| Q11 | P1 | 素材上传白名单过窄（`image/jpg`、`.jfif`、`.svg`、无扩展名等一律 400），且失败原因被前端吞掉 | 后端 + 前端 | 已修复（后端 + 前端，2026-10-19） |

结论：接口本身无缺失，依据文档第 2 节列出的全部端点均在 `AiCanvas*Controller`
中存在并可用。剩余问题集中在**视频异步任务的轮询与错误提示的前端处理**，
以及**文档与实际实现的同步**上。

---

## 2. 待处理问题

### Q1 [P1] 异步视频任务缺少轮询

**现象**

后端 `AiCanvasGenerationResult` 带有 `async` / `taskId` / `status` 字段，并提供
`GET /tasks/{taskId}` 查询进度；前端 `generateVideo()` 只读取一次响应，没有轮询。
一旦后端实现异步返回（`async=true`、`videoUrl` 为空），前端会直接抛出
「服务端未返回视频地址」，任务结果永远落不到画布节点上。

**影响**

任何非即时返回的视频生成都会在画布上表现为失败，即使任务最终成功。

**责任方**：前端

**建议修复**

在 `web-app/twitcanva/src/services/generationService.ts` 的 `generateVideo()` 中，
当 `data.async === true` 且存在 `taskId` 时轮询 `GET /tasks/{taskId}`，
直到 `status` 为 `succeeded` 或 `failed`：

- `succeeded`：取 `videoUrl || url` 返回；
- `failed`：抛出任务携带的错误信息；
- 轮询需设置间隔与总超时上限，避免请求悬挂。

**验收标准**

后端返回 `async=true` 时，画布节点最终能落到成功或明确的失败态，不再出现
「服务端未返回视频地址」。

---

### Q2 [P1] 视频生成 503 的可读提示在画布上显示不出来

**现象**

后端统一错误体 `ApiError` 同时返回 `error`（HTTP 状态短语，如
`Service Unavailable`）与 `message`（可读描述）。前端
`web-app/twitcanva/src/services/apiClient.ts` 的 `toApiError()` 取值顺序为：

```ts
message = data?.error || data?.message || message;
```

`error` 排在 `message` 之前，于是 `error` 永远命中，后端精心准备的中文描述
（如「视频生成服务未配置：……」）被丢弃，画布上只显示 `Service Unavailable`。

**影响**

用户看到的是英文状态短语，无法判断是「功能未配置」「额度不足」还是「上游故障」。

**责任方**：前端（后端侧文案已就绪）

**建议修复**

调整 `toApiError()` 的取值优先级，让业务描述优先于状态短语，例如：

```ts
message = data?.message || data?.msg || data?.error || response.statusText;
```

**验收标准**

视频生成在未配置供应商时，画布上显示后端返回的中文提示，而不是
`Service Unavailable`。

**备注**

后端侧已完成：503 错误体已带中文 `message`（见第 3 节「视频 503 返回可读中文」）。

---

## 3. 已完成适配（后端 `D:\hub\backend-service`）

### Q3 [P1] 分镜脚本补 `cameraMovement` / `lighting` — 已修复

**原问题**

`AiCanvasScript` 只返回 `sceneNumber` / `description` / `cameraAngle` / `mood` /
`prompt`，而前端 `SceneScript` 声明了可选的 `cameraMovement`、`lighting`，并在
`StoryboardVideoModal` 拼接视频提示词时使用它们。两字段恒为 `undefined`，
生成的视频提示词缺少镜头运动与光线信息。

**改动点**

| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/hub/backend/modules/aicanvas/dto/AiCanvasDtos.java` | `AiCanvasScript` 新增 `cameraMovement`、`lighting` 字段 |
| `src/main/java/com/hub/backend/modules/aicanvas/AiCanvasAssistantService.java` | 分镜生成提示词的 JSON 模板补充这两个键；`parseScripts()` 解析并回填 |
| `src/test/java/com/hub/backend/modules/aicanvas/AiCanvasControllerTest.java` | 增加 `cameraMovement` / `lighting` 的响应断言 |

**接口表现**

`POST /storyboard/generate-scripts` 响应示例（节选）：

```json
{
  "scripts": [
    {
      "sceneNumber": 1,
      "description": "开场全景",
      "cameraAngle": "俯视 45 度",
      "cameraMovement": "缓慢推近",
      "lighting": "黄昏侧逆光",
      "mood": "温暖",
      "prompt": "橘猫坐在窗边"
    }
  ],
  "styleAnchor": "暖色写实",
  "characterDNA": {}
}
```

模型未返回该字段时回退为空字符串，保证字段始终存在，前端 `script.cameraMovement ?`
判空逻辑不受影响。

---

### 视频 503 返回可读中文 — 已废弃（改为接入视频模块）

原实现用 503 + 中文文案表示「供应商未接入」。2026-11 起画布视频生成改为转交平台视频模块，
正常提交返回 `202/200 + {async, taskId}`，503 只可能来自视频模块自身的网关故障，见 Q7。

---

### Q4 [P2] `aspectRatio: "Auto"` — 已兼容，原文档描述过时

**核查结果**

依据文档称「后端 `aspectRatio` 仅为 `maxLength=32` 的字符串，会原样透传给上游」，
与实际实现不符。当前后端已在两个位置识别并忽略 `Auto`：

| 位置 | 行为 |
| --- | --- |
| `AiCanvasGenerationService.imageSize()` | `Auto` → 返回 `null`，交由上游使用默认比例；非法值返回 400 |
| `AiCanvasGenerationService.normalizedAspectRatio()` | `Auto` / 空值 → 返回 `null`，响应不回写比例 |

**结论**：无需后端改动。见第 6 节，依据文档对应条目需要更正。

---

## 4. 已适配 / 仅需记录（前端，无需改动）

### Q5 [P2] 视频裁剪只接受 base64 / 公网地址

后端 `AiCanvasVideoTrimRequest.videoDataUrl` 只接受 base64 Data URL 或公网
`http(s)` 地址；画布中的视频是受保护地址
`/api/v1/ai-canvas/assets/{key}/content`。前端已适配：`apiClient.ts` 的
`resolveVideoDataSource()` 先取字节再转 Data URL，由
`hooks/useVideoEditor.ts` 调用后进行裁剪。属已适配的差异，避免后续回退。

### Q6 [P2] 灵感画板消息约束

后端 `AiCanvasChatRequest` 的 `message`、`sessionId` 均为必填。前端在只发送图片时
补默认文案「请分析这张图片」，并在发送前用 `POST /chat/sessions` 确保已有
`sessionId`。`media` 目前仅识别图片。属已适配。

### 其他已确认一致项

| 事项 | 结论 |
| --- | --- |
| 素材分类枚举 | 后端 `requireCategory()` 只接受 `character / scene / object / style / sound / other`，非法值 400，与文档一致 |
| 画布保存必填项 | `title` / `nodes` / `groups` / `viewport` 必填，`id` / `description` / `coverKey` 可选，与文档一致 |
| 生成响应结构 | `async` / `taskId` / `status` / `url` / `aspectRatio` / `videoUrl`，与文档一致 |
| 端点完整性 | 文档第 2 节列出的全部端点均已实现，无缺失 |
| 社交发布（X / TikTok） | 前端整体关闭，后端无对应接口，符合文档预期 |
| 调整镜头角度 | 走外部服务 `VITE_MODAL_CAMERA_ENDPOINT`，后端无接口，符合文档预期 |

### Q9 [P1] 重开画布丢失上次工作流、已生成图片变回「生成中」— 前端已修复

**现象**

- 每次打开 `/canvas` 都回到空画布，需要在「我的工作流」里重新找上一次的画；
- 上次生成中保存过的节点，下次打开仍是 `loading`：有结果图的显示「正在重新生成…」，
  无结果的显示「生成中…」并且一直转圈。

**责任方**：前端。后端按请求原样存取节点与状态，行为本身正确，**不涉及接口变更**。

**根因**

| 缺陷 | 原因 |
| --- | --- |
| 记不住上次工作流 | `workflowId` 只存在 React state，刷新即丢，页面没有自动加载逻辑 |
| 残留 `loading` | 生成中保存的快照会永久停在 `loading`，重开时没有校正 |
| `loading` 覆盖终态 | 自动保存 60s 一次且不串行，生成开始的 `loading` 快照可能晚于生成完成的 `success` 快照落库 |

**前端修复**（`D:\hub\web-app\twitcanva`）

- 新增按登录用户隔离的本地键 `localStorage['twitcanva_current_workflow:{JWT sub}']`，
  保存成功 / 加载成功 / 新建画布时同步；`GET /workflows/{id}` 返回 404 时清除失效 ID。
- 首次挂载后自动 `GET /workflows/{id}` 并恢复 `nodes` / `groups` / `viewport`。
- 加载时校正残留状态：`loading` 且有 `resultUrl` → `success`；无结果 → `error`
  并提示「上次生成未完成，请重新生成。」。
- 保存串行化，自动保存延迟 60s → 1.5s，保存期间的新变更排队补存，避免旧快照覆盖终态。

**后端侧结论**：无改动、无需新增接口。工作流数据仍全部落 `workflows` 表，本地只记
「上次打开的 ID」，换浏览器或清缓存只会回到空画布，不会造成两端数据不一致。

**可选后续**：若后续希望客户端零本地状态，可新增按登录用户返回最近一条画布的接口
（例如 `GET /workflows/current`）；当前实现不依赖它。

### Q10 [P1] 图生图：平台地址带 `?t=` 缓存戳被误判为外部直链 — 后端 + 前端已修复

**现象**

对已有结果图的节点再次执行「图片生成图片」，`POST /images/generations` 返回 502，
节点报错 `Generated image URL must use http or https, got: null`。

**责任方**：后端 + 前端。后端正则漏匹配查询串是直接原因，前端回传缓存戳是触发源，
两侧都已修。

**根因**

| 环节 | 原因 |
| --- | --- |
| 前端 | 生成成功后给 `resultUrl` 追加 `?t=<时间戳>` 缓存戳；图生图时带查询串的父节点地址被作为参考图回传 |
| 后端 | `AiCanvasImageResolver.PLATFORM_FILE_URL` / `CANVAS_ASSET_URL` 以 `content/?$` 结尾，`?t=...` 匹配失败，地址被当成外部直链交给 `ImageUrlDownloader` |
| 结果 | 相对路径没有 scheme，下载器抛 `Generated image URL must use http or https, got: null`，冒泡为 502 |

**后端修复**（`AiCanvasImageResolver`）

- `PLATFORM_FILE_URL` → `(?:^|/)api/v1/files/(\d+)/content/?(?:[?#].*)?$`
- `CANVAS_ASSET_URL` → `(?:^|/)api/v1/ai-canvas/assets/([^/?#]+)/content/?(?:[?#].*)?$`
- 新增测试 `imageGenerationAcceptsPlatformReferenceUrlWithCacheBuster`：用 `url + "?t=..."` 作参考图，
  断言 200、走 `fileService`、且 `imageUrlDownloader` 从未被调用。

**前端修复**（`D:\hub\web-app\twitcanva`）

- `services/generationService.ts` 新增 `toBackendReferenceUrl()`，图生图参考图、视频首帧 / 末帧 /
  运动参考在回传前剥离 `?t=`（相对地址保持相对）。

**接口契约**：`referenceUrls` 及视频参考字段允许携带缓存戳。`SmartEditImageService` 的同类
正则用 `URI.getPath()` 取路径，天然剥离查询串，无需改动。

**验证**：后端 `AiCanvasControllerTest` 16/16 通过；重启后复跑
`referenceUrls=/api/v1/files/999999/content?t=...` 从 502 变为 404「File 999999 was not found」
（正确进入平台文件分支），`/api/v1/ai-canvas/assets/nope/content?t=...` 同样返回 404「asset 'nope' was not found」。

### Q11 [P1] 素材上传白名单过窄，失败原因被前端吞掉 — 后端 + 前端已修复

**现象**

画布右键 →「上传」选择文件后提示「上传文件到服务器失败。」，无从判断是格式、大小还是别的问题。

**责任方**：后端（白名单过窄）+ 前端（把 `msg` 吞掉、只弹统一文案）。

**根因**

| 环节 | 原因 |
| --- | --- |
| 后端 | `AiCanvasAssetService` 只允许 `png/jpg/jpeg/gif/webp/bmp` + 少量视频音频，且**扩展名与 MIME 必须同时命中**。实测 `image/jpg`、`.jfif`、`.heic`、`.svg`、`.avif`、无扩展名文件全部 400 |
| 前端 | `useAssetHandlers.handleContextUpload` 只 `console.error`，用户侧统一显示「上传文件到服务器失败。」；`accept="image/*,video/*"` 又让用户以为浏览器接受的文件后端也接受 |

**后端修复**（`AiCanvasAssetService` / `AiCanvasAssetController`）

- 扩展名白名单扩充：新增 `jpe`、`jfif`、`avif`、`apng`、`svg`、`ico`、`mkv`、`m4v`、`mpeg`、`3gp`、`ogv`、`opus`、`weba`、`aif`、`aiff`。
- MIME 校验改为「扩展名 → 规范 MIME」+「规范 MIME → 落库扩展名」两张表，并新增别名收敛表：
  `image/jpg`、`image/pjpeg` → `image/jpeg`；`image/x-png` → `image/png`；`image/x-ms-bmp` → `image/bmp`；
  `image/vnd.microsoft.icon` → `image/x-icon`；`audio/x-wav` → `audio/wav`；`audio/x-m4a` → `audio/mp4`；`audio/x-aiff` → `audio/aiff`。
- 无扩展名（或扩展名与 MIME 冲突）时按 MIME 归一化落库，`assets` 类型由 MIME 前缀推导。
- 内容接口补安全头：一律 `X-Content-Type-Options: nosniff`；`image/svg+xml` 追加
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`，避免直接打开素材地址时执行 SVG 内嵌脚本。
- 新增测试 `assetUploadAcceptsFormatAliasesAndDerivesMissingTypes`：`image/jpg` → 200 且 `mimeType=image/jpeg`；
  无扩展名 + `image/png` → 200；`logo.svg` → 200 且带 CSP；`image/heic` → 400 且 `msg` 为 `Unsupported AI Canvas asset content type: image/heic`。

**前端修复**（`D:\hub\web-app\twitcanva`）

- `hooks/useAssetHandlers.ts` 新增 `describeUploadFailure()`：把后端原因翻成中文（未登录 / 超过 200MB /
  配额用尽 / 不支持的格式 / 网络不通），不再吞掉原因。
- `file.type` 为空时按扩展名兜底判断图片 / 视频；HEIC / HEIF 在前端直接提示「请先转换为 JPG 或 PNG 再上传」。
- `components/ContextMenu.tsx` 的 `accept` 增加显式扩展名，避免「扩展名未关联 MIME」的文件在 Windows 文件选择框里被隐藏。

**接口契约**

- 支持格式（扩展名 / MIME 任一命中即可）：
  - 图片：`png`、`jpg`/`jpeg`/`jpe`/`jfif`（含 `image/jpg`、`image/pjpeg`）、`gif`、`webp`、`bmp`、`avif`、`apng`、`svg`、`ico`；
  - 视频：`mp4`、`webm`、`mov`、`avi`、`mkv`、`m4v`、`mpeg`/`mpg`、`3gp`、`ogv`；
  - 音频：`mp3`、`wav`、`ogg`、`aac`、`m4a`、`flac`、`opus`、`weba`、`aif`/`aiff`。
- 不支持的格式仍返回 400，`msg` 形如 `Unsupported AI Canvas asset content type: image/heic`。
- **HEIC / HEIF 仍未纳入**：浏览器无法解码，画布上会显示破图。若要支持，需要后端新增「HEIC 解码 → 转 JPEG 落库」的转码步骤，属独立需求。

**验证**：`AiCanvasControllerTest` 17/17 通过；重启后端后逐个 curl 复核
`image/png` / `image/jpg` / `.jfif` / `.svg` / 无扩展名 → 200，`image/heic`、`application/octet-stream` → 400；
浏览器脚本 `.codex/verify-asset-upload.cjs` 三例通过（PNG、SVG 各新增 1 节点，HEIC 提示转换）。

---

## 5. 阻塞性依赖（非接口问题）

### Q7 视频生成 — 已接入平台视频模块（原：供应商未接入）

**原状态**：`AiCanvasGenerationService.generateVideo()` 在校验模型类型后直接抛
`ServiceUnavailableException`，`POST /videos/generations` 恒返回 503。

**现状**：画布视频生成不再自己对接供应商，而是把请求翻译成平台视频模块的契约后交给
`VideoGenerationService`（即前台 `/api/v1/videos/generations` 那条链路）：

- 首帧 / 尾帧的 data URL、裸 base64、平台附件地址、画布素材地址统一落成附件，平台附件
  地址直接复用；提交失败时回滚本次新落的附件；
- 生成模式由帧数量推断（`first-last-frame` / `image2video` / `text2video`）；
- `aspectRatio` / `resolution` 的 `Auto` 归一为「不传」；
- `motionReferenceUrl` 直接拒绝：平台视频链路的 `VideoGenerationCommand` 没有动作参考字段，
  静默忽略会让用户拿到一段不跟动作的成片；
- 提交返回 `{async:true, taskId}`，`GET /tasks/{taskId}` 每次回读视频模块的真实状态并回写
  `ai_canvas_tasks` 索引行，因此画布历史不会一直停在 running；
- 余额校验、并发上限（429）、模型能力校验、计费与结果落库全部沿用视频模块。

**仍需运维确认**：`app.llm.video-model` / VIDEO 版本组里有可用模型，否则请求会以 404
（模型不存在或已下架）结束。

---

## 6. 依据文档需要同步的条目

`D:\hub\web-app\docs\aiCanvasApiCompat.md` 第 4 节以下条目与实际实现已不一致，
建议更新，避免后续按旧结论重复排查：

| 文档条目 | 现状 |
| --- | --- |
| 4.1 分镜脚本缺少 `cameraMovement` / `lighting` | 后端已补齐，可标记为「已解决」 |
| 4.2 视频生成错误文案 | 后端已提供中文 `message`；改为记录前端 `toApiError` 取值优先级问题 |
| 4.3 异步视频任务缺少轮询 | 问题仍成立，建议保留并明确责任方为前端 |
| 4.4 `aspectRatio: "Auto"` 直传后端 | 后端已识别并忽略 `Auto`，条目可移除或标记为「已解决」 |

---

## 7. 验证记录

**后端**

```powershell
cd D:\hub\backend-service
.\mvnw.cmd "-Dtest=AiCanvasControllerTest,OpenAiCompatibleImageEditingClientTest,OpenAiCompatibleImageGenerationClientTest" test
```

结果：3 个测试类、43 个用例全部通过（含本次新增的 `cameraMovement` / `lighting`
响应断言与视频 503 中文文案断言）。

**待补验证**（需前后端同时就绪）

1. 视频生成 503 场景下，画布上显示中文提示（依赖 Q2 修复）；
2. 后端返回 `async=true` 时，画布节点能正确落到成功 / 失败态（依赖 Q1 修复）。

**前端**（2026-10-19，`D:\hub\web-app`）

| 脚本 | 覆盖 | 结果 |
| --- | --- | --- |
| `.codex/verify-new-canvas.cjs` | 路由守卫、登录、`/canvas` 与 `/canvas/old` 加载、无 4xx/5xx | 通过 |
| `.codex/verify-video-error-zh.cjs` | 视频 503 的中文提示可见（Q2） | 通过 |
| `.codex/verify-video-poll.cjs` | mock 异步任务，验证 `GET /tasks/{taskId}` 轮询（Q1） | 通过 |
| `.codex/verify-model-catalog.cjs` | 图片节点模型下拉展示 `GET /api/v1/models` 真实目录（Q8） | 通过 |
| `.codex/verify-canvas-resume.cjs` | 重开画布自动恢复上次工作流、残留 `loading` 校正、视图恢复、自动保存（Q9） | 通过 |
| `.codex/verify-img2img-ref-cache.cjs` | 图生图回传参考图剥离 `?t=` 缓存戳（Q10）：预置「父图结果地址带 `?t=` → 子节点图生图」工作流，拦截真实 `POST /images/generations` | 通过：`referenceUrls` 为 `/api/v1/ai-canvas/assets/seed-key/content`，无查询串 |
| `.codex/verify-asset-upload.cjs` | 素材上传（Q11）：右键画布「上传」依次注入 PNG / SVG / HEIC，读取 `POST /api/v1/ai-canvas/assets` 状态、页面 alert 与节点增量 | 通过：PNG 与 SVG 各返回 200 并新增 1 个节点、无 alert；HEIC 不发请求且提示「HEIC / HEIF 格式浏览器无法直接显示，请先转换为 JPG 或 PNG 再上传。」 |
