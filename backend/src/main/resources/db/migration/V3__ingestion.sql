-- News ingestion (SRS §5.2, FR-008..FR-016) and the article catalogue.
--
-- source_feed         connector configuration and current health per feed
-- ingestion_job       one row per fetch attempt (FR-010)
-- quarantined_record  invalid feed entries, kept with a reason (FR-013)
-- article             permitted metadata, one row per publisher story (FR-011, FR-012)
-- article_content     full text, stored only when STORAGE is permitted (FR-016)

CREATE TABLE source_feed (
    id                       BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id                BIGINT       NOT NULL REFERENCES news_source (id),
    feed_url                 VARCHAR(1000) NOT NULL,
    connector                VARCHAR(20)  NOT NULL DEFAULT 'RSS' CHECK (connector IN ('RSS')),
    refresh_interval_minutes INTEGER      NOT NULL DEFAULT 30 CHECK (refresh_interval_minutes BETWEEN 5 AND 1440),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    next_run_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_attempt_at          TIMESTAMPTZ,
    last_success_at          TIMESTAMPTZ,
    last_outcome             VARCHAR(30),
    consecutive_failures     INTEGER      NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    etag                     VARCHAR(500),
    last_modified            VARCHAR(100),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (source_id, feed_url)
);

CREATE INDEX source_feed_due_idx ON source_feed (next_run_at) WHERE enabled;

CREATE TABLE ingestion_job (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feed_id           BIGINT       NOT NULL REFERENCES source_feed (id),
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ  NOT NULL,
    outcome           VARCHAR(30)  NOT NULL,
    http_status       INTEGER,
    items_seen        INTEGER      NOT NULL DEFAULT 0,
    items_created     INTEGER      NOT NULL DEFAULT 0,
    items_updated     INTEGER      NOT NULL DEFAULT 0,
    items_unchanged   INTEGER      NOT NULL DEFAULT 0,
    items_quarantined INTEGER      NOT NULL DEFAULT 0,
    diagnostic        VARCHAR(500)
);

CREATE INDEX ingestion_job_feed_idx ON ingestion_job (feed_id, started_at DESC);

CREATE TABLE quarantined_record (
    id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id      BIGINT        NOT NULL REFERENCES ingestion_job (id),
    reason      VARCHAR(40)   NOT NULL,
    entry_guid  VARCHAR(1000),
    entry_link  VARCHAR(2000),
    diagnostic  VARCHAR(500),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE article (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id            BIGINT        NOT NULL REFERENCES news_source (id),
    publisher_guid       VARCHAR(1000),
    canonical_url        VARCHAR(2000) NOT NULL,
    title                VARCHAR(1000) NOT NULL,
    excerpt              TEXT,
    author               VARCHAR(500),
    language             VARCHAR(16)   NOT NULL,
    publisher_categories TEXT[]        NOT NULL DEFAULT '{}',
    -- FR-022: three separate times; unknown publisher times stay NULL.
    published_at         TIMESTAMPTZ,
    publisher_updated_at TIMESTAMPTZ,
    first_ingested_at    TIMESTAMPTZ   NOT NULL,
    last_ingested_at     TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    -- FR-012: identity is per publisher, so reports from different publishers never merge.
    UNIQUE (source_id, canonical_url)
);

CREATE UNIQUE INDEX article_source_guid_uq ON article (source_id, publisher_guid) WHERE publisher_guid IS NOT NULL;
CREATE INDEX article_published_idx ON article (published_at DESC NULLS LAST);

CREATE TABLE article_content (
    article_id   BIGINT       PRIMARY KEY REFERENCES article (id) ON DELETE CASCADE,
    body_html    TEXT         NOT NULL,
    stored_at    TIMESTAMPTZ  NOT NULL
);
