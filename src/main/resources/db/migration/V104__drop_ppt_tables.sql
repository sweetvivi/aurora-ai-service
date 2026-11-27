-- V104: 移除旧 PPT 生成功能的数据表，为新的「模板驱动式」方案让位。
--
-- 历史迁移 V36/V37/V38/V39/V40/V54 必须保留：Flyway 会按 checksum 校验已应用的迁移，
-- 删掉脚本文件会导致启动期报 "applied migration not resolved locally"，后端起不来。
-- 因此这里用一条新的前向迁移把表清掉，而不是回头改历史。
--
-- 三张表互不引用（只有 ppt_jobs.user_id 指向 user_accounts），DROP 无顺序依赖。
-- IF EXISTS 保证在「从未建过 PPT 表」的空库上也能安全执行。

DROP TABLE IF EXISTS ppt_jobs;
DROP TABLE IF EXISTS ppt_templates;
DROP TABLE IF EXISTS ppt_deck_templates;
