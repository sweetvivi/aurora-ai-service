-- Agent / template generate modes + Deck catalog (align go-admin AIPPT)

-- Some deployments received these columns through a pre-Flyway schema sync. Add each column
-- conditionally so adopting Flyway does not require rewriting migration history.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ppt_jobs' AND column_name = 'generate_mode') = 0,
    'ALTER TABLE ppt_jobs ADD COLUMN generate_mode VARCHAR(32) NOT NULL DEFAULT ''agent'' AFTER template_id',
    'SELECT 1'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ppt_jobs' AND column_name = 'deck_template_id') = 0,
    'ALTER TABLE ppt_jobs ADD COLUMN deck_template_id VARCHAR(64) NULL AFTER generate_mode',
    'SELECT 1'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ppt_jobs' AND column_name = 'stage') = 0,
    'ALTER TABLE ppt_jobs ADD COLUMN stage VARCHAR(64) NULL AFTER message',
    'SELECT 1'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ppt_jobs' AND column_name = 'progress_msg') = 0,
    'ALTER TABLE ppt_jobs ADD COLUMN progress_msg VARCHAR(1024) NULL AFTER stage',
    'SELECT 1'
);
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE IF NOT EXISTS ppt_deck_templates (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    category        VARCHAR(64)  NOT NULL DEFAULT '商务',
    tags_json       TEXT         NULL,
    style_blurb     VARCHAR(512) NOT NULL DEFAULT '',
    cover_url       VARCHAR(512) NOT NULL DEFAULT '',
    aspect_ratio    VARCHAR(16)  NOT NULL DEFAULT '16:9',
    page_min        INT          NOT NULL DEFAULT 8,
    page_max        INT          NOT NULL DEFAULT 20,
    page_default    INT          NOT NULL DEFAULT 12,
    renderer        VARCHAR(32)  NOT NULL DEFAULT 'svg_workspace',
    theme_template_id VARCHAR(64) NOT NULL DEFAULT 'blue-business-text',
    spec_json       LONGTEXT     NULL,
    source_pptx_key VARCHAR(512) NOT NULL DEFAULT '',
    license_source  VARCHAR(256) NOT NULL DEFAULT '',
    commercial_ok   TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order      INT          NOT NULL DEFAULT 0,
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=draft 1=published 2=archived',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_ppt_deck_code (code),
    KEY idx_ppt_deck_status_sort (status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed: blue-white business text deck (workspace lives under ppt-master decks/)
INSERT INTO ppt_deck_templates (
    code, name, category, tags_json, style_blurb, cover_url, aspect_ratio,
    page_min, page_max, page_default, renderer, theme_template_id, spec_json,
    source_pptx_key, license_source, commercial_ok, sort_order, status
) VALUES (
    'tpl_blue_white_text_biz_01',
    '蓝白商务 · 纯文字拍板集锦',
    '商务',
    '["蓝白","纯文字","KPI","图示"]',
    '经典蓝白商务风，偏纯文字与图示排版，少实景图，适合汇报拍板、方案陈述。',
    '/ppt-deck-assets/tpl_blue_white_text_biz_01/templates/01_cover.svg',
    '16:9',
    8, 20, 12,
    'svg_workspace',
    'blue-business-text',
    '{"id":"tpl_blue_white_text_biz_01","catalog":{"name":"蓝白商务 · 纯文字拍板集锦","category":"商务"},"notes":"Deck workspace 已建设；模板任务强制 apply-template-workspace。"}',
    '',
    '运营采购·蓝白商务纯文字集锦',
    1,
    10,
    1
) ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    style_blurb = VALUES(style_blurb),
    cover_url = VALUES(cover_url),
    status = VALUES(status),
    sort_order = VALUES(sort_order);
