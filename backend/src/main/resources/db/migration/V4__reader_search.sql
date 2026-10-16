-- Reader feeds, categories and search (SRS §5.4, §5.5).

-- FR-027: publisher categories are mapped to app categories by explicit,
-- reviewable rules. Unmapped articles stay uncategorized.
CREATE TABLE category_mapping (
    id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL = applies to every publisher
    source_id          BIGINT       REFERENCES news_source (id),
    publisher_category VARCHAR(200) NOT NULL CHECK (publisher_category = lower(btrim(publisher_category))),
    category_id        SMALLINT     NOT NULL REFERENCES category (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX category_mapping_uq
    ON category_mapping (COALESCE(source_id, 0), publisher_category, category_id);

-- Global starting rules: publisher labels that match an app category name.
INSERT INTO category_mapping (publisher_category, category_id)
SELECT label, c.id
FROM (VALUES ('world', 'world'), ('world news', 'world'), ('international', 'world'),
             ('national', 'national'), ('nation', 'national'), ('india', 'national'),
             ('politics', 'politics'),
             ('business', 'business'), ('economy', 'business'), ('markets', 'business'),
             ('technology', 'technology'), ('tech', 'technology'),
             ('science', 'science'),
             ('health', 'health'),
             ('education', 'education'),
             ('sports', 'sports'), ('sport', 'sports'), ('cricket', 'sports'), ('football', 'sports'),
             ('entertainment', 'entertainment'), ('movies', 'entertainment'), ('music', 'entertainment'),
             ('environment', 'environment'), ('climate', 'environment'),
             ('lifestyle', 'lifestyle')) AS m (label, code)
JOIN category c ON c.code = m.code;

CREATE TABLE article_category (
    article_id  BIGINT      NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    category_id SMALLINT    NOT NULL REFERENCES category (id),
    assigned_by VARCHAR(20) NOT NULL CHECK (assigned_by IN ('PUBLISHER_MAPPING', 'MANUAL')),
    PRIMARY KEY (article_id, category_id)
);

CREATE INDEX article_category_category_idx ON article_category (category_id, article_id);

-- Text search configuration per article language. Urdu has no stemmer, so it
-- uses 'simple'. Marked IMMUTABLE so it can be used in a generated column.
CREATE FUNCTION article_ts_config(lang TEXT) RETURNS regconfig
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT CASE lang
           WHEN 'en' THEN 'english'::regconfig
           WHEN 'hi' THEN 'hindi'::regconfig
           WHEN 'ar' THEN 'arabic'::regconfig
           ELSE 'simple'::regconfig
       END
$$;

-- Ordering time for "Latest": publication time when known, never later than
-- when we first saw the article (so future-dated items cannot pin to the top).
ALTER TABLE article
    ADD COLUMN sort_at TIMESTAMPTZ
        GENERATED ALWAYS AS (LEAST(COALESCE(published_at, first_ingested_at), first_ingested_at)) STORED;

-- FR-031: search title and excerpt (permitted metadata). The stemmed vector is
-- combined with an unstemmed one so exact words match in any language.
ALTER TABLE article
    ADD COLUMN search_vector TSVECTOR
        GENERATED ALWAYS AS (
            setweight(to_tsvector(article_ts_config(language), coalesce(title, '')), 'A') ||
            setweight(to_tsvector(article_ts_config(language), coalesce(excerpt, '')), 'B') ||
            to_tsvector('simple'::regconfig, coalesce(title, '') || ' ' || coalesce(excerpt, ''))
        ) STORED;

CREATE INDEX article_sort_idx ON article (sort_at DESC, id DESC);
CREATE INDEX article_search_idx ON article USING GIN (search_vector);
CREATE INDEX article_language_sort_idx ON article (language, sort_at DESC, id DESC);
