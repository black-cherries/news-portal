-- Email digests and breaking-news alerts (SRS §5.9, FR-065..FR-069).
--
-- Everything is off by default (FR-066). A reader's settings and delivery log
-- are removed with the account.

CREATE TABLE notification_setting (
    user_id           BIGINT      PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    digest_frequency  VARCHAR(10) NOT NULL DEFAULT 'OFF' CHECK (digest_frequency IN ('OFF', 'DAILY', 'WEEKLY')),
    breaking_alerts   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Random value in every notification email's unsubscribe link. It can only
    -- turn notifications off, so it is stored as is.
    unsubscribe_token VARCHAR(64) NOT NULL UNIQUE CHECK (unsubscribe_token ~ '^[A-Za-z0-9_-]{43}$'),
    last_digest_at    TIMESTAMPTZ,
    next_digest_at    TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ NOT NULL,
    CHECK (digest_frequency = 'OFF' OR next_digest_at IS NOT NULL)
);

CREATE INDEX notification_setting_due_idx ON notification_setting (next_digest_at)
    WHERE digest_frequency <> 'OFF';

-- FR-066: notification languages and topics are separate from the For You
-- feed. No rows means "same as my feed settings".
CREATE TABLE notification_language (
    user_id  BIGINT     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    language VARCHAR(8) NOT NULL CHECK (language IN ('en', 'hi', 'ur', 'ar')),
    PRIMARY KEY (user_id, language)
);

CREATE TABLE notification_topic (
    user_id     BIGINT   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category_id SMALLINT NOT NULL REFERENCES category (id),
    PRIMARY KEY (user_id, category_id)
);

-- Breaking-news alerts are chosen by staff. Automatic story detection is a
-- later phase (SRS §3.2), so syndicated copies never trigger an alert (FR-067).
CREATE TABLE breaking_alert (
    id              BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    article_id      BIGINT        REFERENCES article (id) ON DELETE SET NULL,
    -- Normalized headline, used to refuse repeat alerts for the same story.
    title_key       VARCHAR(1000) NOT NULL,
    status          VARCHAR(12)   NOT NULL CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'CANCELLED')),
    reason          VARCHAR(500)  NOT NULL CHECK (btrim(reason) <> ''),
    created_by      BIGINT        REFERENCES app_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    -- A short hold so a mistaken alert can be cancelled before anything is sent.
    send_after      TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    cancelled_by    BIGINT        REFERENCES app_user (id) ON DELETE SET NULL,
    cancelled_at    TIMESTAMPTZ,
    cancel_reason   VARCHAR(500),
    recipient_count INTEGER       NOT NULL DEFAULT 0,
    skipped_count   INTEGER       NOT NULL DEFAULT 0,
    CHECK (status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL))
);

CREATE INDEX breaking_alert_created_idx ON breaking_alert (created_at DESC);
CREATE UNIQUE INDEX breaking_alert_article_uq ON breaking_alert (article_id) WHERE status <> 'CANCELLED';

-- One row per notification email attempted, so nothing is sent twice and
-- per-reader limits can be checked. Kept for a limited time only.
CREATE TABLE notification_delivery (
    id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('DIGEST', 'ALERT')),
    alert_id   BIGINT      REFERENCES breaking_alert (id) ON DELETE CASCADE,
    outcome    VARCHAR(20) NOT NULL CHECK (outcome IN ('SENT', 'FAILED', 'SKIPPED_LIMIT')),
    item_count INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((kind = 'ALERT') = (alert_id IS NOT NULL))
);

CREATE UNIQUE INDEX notification_delivery_alert_uq ON notification_delivery (alert_id, user_id)
    WHERE alert_id IS NOT NULL;
CREATE INDEX notification_delivery_user_idx ON notification_delivery (user_id, kind, created_at DESC);
CREATE INDEX notification_delivery_created_idx ON notification_delivery (created_at);
