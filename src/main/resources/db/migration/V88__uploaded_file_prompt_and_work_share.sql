-- 作品跨模块共用（V88）
--
-- 背景：`uploaded_files.source` 从 V77 起把「图片生成 / 绘画 / 智能修图 / 画布」各自的作品
-- 圈在自己的页面里，作品列表按 source 过滤。产品上改成「一处生成、处处可见、任一处删除
-- 即真删」之后，source 只保留成「谁产出的」这一条追溯信息，不再作为可见性边界。
--
-- 1) 附件记下出图用的提示词：作品卡片跨模块展示时需要它，附件表此前没有这一列，
--    各页面只能靠本地缓存或会话标题凑，换页面就退化成文件名。
-- 2) 画布素材库补成「全部作品」：素材库里的生成图一直是引用行（source_file_id 指向
--    附件，字节只有一份），但此前只在出图后由前端补建，存量作品和漏同步的都没有。
--    这里一次性补齐，之后仍由出图链路继续补。

ALTER TABLE uploaded_files
    ADD COLUMN prompt VARCHAR(1000) NULL AFTER asset_type;

INSERT INTO ai_canvas_assets (
    asset_key, user_id, storage_key, source_file_id, name, category,
    asset_type, mime_type, extension, size_bytes, prompt, model,
    status, created_at, updated_at)
SELECT CONCAT('asset-file-', f.id),
       f.user_id,
       NULL,
       f.id,
       CONCAT(LEFT(COALESCE(NULLIF(f.prompt, ''), f.original_name), 40), ' ',
              DATE_FORMAT(f.created_at, '%Y%m%d-%H%i%s')),
       'other',
       'image',
       f.mime_type,
       f.extension,
       f.size_bytes,
       f.prompt,
       NULL,
       'ACTIVE',
       f.created_at,
       f.created_at
FROM uploaded_files f
WHERE f.status = 'UPLOADED'
  AND f.asset_type IN ('generated', 'edit_result')
  AND f.mime_type LIKE 'image/%'
  AND NOT EXISTS (
      SELECT 1 FROM ai_canvas_assets a
      WHERE a.source_file_id = f.id AND a.status = 'ACTIVE');

-- 3) 「素材移除」与「删除作品」分开记
--
-- 图片生成 / AI 绘画 的删除是真删附件（四个页面同时消失）；画布的素材删除只删素材行；
-- 智能修图的「移除」既不能删附件（图可能还要在别处用），也不能只靠本地存储（换设备就回来），
-- 所以按用户 + 来源记一条隐藏记录，列表查询时排除。
CREATE TABLE IF NOT EXISTS hidden_works (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    scope VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_hidden_works_user_file_scope (user_id, file_id, scope),
    KEY idx_hidden_works_user_scope (user_id, scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
