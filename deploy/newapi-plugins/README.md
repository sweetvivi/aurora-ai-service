# new-api 任务插件覆盖层

这里放**我们自己维护的 new-api 任务插件**，用来修补内置插件的缺陷。上传后写入
new-api 的 `task_plugins` 表（编译期内置插件的覆盖层），**不需要重编译 new-api**。

## 结论先行：覆盖层救不了取件，本站仍用直连

投入一整轮排查后确认：**插件层无法修好「中继转发取件」这条路**，本站继续使用
`llm_providers.video_download_base_url` + `llm_providers.video_download_api_key` 直连供应商。
覆盖层保留 1.1.5，作为「上游若是网关」时的尽力而为，但**不是**取件成功的原因。

V83 之后取件的两处配置都在供应商行上，不再需要 `LLM_VIDEO_DOWNLOAD_API_KEY` 环境变量
（该变量只作老部署的兼容回落）。原因与端点见 `docs/provider-admin-api.md`
「供应商级视频下载源」。

## 取件为什么必须在插件层之外解决

New API 自己的设计是**取件透明**：`BuildProxyURL(taskID)` 用
`system_setting.ServerAddress` 生成 `%s/v1/videos/%s/content`，任务成功时把
`private_data.result_url` 写成中继自己的地址。调用方本不该知道供应商是谁。

但本站这条链路有三处叠在一起，插件改不到：

1. **调用方拿到的是上游原始报文，不是中继渲染的报文。**
   本站后端的轮询路径是 `LLM_VIDEO_STATUS_PATH=/xai/v1/videos`，命中的是插件的
   **原生 query 路由**（`native.videoStatus`），它把任务存储的 `task.data` 原样
   返回。上游Aurora给的 `video.url` 是 `/v1/videos/{上游 uuid}/content`。
   后端直接下载这个 URL，**从不调用插件的 `buildContentRequest`** —— 所以最初
   「补 base + Bearer」的修法根本没有执行路径。

2. **那个 id 是上游 uuid，不是中继的公开任务 id。**
   中继的 content 路由按自己的 `task_xxx` 寻址（`ctx.publicTaskId`）。拿上游 uuid
   去取，中继一律 `404`：

   ```
   GET /v1/videos/602dbb67-b2a9-…/content        404   ← 上游 uuid
   GET /v1/videos/task_otANZekad…/content        200   ← 中继 id（实测 334471 字节）
   ```

3. **query 渲染上下文拿不到拼地址所需的一切。**
   `renderTaskPluginQuery` 传给 `native[renderer]` 的只有 `path` / `method` /
   `params` / `query` / `requestHeaders` / `body`，外加宿主投影的 `artifacts`；
   **没有 `baseUrl`，也没有 `publicTaskId`**。在 1.1.4 里按
   `ctx.baseUrl && publicTaskId(ctx)` 重写 URL，条件恒为假，逻辑被静默跳过。

`artifacts` 本来是正解（宿主已算好带签名的绝对地址），但它依赖
`system_setting.TaskPublicAddress` / `ServerAddress`；本站该选项为空，
`BuildTaskArtifactContentURL` 直接失败，`ctx.artifacts` 是 `{}`，同一条 `result_url`
也才会显示成 `http://localhost:3000/...`。

**所以哪怕把上面三条全填上，也只是把「直连Aurora」换成「经中继转发」，而中继转发
需要额外的公网地址配置；直连方案已经跑通且不依赖这些。**

## 覆盖层里保留了什么

| 插件 | 版本 | 现状 |
|---|---|---|
| `xai/plugin.js` | 1.1.5 | 两条自愈分支：`buildContentRequest` 用公开任务 id 重拼地址；`native.videoStatus` 在有 `artifacts` 时替换成宿主签名地址。**当前配置下都不会被走到**，但删掉就会退回 410。 |

相对内置 1.1.0 的全部差异：

- `buildContentRequest`：绝对地址原样 `credentialless` 透传；相对路径改用
  `ctx.baseUrl + /v1/videos/{publicTaskId}/content` 并带 `Bearer ctx.apiKey`。
- `native.videoStatus`：`video.url` 是相对路径且 `ctx.artifacts.video.url` 存在时，
  换成宿主给的那个签名绝对地址。
