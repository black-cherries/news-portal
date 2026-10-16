-- Accounts, roles and one-time tokens (SRS §4, §5.6, FR-037..FR-044, FR-074).
--
-- Reader and staff accounts are different account types. Staff roles can only
-- be granted to STAFF accounts; the composite foreign key below enforces this.

CREATE TABLE app_user (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_type        VARCHAR(10)  NOT NULL CHECK (account_type IN ('READER', 'STAFF')),
    -- Stored trimmed and lower-cased by the application.
    email               VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(btrim(email))),
    password_hash       VARCHAR(255) NOT NULL,
    status              VARCHAR(30)  NOT NULL
                        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    ui_language         VARCHAR(8)   NOT NULL DEFAULT 'en' CHECK (ui_language IN ('en', 'hi', 'ur', 'ar')),
    -- FR-044: the user's own declaration that they are 16 or older. Not proof of age.
    age_declared_at     TIMESTAMPTZ  NOT NULL,
    email_verified_at   TIMESTAMPTZ,
    -- Changes whenever existing sessions must stop working (password change, disable).
    security_stamp      VARCHAR(64)  NOT NULL,
    failed_login_count  INTEGER      NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (id, account_type),
    CHECK (status <> 'ACTIVE' OR email_verified_at IS NOT NULL)
);

CREATE TABLE role (
    id          SMALLINT     GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(40)  NOT NULL UNIQUE,
    description VARCHAR(200) NOT NULL
);

INSERT INTO role (code, description) VALUES
    ('SYSTEM_ADMIN',     'Users, roles, security, configuration and overall operations'),
    ('SOURCE_MANAGER',   'Publisher configuration, rights reviews, source approvals and ingestion settings'),
    ('CONTENT_REVIEWER', 'Assigned reports, corrections and future AI-content reviews'),
    ('ANALYST',          'Read-only reporting and analytics');

CREATE TABLE user_role (
    user_id      BIGINT      NOT NULL,
    -- Always 'STAFF'; part of the foreign key so readers can never hold a staff role.
    account_type VARCHAR(10) NOT NULL DEFAULT 'STAFF' CHECK (account_type = 'STAFF'),
    role_id      SMALLINT    NOT NULL REFERENCES role (id),
    granted_by   BIGINT      REFERENCES app_user (id),
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id, account_type) REFERENCES app_user (id, account_type)
);

-- One-time tokens. Only a SHA-256 hash of the token is stored.
CREATE TABLE auth_token (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose     VARCHAR(20) NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    token_hash  VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX auth_token_user_idx ON auth_token (user_id, purpose);

-- FR-076: sensitive staff and security actions. Append-only.
CREATE TABLE audit_log (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT       REFERENCES app_user (id),
    action        VARCHAR(60)  NOT NULL,
    target_type   VARCHAR(40),
    target_id     VARCHAR(60),
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX audit_log_created_idx ON audit_log (created_at DESC);

CREATE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();
