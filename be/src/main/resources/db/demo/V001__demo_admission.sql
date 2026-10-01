-- Apply once after the reviewed seven-table baseline, to an empty dedicated demo schema only.
-- Deliberately no IF NOT EXISTS, cascading FK, automatic legacy backfill or runtime DDL.
CREATE TABLE demo_capacity (
    id TINYINT NOT NULL,
    max_visitors INT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE demo_visit (
    user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    scenario_version VARCHAR(16) NOT NULL,
    session_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_demo_visit_created_user (created_at, user_id),
    CONSTRAINT fk_demo_visit_user FOREIGN KEY (user_id) REFERENCES users(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO demo_capacity (id, max_visitors) VALUES (1, 1000);