- 其余（routes / meta / 计费 / 协议）与 1.1.0 完全一致。

按 URL 形态分支而不是按 `ctx.upstream.kind`：本站渠道是 **type 48（xAI）**，
`ctx.upstream.kind` 为 `vendor`，照搬 hailuo 的 `viaGateway` 会走错分支。

## 基底

`plugin.js` 的基底是**官方 1.1.0 原文**，
`sha256 = 9ec1f0514fae96ed945a493636ded0f280ae340a6fd493e108c7e18e665b52a7`
（与插件市场索引 `xai.versions[1.1.0].sha256` 一致）。

**不要用 1.2.0 当基底**：它把 routes 改成了 `/v1/videos/*`，
而 rc.45 的路由注册机制不同，上传会因 `PreflightRoutingConflict` 被拒。

## 本地自检

```bash
node deploy/newapi-plugins/xai/selftest.mjs
```

17 个用例，全部离线：绝对地址透传、相对路径重拼、上游 uuid 不被复用、末尾斜杠、
base 带路径前缀、HEAD 透传、缺密钥/缺任务 id 显式报错、审核拦截、未完成任务、
meta 与 routes 不变、`listArtifacts`、`native.videoStatus` 的四种形态。

## 上传、激活与回滚

需要 **RootAuth**（`nap_` 访问令牌缺 `plugin:read`/`plugin:write`）：

```bash
# 上传（只是入库，不激活）
curl -X POST "$RELAY/api/plugin/task" \
  -H "Authorization: Bearer $JWT" -H "New-Api-User: 1" \
  -H 'Content-Type: application/json' \
  -d '{"source": "<plugin.js 全文>", "sourceSha256": "<hex>", "enabled": true, "remark": "..."}'

# 激活（不上这一步，跑的还是旧版本）
curl -X POST "$RELAY/api/plugin/task/xai/activate" \
  -H "Authorization: Bearer $JWT" -H "New-Api-User: 1" \
  -H 'Content-Type: application/json' -d '{"version":"1.1.5"}'
```

⚠️ **上传 ≠ 激活**。两个版本会并存，`GET /api/plugin/task/xai` 返回的仍是旧版；
必须显式 `activate`，`current_generation` 才会 +1。

- 预演：`POST /api/plugin/task/xai/dryrun` `{"hook":"buildContentRequest","args":[{...ctx}]}`
- 版本列表：`GET /api/plugin/task/xai/versions`
- 回滚：`DELETE /api/plugin/task/xai/versions/1.1.5` 或 `activate` 到旧版本
- 生效确认：`GET /api/plugin/task/runtime/status` 的 `current_generation` +1，
  `plugin_error_count` 为 0

⚠️ **历史任务不受影响**。任务按 `private_data.execution.task_plugin.version` 归属，
替换前创建的任务仍走旧版本。要验证新版必须新建任务。

⚠️ **管理员会话有上限（50）**。每次脚本登录都新增一个 session，反复调试会把
`hub-admin` 顶到 `AUTH_SESSION_LIMIT`，此时连激活都做不了。清理办法（停机十几秒）：

```bash
docker stop hub-test-new-api
docker run --rm -v hub-test_new-api-data:/data -v /vol1/1000/hub-test:/host alpine \
  sh -c 'apk add --no-cache sqlite >/dev/null 2>&1; sqlite3 /data/one-api.db < /host/fix-sessions.sql'
docker start hub-test-new-api
```

`fix-sessions.sql` 内容见 `D:\hub\.tools\`：把 `user_id=1` 的 active 会话置为
`revoked`。`status` 是字符串列，写 `1`/`2` 不会命中任何行。

## 升级 new-api 后的复查

1. `GET /api/plugin/task/runtime/status` 的 `current_generation` 是否重新上涨、
   `plugin_error_count` 是否仍为 0。
2. `GET /api/plugin/task/xai` 的 `version` 是否仍是 1.1.5（覆盖层还在生效）。
3. 若上游修好了取件（把 `viaGateway` 思路推广到 xai，或改用
   `system_setting.TaskPublicAddress`），可以直接回滚覆盖层。
