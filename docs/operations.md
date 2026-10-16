# Operations: backups, restore and monitoring

This covers SRS §12 (backup and recovery) and FR-077/FR-078 (monitoring and alerts). Everything runs inside the backend; no external scheduler is needed.

## Backups

- **When:** daily at 02:30 server time (`BACKUP_CRON`). A system admin can also run one with `POST /api/v1/admin/ops/backup`.
- **What:** a compressed `pg_dump` archive. The backend first writes it as `….dump.partial`, checks it with `pg_restore --list`, then renames it to `newsportal-<db>-<UTC time>-<id>.dump`. A copy of the deletion ledger is saved beside it as `….deletion-ledger.txt` (FR-055).
- **Where:** `BACKUP_DIR` (default `data/backups`). For the pilot, put this on a different disk from the database. A separate device or off-site copy is a later phase (§12).
- **Retention:** 14 days (`BACKUP_RETENTION_DAYS`). The newest backup is always kept, even if it is older than that.
- **Record:** every run is stored in `ops_run` with its outcome, size and SHA-256.
- **Data-loss window:** up to 24 hours. A failure loses changes made since the last successful daily backup.

**Requirement:** `pg_dump` and `pg_restore` must be the same major version as the server (17). Set `PG_BIN_DIR` to their folder, for example `D:/PostgreSQL/17/bin`.

## Restore tests

A backup isn't considered reliable until a restore succeeds (§12). Restore tests run weekly, on Sunday at 03:30 (`RESTORE_TEST_CRON`), or on demand with `POST /api/v1/admin/ops/restore-test`.

Each test:
1. Restores the newest backup into a **separate scratch database** (`RESTORE_TEST_DB_URL`) with `pg_restore --clean --single-transaction --exit-on-error`.
2. Checks that the restored schema version matches the live one, and that core tables are readable.
3. Records the result in `ops_run`.

The backend refuses to run a restore test if `RESTORE_TEST_DB_URL` points at the live database.

To set up the scratch database (`scripts/db/setup-local-db.ps1` does this for development), run as a superuser:

```sql
CREATE DATABASE newsportal_restore OWNER <app role> TEMPLATE template0;
REVOKE ALL ON DATABASE newsportal_restore FROM PUBLIC;
```

## Monitoring and alerts

Every 5 minutes the backend checks:

| Check | Alert key |
|---|---|
| Database reachable | `database.unreachable` (emailed directly, at most every 6 h) |
| Feeds of active publishers | `feed.failing.<id>`, `feed.stale.<id>` (rights restrictions are not alerts) |
| Last backup | `backup.failed`; `backup.stale` when there's no success in 26 h |
| Restore tests | `restore-test.failed`; `restore-test.stale` when there's no success in 8 days; `restore-test.not-configured` |
| Free disk space where backups are written | `disk.backup-low` when below `MIN_FREE_DISK_MB` (default 2048) |

How alerts are sent:

- A **new** problem is emailed at once.
- A problem that **continues** is emailed again every 6 hours.
- A problem that **clears** is emailed once as resolved.
- All changes from one check go out in a single email.
- Alert state is stored in `ops_alert`, so a restart doesn't resend alerts or lose track of them.

Recipients are set in `OPS_ALERT_RECIPIENTS` (comma-separated). If it's empty, alerts go to all active system administrators. In development they land in `.mail-outbox/`.

`GET /api/v1/admin/ops` shows open and recently resolved alerts, recent backups and restore tests, and free disk space. `POST /api/v1/admin/ops/check` runs the checks immediately. These endpoints are for system administrators only.

## Digests and breaking-news alerts

A job checks every minute for alerts whose hold has ended, then for digests that are due. Alerts go first.

| Setting | Default | Meaning |
|---|---|---|
| `NOTIFICATION_JOBS_ENABLED` | `true` | Turns the job off, for example on a second instance |
| `ALERT_HOLD` | `PT2M` | Time to cancel an alert before it is sent |
| `MAX_ALERTS_PER_READER_PER_DAY` | `3` | Alerts one reader can get in 24 hours |
| `MAX_ALERTS_PER_DAY` | `6` | Alerts staff can create in 24 hours |

How sending works:

- **Recording:** each email is recorded in `notification_delivery` before it is handed to the mail server, so a crash or restart never sends it twice.
- **Failures:** a failed send is recorded as `FAILED` and not retried. The log names the delivery ID but no address.
- **Downtime:** if the backend was down, overdue digests go out on the next run and cover at most one period.
- **Retention:** delivery records are deleted after 30 days.
- **Development:** emails land in `.mail-outbox/`.

## Recovering from a backup

1. **Stop the backend.**
2. **Pick a backup.** Use the newest dump with a successful backup *and* a successful restore test (`GET /api/v1/admin/ops`). Check its SHA-256 against `ops_run` if the file was copied elsewhere.
3. **Keep the current database** if it still exists. Rename it rather than dropping it, for example `ALTER DATABASE newsportal RENAME TO newsportal_broken;`.
4. **Recreate the database** and its owner role as in the setup script, then restore:
   ```powershell
   $env:PGPASSWORD = '<app role password>'
   & "$env:PG_BIN_DIR\pg_restore.exe" --host=127.0.0.1 --username=<app role> --dbname=<database> `
       --no-owner --no-privileges --exit-on-error --single-transaction <backup>.dump
   ```
5. **Keep the newest deletion ledger.** Make sure `DELETION_LEDGER_FILE` is the newest ledger, not the copy stored with the backup. If the live ledger was lost, use the newest `….deletion-ledger.txt`.
6. **Start the backend.** Flyway applies any migrations newer than the backup, and the ledger is re-applied at startup, so accounts deleted after the backup are deleted again (FR-055).
7. **Check `GET /api/v1/admin/ops`**, run a manual backup, and tell users about the data-loss window if needed.

## Not covered yet

- No off-site or second-device copy of backups.
- No encryption of backup files at rest. Restrict access to `BACKUP_DIR`.
- There is no alert for purge-job failures. The privacy purge logs its errors, and they are visible in the backend log.
