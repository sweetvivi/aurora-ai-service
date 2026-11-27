-- 客服对话平台 API Key：管理端可配，费用记平台侧；不回显原文。

ALTER TABLE support_bot_config
    ADD COLUMN platform_api_key TEXT NULL COMMENT '客服调用上游的平台密钥；空表示未配置' AFTER brand_color;
