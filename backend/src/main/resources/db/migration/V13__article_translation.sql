-- On-demand machine translation (owner decision of 2026-10-16; SRS amendment 2).
--
-- One cached translation per article and language. source_hash identifies
-- the original text that was translated, so a changed article is translated
-- again. Translations are shown only while the publisher's TRANSLATION
-- permission is valid; the body only while full-text display is too.

CREATE TABLE article_translation (
    article_id    BIGINT       NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    language      VARCHAR(8)   NOT NULL CHECK (language IN ('en', 'hi', 'ur', 'ar')),
    title         VARCHAR(2000) NOT NULL,
    excerpt       TEXT,
    body_html     TEXT,
    source_hash   VARCHAR(64)  NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    model         VARCHAR(100) NOT NULL,
    translated_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (article_id, language)
);

-- How many new translations were made, for the daily cost limit.
CREATE INDEX article_translation_translated_idx ON article_translation (translated_at);
