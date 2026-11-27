# 应用配置接口（`appconfig`）

> 状态：已实现并跑通真实 MySQL。站点识别 + 初始化配置下发。
> 涉及代码：`modules/appconfig`（Controller / Service / ChannelResolver / 实体仓储）、`common/config/properties/AppProperties`。
> 迁移：`V3__app_site_settings.sql`。

本文对应 `docs/project-structure.md` §4.2 模块表的第一个模块，以及 `docs/ai1foo-rebuild-plan.md` §10 契约表的首条接口。

---

## 1. 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/app/config` | **规范入口**，站点初始化配置 |
| `POST` | `/api/v2/config/init` | 兼容线上旧路径，响应与上者完全一致 |

两个入口共用同一个 `AppConfigService`，响应契约只有一份，旧路径只是 Controller 适配层映射。

典型用法：客户端启动时先调一次，拿到白标名称、功能开关、公告与默认模型，再渲染工作区。

## 2. 站点解析（`ChannelResolver`）

按以下优先级确定请求归属站点：

1. **`app-name` 请求头**（头名可配 `app.site.site-header`）：显式声明站点；
2. **请求 Host**：用 `app_sites.domain` 反查（可配 `app.site.domain-fallback` 关闭）；
3. **服务端默认站点**：`app.site.default-key`（默认 `default`）。

规则细节：

- 显式传入但**无法识别**的 `app-name` 直接返回 `404`，**不会降级**到其他站点 —— 避免客户端配错时读到别的租户配置。
- Host 反查失败（例如本地 `localhost`）才回退到默认站点，属于正常路径。
- 默认站点本身不存在时同样返回 `404`，属于部署配置错误。

## 3. 响应契约

```json
{
  "siteKey": "default",
  "appName": "Default Site",
  "domain": null,
  "status": "ACTIVE",
  "defaultModel": "deepseek-v4.1-flash",
  "features": {
    "chat": true,
    "stream": true,
    "attachment": false,
    "share": true,
    "points": false
  },
  "announcement": "欢迎使用 AI 对话服务",
  "serverTime": "2026-10-14T09:31:36.191117600Z"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `siteKey` | string | 解析出的站点标识 |
| `appName` | string | 站点展示名称，前端白标用 |
| `domain` | string \| null | 站点主域名，未绑定为 `null` |
| `status` | string | 站点状态，`ACTIVE` 之外的状态由前端自行降级处理 |
| `defaultModel` | string | 用户未选模型时的默认值 |
| `features` | object | 功能开关，**已与服务端默认值合并** |
| `announcement` | string \| null | 首页公告 |
| `serverTime` | string | 服务端时间（ISO-8601，UTC），供客户端校正本地时间偏差 |

## 4. 配置层级

配置按「服务端默认 → 站点覆盖」两层合并，因此**站点没有配置任何一行也能正常返回**：

| 键 | 层级 | 默认值 | 说明 |
|---|---|---|---|
| `app.site.default-features.*` | 服务端 | 见下 | 全站基线开关 |
| `feature.<name>` | 站点 | — | 覆盖同名开关 |
| `default_model` | 站点 | `app.llm.default-model` | 覆盖默认模型 |
| `announcement` | 站点 | `null` | 公告 |

服务端默认开关（`app.site.default-features`）：`chat=true`、`stream=true`、`attachment=false`、`share=false`、`points=false`。

站点级开关写在 `app_site_settings` 表的 `feature.` 命名空间下，值类型为 `BOOLEAN`；无法解析为布尔字面量时保留上一层取值，不会因脏数据把开关变成 `false`。

## 5. 数据来源

`app_sites`（站点主体） + `app_site_settings`（站点级设置，V3 新增）。

> 为什么不直接用 V1 的 `app_sites.config_json`：JSON 列在 Hibernate 与 MySQL/H2 之间存在类型码不一致，`ddl-auto=validate` 下极易启动失败；且整块 JSON 无法按 key 更新与审计。结构化设置表支持单键修改、索引查询与后续的配置版本化（见 `docs/project-audit.md` 关于站点配置版本的规划）。`config_json` 保留给低频、无需查询的站点载荷。

## 6. 错误语义

| 状态码 | 触发条件 |
|---|---|
| `404` | `app-name` 指定的站点不存在，或默认站点不存在 |

```json
{
  "timestamp": "2026-10-14T09:31:36.265118600Z",
  "status": 404,
  "error": "Not Found",
  "message": "Site 'ghost' was not found",
  "path": "/api/v1/app/config",
  "details": {}
}
```

## 7. 配置项

| 配置 | 默认值 | 说明 |
|---|---|---|
| `app.site.default-key` | `default` | 兜底站点标识 |
| `app.site.site-header` | `app-name` | 站点声明请求头 |
| `app.site.domain-fallback` | `true` | 是否允许按 Host 反查站点 |
| `app.site.default-features.*` | 见 §4 | 服务端基线功能开关 |

## 8. 测试

| 用例类 | 覆盖 |
|---|---|
| `ChannelResolverTest` | 头优先于 Host、未知头拒绝、空头回退、Host 反查、关闭 domain-fallback、默认站点缺失 |
| `AppConfigControllerTest` | 开关合并、站点头选站、Host 选站、未知站点 404、旧路径同响应、OpenAPI 路径 |

```powershell
.\mvnw.cmd test
```

## 9. 尚未覆盖（后续阶段）

- 广告位 `GET /api/v1/app/adverts`（`project-structure.md` 中 appconfig 的职责之一，需要新增广告表）；
- 站点配置的版本化、发布审计与缓存刷新（`ai1foo-rebuild-plan.md` §7.2）；
- 配置变更的管理端接口与 RBAC 保护。
