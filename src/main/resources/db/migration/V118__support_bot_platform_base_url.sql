-- 客服上游基地址：与平台 API Key 一并在管理端配置，不依赖环境变量。

ALTER TABLE support_bot_config
    ADD COLUMN platform_base_url VARCHAR(512) NULL COMMENT '客服上游 OpenAI 兼容基地址，如 https://host/v1' AFTER brand_color;
