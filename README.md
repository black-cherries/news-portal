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

The interface starts in English. It switches only when the reader picks another language in the header, and that choice is remembered. The home page shows news in the interface language first; chips switch to another language or to all of them.

- Interface text is in `frontend/src/i18n/messages.ts`. The Hindi, Urdu and Arabic strings are drafts and need a native-speaker review before the pilot.
- A test fails if any language is missing a key or a `{placeholder}`.
- Urdu and Arabic switch the whole page to right-to-left.
- Article text always carries its own `lang` and `dir`, so mixed-language pages render correctly.
- The Urdu Nastaliq font is self-hosted, so no third-party font requests are made.

## Real news publishers

`scripts/publishers/candidates.json` lists about 20 real publishers with working RSS feeds, covering English, Hindi, Urdu and Arabic: BBC, UN News, The Hindu, The Times of India, NDTV, Amar Ujala, Express, Al Jazeera, Asharq Al-Awsat, The Guardian and DW. A public feed is not permission (FR-007), so loading them takes two steps.

1. Add them as proposed publishers. Nothing is fetched yet:
   ```powershell
   .\scripts\publishers\import-candidates.ps1
   ```
2. For each publisher, read its terms, then record your decision. You can do this in the staff console (Publishers → the publisher → Record decision, then Activate), or with:
   ```powershell
   .\scripts\publishers\approve-publishers.ps1 -Codes bbc-news,un-news-en -Uses metadata,image `
       -Notes "What you reviewed and when" -IReviewedTheTerms
   ```
   This records the decision in your name, in the review history and audit log. Grant only the uses the terms allow; `metadata` means headline, summary and link.

Approved feeds are fetched within a minute or two and then every 30 minutes.

## Translation (Claude via Spring AI)

Readers can translate an article into English, Hindi, Urdu or Arabic from the article page ("Read in … · Translate"). This is a machine translation and is labelled as one.

- **Turn it on:** put `ANTHROPIC_API_KEY=…` in `.env` (git-ignored) and restart the backend. Without a key, translation is off and the rest of the app works as before.
- **Publisher permission:** a publisher's articles can be translated only when it has the `TRANSLATION` permission. The body is translated only where full-text display is also allowed.
- **Cost:** each article is translated once per language and then cached. Only signed-in readers can start a new translation. There are limits per reader per hour and per day across the app: `TRANSLATION_MAX_PER_READER_PER_HOUR` and `TRANSLATION_MAX_PER_DAY`.
- **Model:** `TRANSLATION_MODEL` sets the model; the default is `claude-sonnet-5-5`.

## Images

Lead images from publisher feeds appear on cards and article pages only where the publisher has the `IMAGE` permission (Staff console → publisher → rights). Images load straight from the publisher's server, over HTTPS and without a referrer.

## Videos

- Videos named in a publisher's feed are shown under the article.
- They are embedded only when the publisher's `VIDEO_EMBED` permission is valid and the host is YouTube, Vimeo or a direct HTTPS video file. Otherwise the app links to the original.
- Nothing loads from the video host until the reader presses Play (FR-057).

## Installable app and offline reading

- **Install:** the production build is an installable PWA (`public/manifest.webmanifest`, `public/sw.js`). Browsers allow install and service workers only over HTTPS or on `localhost`. The service worker is registered only in production builds.
- **Service worker:** it caches the app's code and icons, never API responses.
- **Downloads:** readers and guests can save articles for offline reading. Downloads are stored in IndexedDB on the device and aren't linked to the account. They keep full text only where the publisher allows full-text display *and* storage. Otherwise they keep the headline, summary and source link.
- **Syncing:** at start-up and on reconnect, the app asks `GET /api/v1/articles/offline-status` what each download may keep. It removes or reduces copies as needed. A copy not checked for 14 days, or whose permission has ended, is hidden until the device is back online.
- **Downloads page:** shows storage use, a manual check and "remove all".
- **Icons:** generated by `node scripts/make-icons.mjs` in `frontend/`.

## Accounts and email

- In development, emails such as verification and password-reset links are written as text files to `.mail-outbox/` in the repo root. That folder is git-ignored because the links inside are live.
- Create the first system administrator by setting `NEWSPORTAL_BOOTSTRAP_ADMIN_EMAIL` and `NEWSPORTAL_BOOTSTRAP_ADMIN_PASSWORD` (in `.env` or the environment) and starting the backend once. Then remove both variables.
- Staff accounts can't be created through public registration.
- Sessions are kept in server memory, so restarting the backend signs everyone out.
- Readers can turn on daily or weekly email digests and breaking-news alerts under Settings. Both are off by default. Content reviewers choose breaking-news alerts in the staff console. See [docs/api.md](docs/api.md) for the rules and [docs/operations.md](docs/operations.md) for the settings.

## Staff console

Staff accounts sign in normally, then open `/admin`. Each screen shows only what the account's roles allow, and the backend enforces the same rules.

| Screen | Roles |
|---|---|
| Dashboard, Publishers, Source health | Any staff; changes need `SOURCE_MANAGER` |
| Reports | `CONTENT_REVIEWER`; `ANALYST` sees counts on the dashboard |
| Alerts (breaking news) | `CONTENT_REVIEWER` |
| Staff, Operations, Audit log | `SYSTEM_ADMIN` |

The console is English only for now and always left-to-right. Every change asks for a reason, which is recorded in the review history and the audit log.

## Account deletion and backups

- Deleted accounts are purged 7 days after the request (`DELETION_RECOVERY_PERIOD`).
- Each purged user id is appended to `data/deletion-ledger.txt` (`DELETION_LEDGER_FILE`). The file holds ids only, no personal data.
- Keep this file out of the database's restore path and include it in your backups. After restoring an older database, start the backend: it purges any listed account again (FR-055).

## Database migrations

- Migrations live in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`.
- Never edit a migration that has already been applied to any environment. Add a new one instead.
- Back up the database before any destructive migration.
