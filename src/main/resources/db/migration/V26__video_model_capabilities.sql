-- V26: per-model video capabilities.
--
-- A video model is not one fixed form. The same composer switches between "text to video", "image
-- to video" and "first + last frame" depending on the model, and each mode accepts different
-- parameters. Modelling that as a free-form JSON document on the catalogue row keeps the form
-- data-driven: the console edits it, the API returns it, and the client renders the controls the
-- model actually supports instead of guessing.
--
-- Shape:
--   {
--     "modes": ["text2video", "image2video", "first-last-frame"],
--     "aspectRatios": ["16:9", "9:16", "1:1"],
--     "resolutions": ["480p", "720p", "1080p"],
--     "durations": [3, 4, 5, 8, 10],
--     "maxReferenceImages": 2,
--     "audio": true
--   }
--
-- An empty value means "no declaration": the client falls back to its own defaults, so models
-- registered before this migration keep working unchanged.

ALTER TABLE llm_models
    ADD COLUMN capabilities_json JSON NULL AFTER description;

UPDATE llm_models
SET capabilities_json = JSON_OBJECT(
        'modes', JSON_ARRAY('text2video', 'image2video', 'first-last-frame'),
        'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1'),
        'resolutions', JSON_ARRAY('480p', '720p', '1080p'),
        'durations', JSON_ARRAY(3, 4, 5, 8, 10),
        'maxReferenceImages', 2,
        'audio', FALSE)
WHERE model_key = 'seedance-2.0';

UPDATE llm_models
SET capabilities_json = JSON_OBJECT(
        'modes', JSON_ARRAY('text2video', 'image2video'),
        'aspectRatios', JSON_ARRAY('16:9', '9:16'),
        'resolutions', JSON_ARRAY('720p', '1080p'),
        'durations', JSON_ARRAY(5, 10),
        'maxReferenceImages', 1,
        'audio', FALSE)
WHERE model_key = 'kling-2.5';

UPDATE llm_models
SET capabilities_json = JSON_OBJECT(
        'modes', JSON_ARRAY('text2video', 'image2video', 'first-last-frame'),
        'aspectRatios', JSON_ARRAY('16:9', '9:16', '1:1'),
        'resolutions', JSON_ARRAY('480p', '720p'),
        'durations', JSON_ARRAY(3, 5, 8),
        'maxReferenceImages', 2,
        'audio', TRUE)
WHERE model_key = 'wan-2.5';

UPDATE llm_models
SET capabilities_json = JSON_OBJECT(
        'modes', JSON_ARRAY('text2video'),
        'aspectRatios', JSON_ARRAY('16:9', '9:16'),
        'resolutions', JSON_ARRAY('720p'),
        'durations', JSON_ARRAY(6),
        'maxReferenceImages', 0,
        'audio', FALSE)
WHERE model_key = 'hailuo-02';
