-- Publisher and rights management (SRS §5.1, FR-001..FR-007).
--
-- news_source    one row per publisher
-- source_rights  current permission per publisher and content use (FR-004)
-- source_review  append-only history of every rights/suspension decision (FR-003, FR-006)
--
-- Reviewer identity is stored as text until the identity tables exist; a later
-- migration will link it to app_user.

CREATE TABLE news_source (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(60)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    homepage_url     VARCHAR(500) NOT NULL,
    primary_language VARCHAR(8)   NOT NULL CHECK (primary_language IN ('en', 'hi', 'ur', 'ar')),
    status           VARCHAR(20)  NOT NULL DEFAULT 'PROPOSED'
                     CHECK (status IN ('PROPOSED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    status_reason    VARCHAR(500),
    terms_url        VARCHAR(500),
    terms_summary    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE source_review (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id       BIGINT       NOT NULL REFERENCES news_source (id),
    -- NULL for source-level actions (activate/suspend/restore/retire the publisher)
    content_use     VARCHAR(30),
    action          VARCHAR(30)  NOT NULL
                    CHECK (action IN ('RIGHTS_DECISION', 'USE_SUSPENDED', 'USE_RESTORED',
                                      'SOURCE_ACTIVATED', 'SOURCE_SUSPENDED', 'SOURCE_RESTORED',
                                      'SOURCE_RETIRED')),
    previous_status VARCHAR(20),
    new_status      VARCHAR(20),
    valid_from      TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    reviewer        VARCHAR(200) NOT NULL CHECK (btrim(reviewer) <> ''),
    notes           TEXT         NOT NULL CHECK (btrim(notes) <> ''),
    evidence_url    VARCHAR(500),
    next_review_due DATE,
    reviewed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX source_review_source_idx ON source_review (source_id, reviewed_at DESC);

CREATE TABLE source_rights (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id        BIGINT       NOT NULL REFERENCES news_source (id),
    content_use      VARCHAR(30)  NOT NULL
                     CHECK (content_use IN ('METADATA', 'FULL_TEXT_DISPLAY', 'STORAGE', 'TRANSLATION',
                                            'SUMMARY', 'NARRATION', 'VIDEO_EMBED', 'GENERATED_MEDIA')),
    status           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN'
                     CHECK (status IN ('UNKNOWN', 'PERMITTED', 'DENIED', 'DISPUTED')),
    valid_from       TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,
    conditions       TEXT,
    suspended        BOOLEAN      NOT NULL DEFAULT FALSE,
    suspended_at     TIMESTAMPTZ,
    suspended_reason VARCHAR(500),
    last_review_id   BIGINT       REFERENCES source_review (id),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (source_id, content_use),
    CHECK (expires_at IS NULL OR valid_from IS NULL OR expires_at > valid_from),
    CHECK (NOT suspended OR suspended_at IS NOT NULL),
    -- FR-007: a permission can only come from a recorded review, never from feed availability.
    CHECK (status <> 'PERMITTED' OR last_review_id IS NOT NULL)
);

ALTER TABLE source_review
    ADD CONSTRAINT source_review_content_use_check
    CHECK (content_use IS NULL OR content_use IN ('METADATA', 'FULL_TEXT_DISPLAY', 'STORAGE', 'TRANSLATION',
                                                  'SUMMARY', 'NARRATION', 'VIDEO_EMBED', 'GENERATED_MEDIA'));

-- Review history is an audit record: block edits and deletes.
CREATE FUNCTION source_review_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'source_review is append-only';
END;
$$;

CREATE TRIGGER source_review_no_update_delete
    BEFORE UPDATE OR DELETE ON source_review
    FOR EACH ROW EXECUTE FUNCTION source_review_append_only();
