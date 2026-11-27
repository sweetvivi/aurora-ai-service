-- New API 的分组，以及每个模型在哪些分组里可用。
--
-- 广场响应本来就带齐了这些信息：`usable_group`（分组名 → 描述）、`group_ratio`（分组名 → 倍率），
-- 以及每个模型的 `enable_groups` —— 这一份**不会**被调用者自己的分组裁剪，只是会裁剪「模型行」本身。
-- 落库之后，中继就能按模型决定该用哪把分组令牌，而不是把所有用户钉死在 app.new-api.group 上。

ALTER TABLE llm_models
    ADD COLUMN upstream_groups VARCHAR(512) NULL AFTER upstream_available;

CREATE TABLE newapi_groups (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    group_key  VARCHAR(64)   NOT NULL,
    label      VARCHAR(128)  NULL,
    ratio      DECIMAL(18,6) NULL,
    usable     BOOLEAN       NOT NULL DEFAULT TRUE,
    synced_at  DATETIME(6)   NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_newapi_groups_key (group_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;
