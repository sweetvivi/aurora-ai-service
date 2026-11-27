ALTER TABLE ppt_jobs
    ADD COLUMN img_density VARCHAR(16) NOT NULL DEFAULT 'mid' AFTER auto_img;
