-- High-priority notifications are surfaced as a mandatory unread dialog in the front end.
-- Keep this separate from V43 so databases that already applied that migration stay valid.

ALTER TABLE site_notifications
    ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL' AFTER content;
