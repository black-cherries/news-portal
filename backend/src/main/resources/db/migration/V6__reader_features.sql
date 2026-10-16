-- Registered-reader features (SRS §4.2, §5.4, §5.5, §5.7).
-- Everything here belongs to one user and is removed with the account.

-- FR-045: interface language (existing ui_language), news languages and digest language are separate.
ALTER TABLE app_user
    ADD COLUMN digest_language VARCHAR(8) CHECK (digest_language IN ('en', 'hi', 'ur', 'ar')),
    -- FR-047: reader can pause history tracking.
    ADD COLUMN history_paused  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE user_language (
    user_id  BIGINT     NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    language VARCHAR(8) NOT NULL CHECK (language IN ('en', 'hi', 'ur', 'ar')),
    position SMALLINT   NOT NULL,
    PRIMARY KEY (user_id, language)
);

CREATE TABLE user_topic (
    user_id     BIGINT   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    category_id SMALLINT NOT NULL REFERENCES category (id),
    PRIMARY KEY (user_id, category_id)
);

-- FR-034/FR-035: private to the owner.
CREATE TABLE bookmark (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    article_id BIGINT      NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, article_id)
);

CREATE INDEX bookmark_user_created_idx ON bookmark (user_id, created_at DESC);

CREATE TABLE collection (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX collection_user_name_uq ON collection (user_id, lower(name));

CREATE TABLE collection_item (
    collection_id BIGINT      NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    article_id    BIGINT      NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    added_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (collection_id, article_id)
);

-- FR-047/FR-048: one row per user, article and kind, holding only the latest time.
-- Kept separate from bookmarks and collections.
CREATE TABLE reading_history (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    article_id BIGINT      NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('READ', 'LISTEN')),
    last_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, article_id, kind)
);

CREATE INDEX reading_history_user_last_idx ON reading_history (user_id, last_at DESC);
