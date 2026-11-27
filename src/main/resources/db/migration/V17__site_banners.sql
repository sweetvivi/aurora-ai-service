-- Top-of-page activity banners, optionally scoped to one app site.

CREATE TABLE IF NOT EXISTS site_banners (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NULL,
    name VARCHAR(80) NOT NULL,
    title VARCHAR(80) NOT NULL,
    content VARCHAR(240) NOT NULL,
    highlight_text VARCHAR(80) NULL,
    button_text VARCHAR(20) NULL,
    link_url VARCHAR(1000) NULL,
    image_url VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    start_at DATETIME(6) NULL,
    end_at DATETIME(6) NULL,
    dismissible TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_banner_public (status, site_id, start_at, end_at, sort_order, id),
    CONSTRAINT fk_banner_site
        FOREIGN KEY (site_id) REFERENCES app_sites (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
