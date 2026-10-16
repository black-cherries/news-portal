-- Videos named in a publisher's feed entry (SRS §5.8, FR-056).
--
-- Only the link is stored; it is metadata. Whether a video may be embedded
-- is decided when the article is read, from the VIDEO_EMBED permission.

CREATE TABLE article_video (
    article_id BIGINT        NOT NULL REFERENCES article (id) ON DELETE CASCADE,
    position   SMALLINT      NOT NULL CHECK (position BETWEEN 0 AND 9),
    source_url VARCHAR(2000) NOT NULL CHECK (source_url ~ '^https?://'),
    mime_type  VARCHAR(100),
    PRIMARY KEY (article_id, position)
);
