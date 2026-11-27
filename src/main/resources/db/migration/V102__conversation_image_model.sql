-- V102: 模型对话可记住本会话偏好的生图模型（CHAT 模型仍在 model_id）。
--
-- 豆包式对话里文本模型与生图模型分离：conversations.model_id 继续只绑 CHAT，
-- image_model_id 可空，表示用户在创作设置里选的 IMAGE 模型；前端默认落到目录里
-- 第一个可用模型，只有目录为空时才留空、由后端兜底。

SET @add_image_model = (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'conversations'
              AND COLUMN_NAME = 'image_model_id'
        ),
        'DO 0',
        'ALTER TABLE conversations ADD COLUMN image_model_id BIGINT UNSIGNED NULL AFTER model_id'
    )
);
PREPARE stmt FROM @add_image_model;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
