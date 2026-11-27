-- Site-wide notifications published from the admin console. A null site_id targets every site;
-- read state is kept per account so one user dismissing a message does not hide it for others.

CREATE TABLE IF NOT EXISTS site_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_site_notifications_feed (status, published_at, id),
    KEY idx_site_notifications_site (site_id, status, published_at),
    CONSTRAINT fk_site_notifications_site
        FOREIGN KEY (site_id) REFERENCES app_sites (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_notification_reads (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    notification_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    read_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_notification_reads_notification_user (notification_id, user_id),
    KEY idx_user_notification_reads_user (user_id, read_at),
    CONSTRAINT fk_user_notification_reads_notification
        FOREIGN KEY (notification_id) REFERENCES site_notifications (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_notification_reads_user
        FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
