-- Grok（xAI）图生图 / 视频，走Aurora网关。
--
-- 供应商的 api_key 直接落在库里，ProviderCredentialResolver 优先读它，
-- 因此不依赖 LLM_VIDEO_API_KEY 之类的外置环境变量。
-- images_path 用于同步图像接口；metadata_json.videosPath 用于异步视频接口。
--
-- 可重复执行：先删同名模型再插入，避开 (site_id, model_key) 在 NULL site 下
-- 不触发唯一键的问题。

INSERT INTO llm_providers
    (source, external_id, provider_key, name, category, protocol, base_url, endpoint_path,
     images_path, api_version, auth_type, credential_ref, api_key, status, sort_order,
     metadata_json)
VALUES
    ('LOCAL', NULL, 'xai-grok', 'Grok（xAI）', 'VIDEO', 'OPENAI_COMPATIBLE',
     'https://api.example.com/v1', NULL, '/images/generations', NULL, 'BEARER', '',
     '<put-the-xAI-key-here>',
     'ACTIVE', 20, '{"videosPath":"/videos/generations"}')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    category = VALUES(category),
    protocol = VALUES(protocol),
    base_url = VALUES(base_url),
    images_path = VALUES(images_path),
    api_key = VALUES(api_key),
    status = VALUES(status),
    metadata_json = VALUES(metadata_json);

SET @grok = (SELECT id FROM llm_providers WHERE provider_key = 'xai-grok');

DELETE FROM llm_models WHERE site_id IS NULL AND model_key IN (
    'grok-imagine-image', 'grok-imagine-image-2.0',
    'grok-imagine-video', 'grok-imagine-video-1.5');

INSERT INTO llm_models
    (source, external_id, site_id, provider_id, group_id, parent_model_id,
     model_key, model_name, model_type, company, icon_url, description,
     capabilities_json, status, upstream_available, sort_order)
VALUES
    ('LOCAL', NULL, NULL, @grok, NULL, NULL,
     'grok-imagine-image', 'Grok Imagine Image', 'IMAGE', 'xAI', NULL,
     'xAI 图像生成模型，文字描述直接出图。',
     NULL, 'ACTIVE', TRUE, 70),
    ('LOCAL', NULL, NULL, @grok, NULL, NULL,
     'grok-imagine-image-2.0', 'Grok Imagine Image 2.0', 'IMAGE', 'xAI', NULL,
     'xAI 新一代图像模型，细节与文字渲染更好。',
     NULL, 'ACTIVE', TRUE, 80),
    ('LOCAL', NULL, NULL, @grok, NULL, NULL,
     'grok-imagine-video', 'Grok Imagine Video', 'VIDEO', 'xAI', NULL,
     'xAI 视频模型，支持文生与图生，最高 720p。',
     JSON_OBJECT(
         'modes', JSON_ARRAY('text2video', 'image2video'),
         'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1', '4:3', '3:4'),
         'resolutions', JSON_ARRAY('480p', '720p'),
         'durations', JSON_ARRAY(5, 6, 8, 10, 15),
         'maxReferenceImages', 1,
         'audio', TRUE),
     'ACTIVE', TRUE, 50),
    ('LOCAL', NULL, NULL, @grok, NULL, NULL,
     'grok-imagine-video-1.5', 'Grok Imagine Video 1.5', 'VIDEO', 'xAI', NULL,
     'xAI 新一代视频模型，支持文生、图生与首尾帧，最高 1080p。',
     JSON_OBJECT(
         'modes', JSON_ARRAY('text2video', 'image2video', 'first-last-frame'),
         'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1', '4:3', '3:4'),
         'resolutions', JSON_ARRAY('480p', '720p', '1080p'),
         'durations', JSON_ARRAY(5, 6, 8, 10, 15),
         'maxReferenceImages', 2,
         'audio', TRUE),
     'ACTIVE', TRUE, 60);

SELECT m.id, m.model_key, m.model_type, m.status, p.provider_key,
       CASE WHEN p.api_key IS NULL OR p.api_key = '' THEN 'NO KEY'
            ELSE CONCAT('KEY(', CHAR_LENGTH(p.api_key), ')') END AS provider_key_state
FROM llm_models m
JOIN llm_providers p ON p.id = m.provider_id
WHERE p.provider_key = 'xai-grok'
ORDER BY m.model_type, m.sort_order;
