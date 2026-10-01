-- Reviewed initial schema for the existing seven JPA entities.
-- Apply only to a newly created, empty, runner-owned verification schema.
-- The caller selects and owns the schema; this file does not create, drop,
-- select, or repair a database and contains no data or account statements.
-- Reapplying to existing tables must fail; do not add IF NOT EXISTS.
-- Runtime must use the unchanged product demo,render ConfigData and validate.
-- MySQL 8.4's collation is explicit to avoid TiDB's different default.
-- Actual TiDB version/collation and constraint enforcement require remote checks.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    email VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    gender VARCHAR(20) NULL,
    age INT NULL,
    file_id VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    card_no VARCHAR(255) NULL,
    cvc VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cards_user UNIQUE (user_id),
    CONSTRAINT fk_cards_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE transactions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    card_id BIGINT NULL,
    transaction_date_time DATETIME(6) NULL,
    amount INT NULL,
    merchant_name VARCHAR(255) NULL,
    category VARCHAR(255) NULL,
    PRIMARY KEY (id),
    INDEX idx_transactions_card (card_id),
    CONSTRAINT fk_transactions_card FOREIGN KEY (card_id) REFERENCES cards (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE budgets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    budget_date DATE NULL,
    amount INT NULL,
    category VARCHAR(255) NULL,
    initial_amount INT NULL,
    initial_file_id VARCHAR(255) NULL,
    predicted_at DATETIME(6) NULL,
    is_overridden BIT(1) NULL,
    overridden_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_budgets_user (user_id),
    CONSTRAINT fk_budgets_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    file_id VARCHAR(255) NULL,
    status ENUM('DONE', 'ERROR', 'QUEUED', 'RUNNING') NULL,
    retry_count INT NULL,
    last_message VARCHAR(255) NULL,
    next_poll_at DATETIME(6) NULL,
    leased_until DATETIME(6) NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE peer_transaction_stats (
    id BIGINT NOT NULL AUTO_INCREMENT,
    age_group INT NULL,
    gender VARCHAR(255) NULL,
    stats_date DATE NULL,
    amount INT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Dummy is still an unconditional entity; validate needs its empty table.
-- No legacy CSV, merchant fixture, peer rows, or analysis rows are imported.
CREATE TABLE dummy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    category VARCHAR(50) NOT NULL,
    merchant_name VARCHAR(100) NOT NULL,
    min_amount INT NOT NULL,
    max_amount INT NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_dummy_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
