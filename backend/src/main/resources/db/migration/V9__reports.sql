-- Structured user reports and correction requests (SRS §5.9, FR-070..FR-073).
--
-- Reports never change publisher content (FR-073). When a reporter's account
-- is purged, the report stays but loses the link to them (FR-054).

CREATE TABLE report (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Random, not sequential, so references cannot be guessed (FR-072).
    reference        VARCHAR(12)  NOT NULL UNIQUE,
    type             VARCHAR(30)  NOT NULL
                     CHECK (type IN ('TECHNICAL', 'BROKEN_LINK', 'LANGUAGE_ERROR', 'TRANSLATION_ISSUE',
                                     'MISSING_ATTRIBUTION', 'SUSPECTED_DUPLICATE', 'MISLEADING_WORDING',
                                     'CORRECTION_REQUEST')),
    status           VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED'
                     CHECK (status IN ('RECEIVED', 'UNDER_REVIEW', 'RESOLVED')),
    resolution       VARCHAR(30)
                     CHECK (resolution IN ('FIXED', 'PUBLISHER_NOTIFIED', 'NO_ACTION_NEEDED', 'DUPLICATE_REPORT',
                                           'NOT_REPRODUCIBLE')),
    article_id       BIGINT       REFERENCES article (id) ON DELETE SET NULL,
    source_id        BIGINT       REFERENCES news_source (id) ON DELETE SET NULL,
    reporter_user_id BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    notify_reporter  BOOLEAN      NOT NULL DEFAULT FALSE,
    description      TEXT         NOT NULL CHECK (char_length(description) BETWEEN 10 AND 2000),
    page_url         VARCHAR(500),
    ui_language      VARCHAR(8),
    assigned_to      BIGINT       REFERENCES app_user (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    resolved_at      TIMESTAMPTZ,
    CHECK (status <> 'RESOLVED' OR (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);

CREATE INDEX report_status_idx ON report (status, created_at DESC);
CREATE INDEX report_reporter_idx ON report (reporter_user_id, created_at DESC);

CREATE TABLE report_status_history (
    id            BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id     BIGINT        NOT NULL REFERENCES report (id) ON DELETE CASCADE,
    from_status   VARCHAR(20),
    to_status     VARCHAR(20)   NOT NULL,
    changed_by    BIGINT        REFERENCES app_user (id) ON DELETE SET NULL,
    -- Shown to the reporter.
    public_note   VARCHAR(1000),
    -- Staff only.
    internal_note VARCHAR(2000),
    changed_at    TIMESTAMPTZ   NOT NULL
);

CREATE INDEX report_status_history_report_idx ON report_status_history (report_id, changed_at);
