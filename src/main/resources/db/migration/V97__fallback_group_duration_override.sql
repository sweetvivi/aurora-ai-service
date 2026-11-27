-- V97: 版本组可以覆盖成员的能力声明。
--
-- 一个版本组的成员可以互相顶替，但实际能力并不一致：seedance-2.0 组里多数成员收 4–15 秒的
-- 任意整数，而 fast-GT / fast-OvO-Pack 只做 10 或 15 秒。目录原本只发布「代表成员」（价格最低
-- 那个）的声明，前台就给出 7 秒，请求落到 fast-GT 会被上游 400；改成取组内交集又太保守 ——
-- 只要组里还有一个只认 10/15 的成员，整个组就只能选 10/15。
--
-- 现在把决定权交回运营：这两个字段非空时，目录按它对外声明，不再按成员计算。
--   duration_mode：range（durations 是闭区间端点）/ set（durations 就是全部档位）
--   durations_json：JSON 整数数组，例如 [4,15] 或 [10,15]
-- 留空表示「按成员自动取交集」，与升级前行为一致。
--
-- 幂等：列已存在时不重复添加。

SET @add_mode = (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'llm_model_fallback_groups'
              AND COLUMN_NAME = 'duration_mode'
        ),
        'DO 0',
        'ALTER TABLE llm_model_fallback_groups ADD COLUMN duration_mode VARCHAR(16) NULL AFTER order_customized'
    )
);
PREPARE stmt FROM @add_mode;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_durations = (
    SELECT IF(
        EXISTS(
            SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'llm_model_fallback_groups'
              AND COLUMN_NAME = 'durations_json'
        ),
        'DO 0',
        'ALTER TABLE llm_model_fallback_groups ADD COLUMN durations_json TEXT NULL AFTER duration_mode'
    )
);
PREPARE stmt FROM @add_durations;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
