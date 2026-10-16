-- Backups, restore tests and operational alerts (SRS §12, FR-077, FR-078).

-- One row per backup or restore-test run.
CREATE TABLE ops_run (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        VARCHAR(20)   NOT NULL CHECK (kind IN ('BACKUP', 'RESTORE_TEST')),
    started_at  TIMESTAMPTZ   NOT NULL,
    finished_at TIMESTAMPTZ   NOT NULL,
    outcome     VARCHAR(10)   NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILED')),
    file_name   VARCHAR(300),
    size_bytes  BIGINT,
    sha256      VARCHAR(64),
    detail      VARCHAR(1000)
);

CREATE INDEX ops_run_kind_idx ON ops_run (kind, started_at DESC);

-- Current state of each alert, so restarts neither lose nor repeat notifications.
CREATE TABLE ops_alert (
    alert_key        VARCHAR(100) PRIMARY KEY,
    status           VARCHAR(10)  NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    message          VARCHAR(500) NOT NULL,
    first_seen_at    TIMESTAMPTZ  NOT NULL,
    last_seen_at     TIMESTAMPTZ  NOT NULL,
    last_notified_at TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ
);
