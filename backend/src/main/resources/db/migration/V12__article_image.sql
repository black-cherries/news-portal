-- Publisher images (owner decision of 2026-10-16).
--
-- A lead image is shown only while the publisher's new IMAGE permission is
-- valid. Like videos, only the link is stored: images load from the
-- publisher's server.

ALTER TABLE source_rights DROP CONSTRAINT source_rights_content_use_check;
ALTER TABLE source_rights ADD CONSTRAINT source_rights_content_use_check
    CHECK (content_use IN ('METADATA', 'FULL_TEXT_DISPLAY', 'STORAGE', 'TRANSLATION', 'SUMMARY', 'NARRATION',
                           'VIDEO_EMBED', 'GENERATED_MEDIA', 'IMAGE'));

ALTER TABLE source_review DROP CONSTRAINT source_review_content_use_check;
ALTER TABLE source_review ADD CONSTRAINT source_review_content_use_check
    CHECK (content_use IS NULL OR content_use IN ('METADATA', 'FULL_TEXT_DISPLAY', 'STORAGE', 'TRANSLATION',
                                                  'SUMMARY', 'NARRATION', 'VIDEO_EMBED', 'GENERATED_MEDIA',
                                                  'IMAGE'));

-- HTTPS only, so pages never load mixed content.
ALTER TABLE article ADD COLUMN image_url VARCHAR(2000) CHECK (image_url ~ '^https://');
