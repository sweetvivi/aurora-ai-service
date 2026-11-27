-- New API is the source of truth for providers and models.
--
-- Local rows remain as a cache so historical conversations and messages keep their foreign keys.
-- Upstream removals only flip `upstream_available`; they never delete a local catalogue row.

ALTER TABLE llm_providers
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'LOCAL' AFTER id,
    ADD COLUMN external_id VARCHAR(128) NULL AFTER source,
    ADD COLUMN upstream_available BOOLEAN NOT NULL DEFAULT TRUE AFTER status,
    ADD COLUMN provider_group VARCHAR(255) NULL AFTER upstream_available,
    ADD COLUMN priority BIGINT NOT NULL DEFAULT 0 AFTER provider_group,
    ADD COLUMN weight INT NOT NULL DEFAULT 0 AFTER priority;

CREATE UNIQUE INDEX uk_llm_providers_source_external
    ON llm_providers (source, external_id);

CREATE INDEX idx_llm_providers_catalog
    ON llm_providers (source, upstream_available, priority, weight);

ALTER TABLE llm_models
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'LOCAL' AFTER id,
    ADD COLUMN external_id VARCHAR(128) NULL AFTER source,
    ADD COLUMN upstream_available BOOLEAN NOT NULL DEFAULT TRUE AFTER status;

CREATE UNIQUE INDEX uk_llm_models_source_external
    ON llm_models (source, external_id);

CREATE INDEX idx_llm_models_catalog_availability
    ON llm_models (status, upstream_available, sort_order);
