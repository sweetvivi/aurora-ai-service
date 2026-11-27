-- ppt-master stores full project filesystem paths in presenton_presentation_id
-- (e.g. C:\...\ppt-master\projects\ppt_job_..._ppt169_...), which exceeds VARCHAR(128).
ALTER TABLE ppt_jobs
    MODIFY COLUMN presenton_presentation_id VARCHAR(1024) NULL;
