ALTER TABLE ppt_jobs
    ADD COLUMN image_model_id VARCHAR(128) NULL AFTER text_model_id,
    ADD COLUMN aspect_ratio VARCHAR(16) NOT NULL DEFAULT '16:9' AFTER template_id;
