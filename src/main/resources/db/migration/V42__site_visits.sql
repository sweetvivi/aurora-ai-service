-- Page-view events used by the admin dashboard. Each SPA navigation is recorded once by the
-- front end; rows are intentionally lightweight and contain no client address or account id.

CREATE TABLE IF NOT EXISTS site_visits (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    site_id BIGINT UNSIGNED NOT NULL,
    path VARCHAR(512) NOT NULL,
    visited_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_site_visits_site_visited (site_id, visited_at),
    CONSTRAINT fk_site_visits_site FOREIGN KEY (site_id) REFERENCES app_sites (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
