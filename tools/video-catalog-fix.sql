-- Grok Imagine via the demo-relay gateway.
--
-- The provider is registered with its own key so the console can rotate it later; the models are
-- attached to it, which is what makes ModelUseResolver hand their credentials to the clients.
--
-- Video models use the OpenAI-compatible asynchronous shape this project already implements:
--   POST /v1/videos/generations  -> task id
--   GET  /v1/videos/generations/{id} -> status + result url
-- Image models use the synchronous /v1/images/generations endpoint.

INSERT INTO llm_providers
    (provider_key, name, category, protocol, base_url, endpoint_path, images_path,
     api_version, auth_type, credential_ref, api_key, status, sort_order, metadata_json)
VALUES
    ('xai-grok', 'Grok（xAI）', 'VIDEO', 'OPENAI_COMPATIBLE', 'https://api.example.com/v1',
     NULL, '/images/generations', NULL, 'BEARER', 'env:XAI_API_KEY',
     '<put-LLM_VIDEO_API_KEY-here>',
     'ACTIVE', 20, '{"videosPath":"/videos/generations"}')
ON DUPLICATE KEY UPDATE
    name = VALUES(name), category = VALUES(category), protocol = VALUES(protocol),
    base_url = VALUES(base_url), images_path = VALUES(images_path),
    credential_ref = VALUES(credential_ref), api_key = VALUES(api_key),
    status = VALUES(status), metadata_json = VALUES(metadata_json);

INSERT INTO llm_models
    (site_id, provider_id, group_id, parent_model_id, model_key, model_name, model_type, company,
     icon_url, description, capabilities_json, status, sort_order)
VALUES
    (NULL, (SELECT id FROM llm_providers WHERE provider_key = 'xai-grok'), NULL, NULL,
     'grok-imagine-video', 'Grok Imagine Video', 'VIDEO', 'xAI', NULL,
     'xAI 视频模型，支持文生与图生，最高 720p。',
     JSON_OBJECT(
         'modes', JSON_ARRAY('text2video', 'image2video'),
         'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1', '4:3', '3:4'),
         'resolutions', JSON_ARRAY('480p', '720p'),
         'durations', JSON_ARRAY(5, 6, 8, 10, 15),
         'maxReferenceImages', 1,
         'audio', TRUE),
     'ACTIVE', 50),
    (NULL, (SELECT id FROM llm_providers WHERE provider_key = 'xai-grok'), NULL, NULL,
     'grok-imagine-video-1.5', 'Grok Imagine Video 1.5', 'VIDEO', 'xAI', NULL,
     'xAI 新一代视频模型，支持文生、图生与首尾帧，最高 1080p。',
     JSON_OBJECT(
         'modes', JSON_ARRAY('text2video', 'image2video', 'first-last-frame'),
         'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1', '4:3', '3:4'),
         'resolutions', JSON_ARRAY('480p', '720p', '1080p'),
         'durations', JSON_ARRAY(5, 6, 8, 10, 15),
         'maxReferenceImages', 2,
         'audio', TRUE),
     'ACTIVE', 60),
    (NULL, (SELECT id FROM llm_providers WHERE provider_key = 'xai-grok'), NULL, NULL,
     'grok-imagine-image', 'Grok Imagine Image', 'IMAGE', 'xAI', NULL,
     'xAI 图像生成模型。',
     NULL, 'ACTIVE', 70),
    (NULL, (SELECT id FROM llm_providers WHERE provider_key = 'xai-grok'), NULL, NULL,
     'grok-imagine-image-2.0', 'Grok Imagine Image 2.0', 'IMAGE', 'xAI', NULL,
     'xAI 新一代图像生成模型。',
     NULL, 'ACTIVE', 80)
ON DUPLICATE KEY UPDATE
    provider_id = VALUES(provider_id), model_name = VALUES(model_name),
    model_type = VALUES(model_type), company = VALUES(company),
    description = VALUES(description), capabilities_json = VALUES(capabilities_json),
    status = VALUES(status), sort_order = VALUES(sort_order);
