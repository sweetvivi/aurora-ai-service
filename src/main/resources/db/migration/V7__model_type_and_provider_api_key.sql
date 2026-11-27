-- Model type, provider attribution, and a plaintext provider key.
--
-- `model_type` separates the endpoints a model can be used from: chat, image, video, audio. It is
-- what lets one catalogue serve the chat picker, the image panel and, later, the media tools.
--
-- `provider_id` moves "which upstream serves this model" out of configuration and into data, so the
-- admin console can point a model at a supplier without a redeploy.
--
-- `llm_providers.api_key` keeps the key as typed by an operator. The V6 design stored only a
-- reference (`credential_ref`) plus a fingerprint, which forces whoever adds a supplier to first put
-- the secret somewhere else and then describe how to find it. The operators asked for the simpler
-- contract: type the key into the console, it is stored, it is used. The reference column stays and
-- is still honoured when it is the only thing present.

ALTER TABLE llm_models
    ADD COLUMN provider_id BIGINT UNSIGNED NULL AFTER site_id,
    ADD COLUMN model_type VARCHAR(32) NOT NULL DEFAULT 'CHAT' AFTER model_name;

ALTER TABLE llm_models
    ADD CONSTRAINT fk_llm_models_provider FOREIGN KEY (provider_id) REFERENCES llm_providers (id);

CREATE INDEX idx_llm_models_type_status ON llm_models (model_type, status, sort_order);

ALTER TABLE llm_providers
    ADD COLUMN api_key TEXT NULL AFTER credential_ref;

-- One supplier row pointing at the gateway the service already talks to. The key is deliberately
-- left empty: the running configuration supplies it today, and a migration file is not a place for
-- a secret. Filling it in from the console takes precedence over the configuration.
INSERT INTO llm_providers
    (provider_key, name, protocol, base_url, endpoint_path, auth_type, credential_ref, status, sort_order)
SELECT 'demo-relay', '示例中转', 'OPENAI_COMPATIBLE', 'https://api.example.com/v1', '/chat/completions',
       'BEARER', 'config:app.llm.api-key', 'ACTIVE', 10
WHERE NOT EXISTS (SELECT 1 FROM llm_providers WHERE provider_key = 'demo-relay');

-- Everything already in the catalogue serves the chat endpoint.
UPDATE llm_models
SET provider_id = (SELECT id FROM llm_providers WHERE provider_key = 'demo-relay')
WHERE provider_id IS NULL;

-- The image endpoint had its model in configuration only. Registering it makes the image key
-- manageable from the same console as the chat key.
INSERT INTO llm_models
    (site_id, provider_id, model_key, model_name, company, route, model_type, status, sort_order,
     is_free, is_thinking, only_points, allow_points, consume_points)
SELECT NULL,
       (SELECT id FROM llm_providers WHERE provider_key = 'demo-relay'),
       'gpt-image-2', 'GPT Image 2', 'OpenAI', 'gpt-image-2', 'IMAGE', 'ACTIVE', 10,
       0, 0, 0, 0, 0
WHERE NOT EXISTS (SELECT 1 FROM llm_models WHERE model_key = 'gpt-image-2');
