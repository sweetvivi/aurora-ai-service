-- Smart-editing board records and public examples.

CREATE TABLE IF NOT EXISTS smart_edit_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NULL,
    title VARCHAR(255) NOT NULL,
    canvas_json LONGTEXT NOT NULL,
    messages_json LONGTEXT NOT NULL,
    cover VARCHAR(1024) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_smart_edit_records_user_status_updated (user_id, status, updated_at),
    KEY idx_smart_edit_records_status_created (status, created_at),
    CONSTRAINT fk_smart_edit_records_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO smart_edit_records
    (user_id, title, canvas_json, messages_json, cover, status)
SELECT NULL,
       '商品图精修示例',
       JSON_OBJECT(
           'images', JSON_ARRAY(),
           'scale', 1,
           'stagePos', JSON_OBJECT('x', 0, 'y', 0)
       ),
       JSON_ARRAY(
           JSON_OBJECT(
               'id', 'demo-user-1',
               'role', 'user',
               'content', '保留商品主体，背景换成干净的纯色背景。',
               'timestamp', 1730000000000
           )
       ),
       '',
       'DEMO'
WHERE NOT EXISTS (
    SELECT 1 FROM smart_edit_records
    WHERE status = 'DEMO' AND title = '商品图精修示例'
);

INSERT INTO smart_edit_records
    (user_id, title, canvas_json, messages_json, cover, status)
SELECT NULL,
       '人像抠图示例',
       JSON_OBJECT(
           'images', JSON_ARRAY(),
           'scale', 1,
           'stagePos', JSON_OBJECT('x', 0, 'y', 0)
       ),
       JSON_ARRAY(
           JSON_OBJECT(
               'id', 'demo-user-2',
               'role', 'user',
               'content', '去除背景并保留头发边缘细节。',
               'timestamp', 1730000000000
           )
       ),
       '',
       'DEMO'
WHERE NOT EXISTS (
    SELECT 1 FROM smart_edit_records
    WHERE status = 'DEMO' AND title = '人像抠图示例'
);
