-- V93: 把已接入的 Grok Imagine Video 1.5 摆上画布的视频模型列表。
--
-- 画布的视频模型不是「渠道里有什么就显示什么」：它读 GET /api/v1/models?type=VIDEO，
-- 而该接口只返回**版本组**（llm_model_fallback_groups）里还有有效成员的模型。
-- 模型本身存在、状态 ACTIVE、upstream_available 为真，但只要没进版本组，就永远不会出现，
-- 客户端会退回到自己写死的默认模型列表（那些模型上游并不存在，提交必然失败）。
--
-- 这里补三件事：
--   1. 能力声明：没有 capabilities_json 时客户端会把所有模式都当支持，参考图模式也不会出现。
--      Grok Imagine Video 1.5 走 xAI 的 reference_to_video，官方上限是**单次 14 张参考图**
--      （老款 grok-imagine-video 才是 7 张），这里按 14 声明，客户端据此决定最多能连几张。
--      注意 xAI 的参考图模式只出 720p，选 1080p 会被上游拒。
--   2. 版本组：VIDEO 类型的分组行。
--   3. 成员：把模型挂进该分组。
--
-- 幂等：模型不存在、能力已声明、分组/成员已存在时都不做改动；管理员改过的能力描述不会被覆盖。

UPDATE llm_models
SET model_name = 'Grok Imagine Video 1.5',
    description = COALESCE(description, 'xAI 视频模型，支持文生、图生、首尾帧与多图参考，最高 1080p。')
WHERE model_key = 'grok-imagine-video-1.5'
  AND model_type = 'VIDEO'
  AND model_name = model_key;

UPDATE llm_models
SET capabilities_json = JSON_OBJECT(
        'modes', JSON_ARRAY('text2video', 'image2video', 'first-last-frame', 'reference2video'),
        'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1', '4:3', '3:4'),
        'resolutions', JSON_ARRAY('480p', '720p', '1080p'),
        'durations', JSON_ARRAY(5, 6, 8, 10, 15),
        'maxReferenceImages', 14,
        'audio', TRUE)
WHERE model_key = 'grok-imagine-video-1.5'
  AND model_type = 'VIDEO'
  AND capabilities_json IS NULL;

INSERT INTO llm_model_fallback_groups
    (site_id, group_key, display_name, model_type, status, sort_order, order_customized)
SELECT NULL, 'grok-imagine-video-1.5', 'Grok Imagine Video 1.5', 'VIDEO', 'ACTIVE', 10, FALSE
WHERE NOT EXISTS (
    SELECT 1
    FROM llm_model_fallback_groups
    WHERE site_id IS NULL AND group_key = 'grok-imagine-video-1.5'
);

INSERT INTO llm_model_fallback_members (group_id, model_id, priority, enabled)
SELECT grouped.id, model.id, 0, TRUE
FROM llm_model_fallback_groups grouped
JOIN llm_models model
    ON model.model_key = 'grok-imagine-video-1.5'
   AND model.model_type = 'VIDEO'
   AND model.status = 'ACTIVE'
   AND model.upstream_available = TRUE
WHERE grouped.site_id IS NULL
  AND grouped.group_key = 'grok-imagine-video-1.5'
  AND NOT EXISTS (
      SELECT 1
      FROM llm_model_fallback_members member
      WHERE member.group_id = grouped.id AND member.model_id = model.id
  );
