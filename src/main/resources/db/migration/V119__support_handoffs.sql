-- 客服转人工独立工单表；历史误入意见反馈的记录迁入后删除。

CREATE TABLE IF NOT EXISTS support_handoffs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT NULL,
    user_id BIGINT NULL,
    content VARCHAR(2000) NOT NULL,
    contact VARCHAR(120) NULL,
    page_url VARCHAR(512) NULL,
    agent_conversation_id BIGINT NULL,
    transcript_snapshot MEDIUMTEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    handled_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_support_handoffs_status_created (status, created_at DESC),
    KEY idx_support_handoffs_site_created (site_id, created_at DESC),
    KEY idx_support_handoffs_user (user_id),
    KEY idx_support_handoffs_conversation (agent_conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO support_handoffs (
    site_id, user_id, content, contact, page_url,
    agent_conversation_id, transcript_snapshot, status, created_at, handled_at
)
SELECT f.site_id,
       f.user_id,
       f.content,
       f.contact,
       f.page_url,
       f.agent_conversation_id,
       f.transcript_snapshot,
       CASE WHEN UPPER(f.status) = 'HANDLED' THEN 'HANDLED' ELSE 'NEW' END,
       f.created_at,
       CASE WHEN UPPER(f.status) = 'HANDLED' THEN f.created_at ELSE NULL END
FROM user_feedback f
WHERE f.source = 'SUPPORT_WIDGET'
   OR UPPER(f.category) = 'SUPPORT';

DELETE FROM user_feedback
WHERE source = 'SUPPORT_WIDGET'
   OR UPPER(category) = 'SUPPORT';
