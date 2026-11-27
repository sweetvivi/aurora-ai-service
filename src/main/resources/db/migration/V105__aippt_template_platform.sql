-- V105: 新版 AI PPT「模板驱动式生成平台」数据表。
--
-- 旧 ppt_jobs / ppt_templates / ppt_deck_templates 已由 V104 移除；本迁移重建
-- 全新命名的三张表（aippt_ 前缀），结构与 docs/ai-ppt-platform-plan.md §5 一致。
-- aippt_templates.spec_json 允许为空：官方模板首次访问详情时走「引擎 parse + spec-draft」
-- 懒加载回填，与用户上传模板共用同一条链路。

CREATE TABLE IF NOT EXISTS aippt_templates (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL DEFAULT '商务',
    description VARCHAR(1024) NOT NULL DEFAULT '',
    cover_url VARCHAR(512) NOT NULL DEFAULT '',
    aspect_ratio VARCHAR(16) NOT NULL DEFAULT '16:9',
    page_count INT NOT NULL DEFAULT 0,
    theme_color VARCHAR(16) NOT NULL DEFAULT '#2563eb',
    dark_mode TINYINT(1) NOT NULL DEFAULT 0,
    source_type VARCHAR(16) NOT NULL DEFAULT 'official',
    owner_user_id BIGINT UNSIGNED NULL,
    status TINYINT NOT NULL DEFAULT 1,
    storage_key VARCHAR(768) NOT NULL DEFAULT '',
    spec_json LONGTEXT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_aippt_tpl_code (code),
    KEY idx_aippt_tpl_status_sort (status, sort_order),
    KEY idx_aippt_tpl_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_template_pages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    template_id BIGINT UNSIGNED NOT NULL,
    page_index INT NOT NULL,
    page_role VARCHAR(32) NOT NULL,
    preview_key VARCHAR(768) NOT NULL DEFAULT '',
    slots_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_aippt_tpl_page (template_id, page_index),
    CONSTRAINT fk_aippt_tpl_page_template
        FOREIGN KEY (template_id) REFERENCES aippt_templates (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS aippt_jobs (
    job_id VARCHAR(64) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(512) NOT NULL,
    template_id BIGINT UNSIGNED NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    stage VARCHAR(64) NULL,
    progress INT NOT NULL DEFAULT 0,
    message VARCHAR(1024) NULL,
    topic LONGTEXT NULL,
    page_count INT NULL,
    points_cost INT NOT NULL DEFAULT 0,
    pages_json LONGTEXT NULL,
    storage_key VARCHAR(768) NULL,
    file_name VARCHAR(512) NULL,
    preview_urls_json LONGTEXT NULL,
    error_message VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (job_id),
    KEY idx_aippt_jobs_user_created (user_id, created_at),
    KEY idx_aippt_jobs_user_status (user_id, status),
    CONSTRAINT fk_aippt_jobs_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 官方模板种子：storage_key 由 AipptTemplateSeeder 启动时从
-- resources/aippt/official/{code}.pptx 幂等导入 FileStorage 后回填；
-- cover_url 复用前端静态 SVG 封面（web-app/public/ppt-templates/ppt-master/）。
INSERT INTO aippt_templates
    (code, name, category, description, cover_url, aspect_ratio, page_count,
     theme_color, dark_mode, source_type, status, sort_order)
VALUES
    ('business-blue', '商务蓝', '商务汇报',
     '蓝色强调与清晰信息层级，适合商务汇报、工作总结与商业计划。',
     '/ppt-templates/ppt-master/business-clean.svg', '16:9', 6, '#2563eb', 0, 'official', 1, 10),
    ('minimal-gray', '简约灰', '通用',
     '低饱和灰白配色与轻量卡片结构，适合通用汇报与演讲报告。',
     '/ppt-templates/ppt-master/fresh-modern.svg', '16:9', 6, '#475569', 0, 'official', 1, 9),
    ('teach-fresh', '清新教学', '教育课件',
     '清爽绿色调与柔和卡片，适合教学课件、培训与课程分享。',
     '/ppt-templates/ppt-master/green-fresh-courseware.svg', '16:9', 6, '#10b981', 0, 'official', 1, 8),
    ('dark-tech', '深色科技', '科技',
     '深色底、青色强调与高对比信息结构，适合科技发布与产品路演。',
     '/ppt-templates/ppt-master/dark-tech.svg', '16:9', 6, '#06b6d4', 1, 'official', 1, 7),
    ('green-fresh-courseware', '绿意课件', '教育课件',
     '浅绿背景与温和层次，适合课程介绍与学习总结。',
     '/ppt-templates/ppt-master/green-fresh-courseware.svg', '16:9', 6, '#22c55e', 0, 'official', 1, 6)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    category = VALUES(category),
    description = VALUES(description),
    cover_url = VALUES(cover_url),
    theme_color = VALUES(theme_color),
    dark_mode = VALUES(dark_mode),
    sort_order = VALUES(sort_order);
