-- V11: persist the customAgent page's ui_type discriminator.
--
-- V9 already stores the low-frequency UI payload (forms / tip_struct / exampleUrls) in
-- agents.ui_config_json. ui_type is promoted to its own column because it selects the renderer
-- and is expected to participate in catalog filtering without parsing JSON.
--
-- Existing rows and imported prompt-library agents use the standard chat renderer.

ALTER TABLE agents
    ADD COLUMN ui_type VARCHAR(32) NOT NULL DEFAULT 'CHAT' AFTER agent_type;
