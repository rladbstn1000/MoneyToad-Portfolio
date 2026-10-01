-- Apply once after the reviewed baseline and V001, before granting runtime/cleanup privileges.
-- This row carries no business state. UPDATE privilege is only for FOR UPDATE authorization.
-- Product code never updates, inserts or repairs this table.
CREATE TABLE demo_admission_lock (
    id TINYINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO demo_admission_lock (id) VALUES (1);
