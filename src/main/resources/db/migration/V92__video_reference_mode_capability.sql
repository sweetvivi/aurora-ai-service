-- V92: 视频「参考图模式」的模型能力声明。
--
-- 画布视频节点连两张图时，默认按首尾帧理解。参考图模式（两张图都当参考图，不锁首尾）
-- 需要模型声明 reference2video，前端才会放出切换入口、后端才放行请求。
--
-- Grok Imagine Video 1.5 走 xAI 的 reference_to_video（reference_images 数组），是当前
-- 目录里唯一确定支持多图参考的视频模型，这里补上声明。其余模型保持原样：没有声明
-- reference2video 的模型不会出现该选项，也不会被放行，避免把参考图静默当成首帧。
--
-- 幂等：模型不存在、没有能力声明、或已经声明过时都不做改动。

UPDATE llm_models
SET capabilities_json = JSON_ARRAY_APPEND(capabilities_json, '$.modes', 'reference2video')
WHERE model_key = 'grok-imagine-video-1.5'
  AND capabilities_json IS NOT NULL
  AND JSON_CONTAINS_PATH(capabilities_json, 'one', '$.modes')
  AND NOT JSON_CONTAINS(capabilities_json -> '$.modes', '"reference2video"');
