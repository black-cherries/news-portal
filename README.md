# News Portal

Multilingual news platform (English, Hindi, Urdu, Arabic). The approved requirements are in [docs/SRS.md](docs/SRS.md).

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot 4.1 REST API (`/api/v1`), Java 25, Maven, Flyway, PostgreSQL |
| `frontend/` | React 19 + TypeScript, Vite |
| `scripts/` | PowerShell helper scripts |
| `docs/` | Requirements and design documents |

The stack is Java and TypeScript only. Do not add Python services or scripts.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 | `java -version` |
| Node.js | 24 | `node -v` |
| PostgreSQL | 17 | Listens on `127.0.0.1:5432` only |

Maven doesn't need a separate install. Use the wrapper (`mvnw.cmd`) in `backend/`.

On this machine, caches live on D: because C: has little free space:

- Maven: `MAVEN_USER_HOME=D:\dev-cache\m2`, and `~/.m2/settings.xml` sets `localRepository` to `D:/dev-cache/m2/repository`.
- npm: `npm config set cache D:\dev-cache\npm`.

## First-time setup

1. Install PostgreSQL 17. Keep the superuser password in a file outside the repo; the default path is `D:\dev-secrets\pg-superuser.txt`.
2. Create the dev and test databases and generate `.env`:

   ```powershell
   .\scripts\db\setup-local-db.ps1
   ```

   The script creates `newsportal_dev` and `newsportal_test`, each owned by a least-privilege role of the same name. It writes their credentials to `.env` in the repo root. `.env` is git-ignored. See `.env.example` for the variables.
3. Install the frontend dependencies:

   ```powershell
   cd frontend; npm install
   ```

## Run

Start the backend and the frontend in two terminals:

```powershell
cd backend; .\mvnw.cmd spring-boot:run
```

```powershell
cd frontend; npm run dev
```

- Backend: http://127.0.0.1:8081/api/v1/health returns `{"status":"UP"}`. The port is 8081 because Open WebUI already uses 8080 on this machine. Set `SERVER_PORT` to change it.
- Frontend: http://127.0.0.1:5173. The dev server proxies `/api` to the backend.

On startup, the backend applies Flyway migrations to the dev database.

## Test

```powershell
cd backend; .\mvnw.cmd test
cd frontend; npm test; npm run build; npm run lint
```

Backend tests use the `test` profile. The test database is **wiped and re-migrated** on every run. Never point `TEST_DB_URL` at a database whose data you need.

## Configuration and environments

| Profile | Use | Notes |
|---|---|---|
| `dev` (default) | Local development | Binds to `127.0.0.1` |
| `test` | Automated tests | Wipes and re-migrates its database on every run |
| `pilot` | Pilot/production | Needs `SERVER_ADDRESS` and the `DB_*` variables, with no defaults. Flyway `clean` is disabled. |

Hibernate never changes the schema (`ddl-auto: validate`). Only Flyway does.

## Frontend languages

- Interface text is in `frontend/src/i18n/messages.ts`. The Hindi, Urdu and Arabic strings are drafts and need a native-speaker review before the pilot.
- A test fails if any language is missing a key or a `{placeholder}`.
- Urdu and Arabic switch the whole page to right-to-left.
- Article text always carries its own `lang` and `dir`, so mixed-language pages render correctly.
- The Urdu Nastaliq font is self-hosted, so no third-party font requests are made.

## Accounts and email

- In development, emails such as verification and password-reset links are written as text files to `.mail-outbox/` in the repo root. That folder is git-ignored because the links inside are live.
- Create the first system administrator by setting `NEWSPORTAL_BOOTSTRAP_ADMIN_EMAIL` and `NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD` (in `.env` or the environment) and starting the backend once. Then remove both variables.
- Staff accounts can't be created through public registration.
- Sessions are kept in server memory, so restarting the backend signs everyone out.

## Account deletion and backups

- Deleted accounts are purged 7 days after the request (`DELETION_RECOVERY_PERIOD`).
- Each purged user id is appended to `data/deletion-ledger.txt` (`DELETION_LEDGER_FILE`). The file holds ids only, no personal data.
- Keep this file out of the database's restore path and include it in your backups. After restoring an older database, start the backend: it purges any listed account again (FR-055).

## Database migrations

- Migrations live in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`.
- Never edit a migration that has already been applied to any environment. Add a new one instead.
- Back up the database before any destructive migration.
