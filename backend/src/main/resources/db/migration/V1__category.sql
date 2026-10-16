-- Normalized app categories (SRS FR-026). Publisher-supplied categories are
-- stored separately (FR-027). Display names are localized in the frontend
-- using the stable code.
CREATE TABLE category (
    id         SMALLINT    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(40) NOT NULL UNIQUE,
    sort_order SMALLINT    NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO category (code, sort_order) VALUES
    ('world',         10),
    ('national',      20),
    ('politics',      30),
    ('business',      40),
    ('technology',    50),
    ('science',       60),
    ('health',        70),
    ('education',     80),
    ('sports',        90),
    ('entertainment', 100),
    ('environment',   110),
    ('lifestyle',     120);
