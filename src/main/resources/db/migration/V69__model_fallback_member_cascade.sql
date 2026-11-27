-- New API catalogue sync may retire and remove a model row. Its fallback membership is a local
-- relationship, so removing the model must remove that relationship instead of blocking sync.
ALTER TABLE llm_model_fallback_members
    DROP FOREIGN KEY fk_llm_model_fallback_members_model;

ALTER TABLE llm_model_fallback_members
    ADD CONSTRAINT fk_llm_model_fallback_members_model
        FOREIGN KEY (model_id) REFERENCES llm_models (id) ON DELETE CASCADE;
