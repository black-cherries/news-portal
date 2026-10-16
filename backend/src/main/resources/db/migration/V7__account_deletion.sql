-- Account deletion with a recovery period (FR-052..FR-055).

ALTER TABLE app_user DROP CONSTRAINT app_user_status_check;
ALTER TABLE app_user
    ADD CONSTRAINT app_user_status_check
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED', 'DELETION_PENDING', 'DELETED')),
    ADD COLUMN deletion_requested_at TIMESTAMPTZ,
    ADD COLUMN deletion_due_at       TIMESTAMPTZ,
    ADD CONSTRAINT app_user_deletion_due_check
        CHECK (status <> 'DELETION_PENDING' OR deletion_due_at IS NOT NULL);

CREATE INDEX app_user_deletion_due_idx ON app_user (deletion_due_at) WHERE status = 'DELETION_PENDING';

-- FR-055: ids of purged accounts, so deletions can be re-applied after a
-- backup restore. Holds no personal data; deliberately no foreign key.
-- The application also appends each id to a file kept outside the database.
CREATE TABLE account_deletion_ledger (
    user_id   BIGINT      PRIMARY KEY,
    purged_at TIMESTAMPTZ NOT NULL
);
