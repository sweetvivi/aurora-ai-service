-- aippt 任务新增预览图 storage key 列表。
-- 之前只存了面向前端的 URL（/api/v1/aippt/jobs/{id}/previews/{index}），
-- 读取端无法从 URL 恢复真实文件 key，导致结果预览全部 404（碎图）。
ALTER TABLE aippt_jobs
    ADD COLUMN preview_keys_json LONGTEXT NULL AFTER preview_urls_json;
