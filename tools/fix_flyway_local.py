"""Align local MySQL schema with current V104-V108 migrations and repair Flyway history."""
from __future__ import annotations

import zlib
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[1]
MIG = ROOT / "src" / "main" / "resources" / "db" / "migration"


def flyway_checksum(path: Path) -> int:
    data = path.read_bytes().replace(b"\r\n", b"\n")
    crc = zlib.crc32(data) & 0xFFFFFFFF
    if crc >= 0x80000000:
        crc -= 0x100000000
    return crc


def column_exists(cur, table: str, column: str) -> bool:
    cur.execute(
        """
        select 1 from information_schema.columns
         where table_schema = database()
           and table_name = %s
           and column_name = %s
        """,
        (table, column),
    )
    return cur.fetchone() is not None


def table_exists(cur, table: str) -> bool:
    cur.execute(
        """
        select 1 from information_schema.tables
         where table_schema = database()
           and table_name = %s
        """,
        (table,),
    )
    return cur.fetchone() is not None


def index_exists(cur, table: str, index: str) -> bool:
    cur.execute(
        """
        select 1 from information_schema.statistics
         where table_schema = database()
           and table_name = %s
           and index_name = %s
         limit 1
        """,
        (table, index),
    )
    return cur.fetchone() is not None


def main() -> None:
    conn = pymysql.connect(
        host="127.0.0.1",
        user="root",
        password="root",
        database="backend",
        autocommit=True,
    )
    cur = conn.cursor()

    if table_exists(cur, "ppt_jobs"):
        if not column_exists(cur, "ppt_jobs", "lease_owner"):
            cur.execute(
                "alter table ppt_jobs add column lease_owner varchar(64) null after error_message"
            )
            print("added ppt_jobs.lease_owner")
        if not column_exists(cur, "ppt_jobs", "lease_until"):
            cur.execute(
                "alter table ppt_jobs add column lease_until datetime(6) null after lease_owner"
            )
            print("added ppt_jobs.lease_until")
        if not index_exists(cur, "ppt_jobs", "idx_ppt_jobs_status_updated"):
            cur.execute(
                "create index idx_ppt_jobs_status_updated on ppt_jobs (status, updated_at)"
            )
            print("added idx_ppt_jobs_status_updated")
        if not index_exists(cur, "ppt_jobs", "idx_ppt_jobs_lease_until"):
            cur.execute("create index idx_ppt_jobs_lease_until on ppt_jobs (lease_until)")
            print("added idx_ppt_jobs_lease_until")

    if not table_exists(cur, "task_queue"):
        cur.execute(
            """
            CREATE TABLE task_queue (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                queue_name VARCHAR(64) NOT NULL,
                payload LONGTEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'pending',
                attempts INT NOT NULL DEFAULT 0,
                max_attempts INT NOT NULL DEFAULT 5,
                available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                lease_owner VARCHAR(64) NULL,
                lease_until DATETIME(6) NULL,
                last_error VARCHAR(2048) NULL,
                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                PRIMARY KEY (id),
                KEY idx_task_queue_claim (status, available_at, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """
        )
        print("created task_queue")

    if not table_exists(cur, "shedlock"):
        cur.execute(
            """
            CREATE TABLE shedlock (
                name VARCHAR(64) NOT NULL,
                lock_until TIMESTAMP(3) NULL,
                locked_at TIMESTAMP(3) NULL,
                locked_by VARCHAR(255) NULL,
                PRIMARY KEY (name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """
        )
        print("created shedlock")

    if not column_exists(cur, "llm_models", "upstream_groups"):
        cur.execute(
            "alter table llm_models add column upstream_groups varchar(512) null after upstream_available"
        )
        print("added llm_models.upstream_groups")

    if not table_exists(cur, "newapi_groups"):
        cur.execute(
            """
            CREATE TABLE newapi_groups (
                id         BIGINT        NOT NULL AUTO_INCREMENT,
                group_key  VARCHAR(64)   NOT NULL,
                label      VARCHAR(128)  NULL,
                ratio      DECIMAL(18,6) NULL,
                usable     BOOLEAN       NOT NULL DEFAULT TRUE,
                synced_at  DATETIME(6)   NOT NULL,
                created_at DATETIME(6)   NOT NULL,
                updated_at DATETIME(6)   NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_newapi_groups_key (group_key)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
            """
        )
        print("created newapi_groups")

    if not table_exists(cur, "newapi_group_tokens"):
        cur.execute(
            """
            CREATE TABLE newapi_group_tokens (
                id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                user_id BIGINT UNSIGNED NOT NULL,
                site_id BIGINT UNSIGNED NOT NULL,
                group_key VARCHAR(64) NOT NULL,
                token_name VARCHAR(50) NOT NULL,
                newapi_token_id BIGINT UNSIGNED NULL,
                key_ciphertext TINYBLOB NULL,
                key_nonce VARBINARY(12) NULL,
                key_tag VARBINARY(16) NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING',
                last_error VARCHAR(512) NULL,
                attempt INT NOT NULL DEFAULT 0,
                next_retry_at DATETIME(6) NULL,
                lease_owner VARCHAR(64) NULL,
                lease_until DATETIME(6) NULL,
                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                PRIMARY KEY (id),
                UNIQUE KEY uk_newapi_group_tokens_user_group (user_id, group_key),
                UNIQUE KEY uk_newapi_group_tokens_newapi_id (newapi_token_id),
                UNIQUE KEY uk_newapi_group_tokens_name (token_name),
                KEY idx_newapi_group_tokens_status_retry (status, next_retry_at),
                CONSTRAINT fk_newapi_group_tokens_user
                    FOREIGN KEY (user_id) REFERENCES user_accounts (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """
        )
        print("created newapi_group_tokens")

    mapping = {
        "104": "V104__ppt_job_lease_and_recovery.sql",
        "105": "V105__task_queue.sql",
        "106": "V106__shedlock.sql",
        "107": "V107__newapi_groups.sql",
        "108": "V108__newapi_group_tokens.sql",
    }
    for version, script in mapping.items():
        path = MIG / script
        checksum = flyway_checksum(path)
        cur.execute(
            """
            update flyway_schema_history
               set script = %s,
                   checksum = %s
             where version = %s
            """,
            (script, checksum, version),
        )
        print(f"repaired flyway {version} -> {script} checksum={checksum}")

    cur.close()
    conn.close()
    print("done")


if __name__ == "__main__":
    main()
