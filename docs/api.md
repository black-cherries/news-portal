# REST API v1

Base path: `/api/v1`. Reading endpoints (`/health`, `/articles`, `/categories`, `/publishers`) and the sign-up and sign-in endpoints are public, so guests can use them without an account (FR-037). Everything else requires a signed-in session. Responses are JSON. Times are ISO-8601 UTC strings, for example `2026-03-01T11:00:00Z`.

A reader sees an article only while its publisher is `ACTIVE` and its METADATA use is permitted, in date and not suspended. Any other article behaves as if it doesn't exist: list endpoints leave it out and the detail endpoint returns `404`.

Errors use [RFC 9457 problem details](https://www.rfc-editor.org/rfc/rfc9457) with status `400` and a short `detail` message.

## Authentication and CSRF

The browser app signs in with a session cookie (`NPSESSION`: HttpOnly, SameSite=Lax, and Secure in the pilot).

Every `POST`, `PUT`, `PATCH` and `DELETE` must carry a CSRF token:

1. Call `GET /auth/csrf` once. The response sets the `XSRF-TOKEN` cookie.
2. Send the cookie's value in the `X-XSRF-TOKEN` header with each unsafe request.
3. After sign-in or a password change the token is replaced, so read the cookie again before the next unsafe request.

| Endpoint | Body | Responses |
|---|---|---|
| `GET /auth/csrf` | | `200 {"headerName":"X-XSRF-TOKEN"}` |
| `POST /auth/register` | `{email, password, ageConfirmed, uiLanguage?}` | `202`, the same response whether or not the email already has an account. `400` for invalid input. |
| `POST /auth/verify-email` | `{token}` | `204`, or `400 INVALID_OR_EXPIRED_LINK` |
| `POST /auth/resend-verification` | `{email}` | `202` always |
| `POST /auth/login` | `{email, password}` | `200` with the user, `401 INVALID_CREDENTIALS`, or `403 EMAIL_NOT_VERIFIED` |
| `POST /auth/logout` | | `204` |
| `GET /auth/me` | | `200 {id, email, accountType, roles}`, or `401` |
| `POST /auth/password-reset/request` | `{email}` | `202` always |
| `POST /auth/password-reset/confirm` | `{token, newPassword}` | `204`, `400` for a weak password (the token stays usable), or `400 INVALID_OR_EXPIRED_LINK` |
| `POST /auth/password/change` | `{currentPassword, newPassword}` | `204`. Signs out the user's other sessions. |

Rules:

- **Passwords:** 10 to 128 characters, in any script.
- **Links in emails:** single-use. Verification links expire after 24 hours and reset links after 1 hour. The token is in the URL fragment: `/verify-email#token=…` and `/reset-password#token=…`.
- **Lockout:** 10 wrong passwords lock the account for 15 minutes.
- **Rate limits:** too many attempts from one address, or too many emails to one inbox, return `429` with a `Retry-After` header.
- **Staff endpoints:** `/admin/**` requires a staff account. Readers get `403` and anonymous callers get `401`.

## Reader API (`/me`)

Requires a signed-in reader account; staff accounts get `403`. Every item belongs to the signed-in reader. Another reader's collection answers `404`, so its existence isn't revealed (FR-035).

Saved lists only show articles the publisher's rights still allow. `hiddenCount` tells the reader how many saved items are currently hidden (FR-036). Personal lists use `page` (starting at 0) and `size` (1 to 50, default 20).

| Endpoint | Body | Notes |
|---|---|---|
| `GET /me/preferences` | | `{uiLanguage, newsLanguages, topics, digestLanguage, historyPaused}` |
| `PUT /me/preferences` | Same shape | Replaces all preferences. Languages are `en`, `hi`, `ur`, `ar`. `topics` are category codes. An empty `newsLanguages` means "same as the interface language"; empty `topics` means all topics. |
| `GET /me/for-you?cursor&size` | | Rule-based feed (FR-024) in the reader's news languages and topics, newest first. Same shape as `/articles`. |
| `GET /me/for-you/other-languages?size=5` | | A small, separately labelled sample from other languages (FR-029); at most 10 items |
| `GET /me/bookmarks?q&page&size` | | `{items:[{savedAt, article}], page, size, hasMore, hiddenCount}`. `q` searches within bookmarks. |
| `GET /me/bookmarks/{articleId}` | | `{bookmarked: true|false}` |
| `PUT /me/bookmarks/{articleId}` | | `204`. Idempotent. `404` if the article isn't visible. |
| `DELETE /me/bookmarks/{articleId}` | | `204` |
| `GET /me/collections` | | `[{id, name, itemCount, createdAt, updatedAt}]` |
| `POST /me/collections` | `{name}` | `201`. Names are 1 to 100 characters and unique per reader, ignoring case (`409` if taken). |
| `PATCH /me/collections/{id}` | `{name}` | Rename |
| `DELETE /me/collections/{id}` | | |
| `GET /me/collections/{id}/items?q&page&size` | | Same shape as bookmarks |
| `PUT` / `DELETE /me/collections/{id}/items/{articleId}` | | `204` |
| `POST /me/history` | `{articleId, kind}` | `kind` is `READ` or `LISTEN`. The app calls this when the reader opens or listens to an article. Returns `{recorded:false}` while history is paused. |
| `GET /me/history?kind&page&size` | | `{items:[{kind, lastAt, article}], page, size, hasMore, paused}`. Only the latest time per article is kept. |
| `DELETE /me/history/{articleId}?kind` | | Removes one entry, or both kinds if `kind` is omitted |
| `DELETE /me/history?kind` | | Clears all history, or one kind. Bookmarks and collections are unaffected (FR-048). |

Limits: 5,000 bookmarks, 100 collections and 1,000 items per collection (`409` when reached).

### Privacy (FR-049, FR-052 to FR-055)

| Endpoint | Body | Notes |
|---|---|---|
| `POST /me/export` | `{password}` | Downloads all of the reader's personal data as a JSON attachment: account, preferences, bookmarks, collections and history. Secrets are never included. At most 5 per hour. |
| `POST /me/account/delete` | `{password}` | `202 {deletionDueAt}`. Signs the reader out on every device immediately. |
| `POST /auth/account/restore` | `{email, password}` | Public. Cancels a pending deletion before `deletionDueAt` and returns `204`; the reader then signs in normally. Every failure returns the same `401`. |

During the recovery period (`DELETION_RECOVERY_PERIOD`, 7 days by default), signing in with the correct password returns `403 ACCOUNT_DELETION_PENDING` with `deletionDueAt`.

After the period, an hourly job deletes the account and everything it owns. Articles belong to publishers and are not affected. Each purged user id is recorded in the database and in a file outside it (`DELETION_LEDGER_FILE`). At startup, any recorded id that has reappeared, for example after restoring an older backup, is purged again.

## Reports and correction requests (FR-070 to FR-073)

| Endpoint | Who | Body | Notes |
|---|---|---|---|
| `POST /reports` | Anyone, guests included | `{type, articleId?, description, pageUrl?, notifyMe?}` | `201 {reference, status}`. Optional `X-UI-Language` header. At most 10 per hour per address, and 20 per day per reader. |
| `GET /me/reports` | Reader | | Own reports with public updates only. Internal staff notes are never included. |
| `PUT /me/reports/{reference}/notify` | Reader | `{notifyMe}` | Turns status-change emails for that report on or off |

- **Types:** `TECHNICAL`, `BROKEN_LINK`, `LANGUAGE_ERROR`, `TRANSLATION_ISSUE`, `MISSING_ATTRIBUTION`, `SUSPECTED_DUPLICATE`, `MISLEADING_WORDING`, `CORRECTION_REQUEST`.
- **Statuses:** `RECEIVED`, `UNDER_REVIEW`, `RESOLVED`.
- **Fields:** `description` is 10 to 2000 characters. `articleId` must be a visible article. `pageUrl` is an http(s) URL or an app path.
- **References:** random, for example `NP-7K3QX9M2`. A report can only be viewed by its signed-in reporter, never through a public link (FR-072).
- **Content:** reports never change an article automatically (FR-073).

## Email digests and breaking-news alerts (FR-065 to FR-069)

| Endpoint | Who | Body | Notes |
|---|---|---|---|
| `GET /me/notifications` | Reader | | `{digest, breakingAlerts, languages, topics, nextDigestAt}` |
| `PUT /me/notifications` | Reader | `{digest, breakingAlerts?, languages?, topics?}` | `digest` is `OFF`, `DAILY` or `WEEKLY` and is required. `nextDigestAt` is ignored. |
| `POST /notifications/unsubscribe` | Anyone with the link | `{token, kind?}` | `kind` is `DIGEST`, `ALERTS` or `ALL` (default). `200 {unsubscribed: true}`, or `404` for an unknown link. At most 30 per hour per address. |

- **Defaults:** everything is off until the reader turns it on (FR-066). Empty `languages` or `topics` means "same as my For You settings": news languages, or the interface language if none; feed topics, or every topic if none.
- **Digests:** sent one period after they are turned on, then daily or weekly. A digest lists articles that arrived since the previous one and that the publisher's rights still allow: up to 15 for daily, 30 for weekly. Nothing is sent when nothing matched.
- **Language (FR-068):** digest and alert emails use the reader's digest language, or the interface language if none is set. Articles keep their original title, and any article in another language is labelled with that language. The Hindi, Urdu and Arabic email texts are drafts.
- **Duplicates (FR-067):** the same headline from several publishers appears once in a digest, naming the other publishers.
- **Unsubscribe:** every notification email links to `/unsubscribe#token=…&kind=…`. The token is random and per reader, and it can only turn notifications off. The page asks before changing anything, so link scanners don't unsubscribe people. The unsubscribe endpoint keeps normal CSRF protection, so a one-click `List-Unsubscribe` header is not offered yet.
- **Other email (FR-069):** account, security and report-update emails are sent as needed and are not affected by these settings. The app sends no promotional email. Adding any would need a separate consent setting first.
- The settings and the log of notification emails sent (kind, outcome, item count and time) are part of the personal-data export. The log is kept for 30 days.

## Staff API (`/admin`)

Requires a signed-in staff account. Any staff role can read. Only `SOURCE_MANAGER` can change publishers, rights and feeds. Only `SYSTEM_ADMIN` can manage staff and read the audit log.

Every change takes a `notes` field explaining why. It's recorded in the publisher's review history (reviewer = staff email) and in the audit log (actor = staff user id).

Errors: `400` for invalid input, `403` for the wrong role, `404` when the item doesn't exist, and `409` for a change not allowed in the current state (for example, activating a publisher whose METADATA use isn't permitted).

### Publishers and rights (read: any staff; write: SOURCE_MANAGER)

| Endpoint | Body | Notes |
|---|---|---|
| `GET /admin/sources` | | Every publisher with rights and feeds. Each content use shows its stored decision and its effective `allowed`/`reason` right now. |
| `GET /admin/sources/{id}` | | Same, plus the latest 100 review entries |
| `POST /admin/sources` | `{code, name, homepageUrl, primaryLanguage, termsUrl?, termsSummary?}` | `201`. New publishers start as `PROPOSED`. |
| `PATCH /admin/sources/{id}/terms` | `{termsUrl, termsSummary, notes}` | |
| `POST /admin/sources/{id}/activate` | `{notes}` | Needs METADATA permitted and in date |
| `POST /admin/sources/{id}/suspend` | `{notes}` | Hides all of the publisher's content immediately |
| `POST /admin/sources/{id}/restore` | `{notes}` | Recorded approval; needs METADATA still valid |
| `POST /admin/sources/{id}/retire` | `{notes}` | |
| `PUT /admin/sources/{id}/rights/{use}` | `{status, validFrom?, expiresAt?, conditions?, notes, evidenceUrl?, nextReviewDue?}` | `use` is one of `metadata`, `full-text-display`, `storage`, `translation`, `summary`, `narration`, `video-embed`, `generated-media`. `status` is `PERMITTED`, `DENIED`, `DISPUTED` or `UNKNOWN`. |
| `POST /admin/sources/{id}/rights/{use}/suspend` | `{notes}` | |
| `POST /admin/sources/{id}/rights/{use}/restore` | `{notes}` | Lifts the suspension only; the rights status still applies |

### Feeds and health (read: any staff; write: SOURCE_MANAGER)

| Endpoint | Body | Notes |
|---|---|---|
| `POST /admin/sources/{id}/feeds` | `{feedUrl, refreshIntervalMinutes?}` | The interval is 5 to 1440 minutes; the default is 30 |
| `PATCH /admin/feeds/{id}` | `{enabled?, refreshIntervalMinutes?}` | |
| `POST /admin/feeds/{id}/run` | | Fetches now. Rights checks still apply. Returns the job. |
| `GET /admin/feeds/{id}/jobs?limit=20` | | Recent fetch attempts, newest first |
| `GET /admin/jobs/{id}/quarantine` | | Entries rejected in that run, with reasons |
| `GET /admin/source-health` | | All feeds, worst first. `health` is one of `FAILING`, `STALE`, `RECOVERING`, `RIGHTS_RESTRICTED`, `PENDING`, `HEALTHY`, `DISABLED`. |

Health rules:

- **`FAILING`:** 3 or more failures in a row, or failures without any success yet.
- **`STALE`:** no success for 3 refresh intervals, and at least 2 hours.
- **`RECOVERING`:** succeeding again after a failure in the last 24 hours.
- **`RIGHTS_RESTRICTED`:** the feed isn't fetched because of the publisher's rights. This is not counted as a failure.

### Staff accounts and audit (SYSTEM_ADMIN)

| Endpoint | Body | Notes |
|---|---|---|
| `GET /admin/staff` | | |
| `POST /admin/staff` | `{email, roles}` | Emails an invitation; the person sets their own password through `/reset-password`. The link is valid for 72 hours. |
| `PUT /admin/staff/{id}/roles` | `{roles}` | Takes effect on the person's next request |
| `POST /admin/staff/{id}/disable` | | Ends the person's sessions immediately |
| `POST /admin/staff/{id}/enable` | | |
| `GET /admin/audit?limit=50&before={id}` | | Newest first |

### Reports (CONTENT_REVIEWER; counts also for ANALYST)

| Endpoint | Body | Notes |
|---|---|---|
| `GET /admin/reports?status&type&mine&page&size` | | Newest first. Reporters appear only as `READER` or `GUEST_OR_DELETED`, never by identity. |
| `GET /admin/reports/{id}` | | Includes the full history, with internal notes |
| `POST /admin/reports/{id}/assign` | `{assigneeId?}` | Assigns to an active content reviewer; with no ID, to yourself |
| `POST /admin/reports/{id}/status` | `{status, resolution?, publicNote?, internalNote?}` | Moves to `UNDER_REVIEW` or `RESOLVED`; resolving needs a `resolution` (`FIXED`, `PUBLISHER_NOTIFIED`, `NO_ACTION_NEEDED`, `DUPLICATE_REPORT`, `NOT_REPRODUCIBLE`). Emails the reporter with the public note if they asked for updates. |
| `GET /admin/reports/stats` | | Counts by status and type, plus open reports older than 7 days. Analysts can use this one. |

All changes are written to the audit log. When a reporter's account is purged, the report remains, without the link to them (FR-054).

### Breaking-news alerts (CONTENT_REVIEWER)

Alerts are chosen by staff. Nothing is sent automatically, because related-story detection is a later phase (SRS §3.2). Syndicated copies therefore never count as confirmation of a story (FR-067).

| Endpoint | Body | Notes |
|---|---|---|
| `GET /admin/alerts?page&size` | | Newest first |
| `GET /admin/alerts/{id}` | | |
| `POST /admin/alerts` | `{articleId, reason}` | `201`, status `QUEUED`. `404` if the article isn't shown to readers. `409` if this article already has an alert, if the same headline had one in the last 24 hours, or if the app-wide limit is reached. |
| `POST /admin/alerts/{id}/cancel` | `{reason}` | Only while `QUEUED`; otherwise `409` |

- **Statuses:** `QUEUED`, `SENDING`, `SENT`, `CANCELLED`.
- **Hold:** an alert waits `ALERT_HOLD` (default 2 minutes) before it is sent, so a mistake can be cancelled.
- **Recipients:** readers who turned alerts on and whose notification languages and topics match the article.
- **Rights:** they are checked again just before sending. If the article is no longer shown, the alert is cancelled automatically.
- **Limits:** each reader gets at most `MAX_ALERTS_PER_READER_PER_DAY` alerts (default 3) in 24 hours. Readers over the limit are skipped and counted in `skippedCount`. Staff can create at most `MAX_ALERTS_PER_DAY` alerts (default 6) in 24 hours.
- **Audit:** creating and cancelling alerts are written to the audit log. The reason is for staff only and never appears in the email.

### Operations (SYSTEM_ADMIN)

See [operations.md](operations.md) for how backups, restore tests and alerts work.

| Endpoint | Notes |
|---|---|
| `GET /admin/ops` | Open and recently resolved alerts, recent backups and restore tests, backup folder and free disk space |
| `POST /admin/ops/backup` | Runs a backup now and returns `{kind, success, fileName, sizeBytes, detail}` |
| `POST /admin/ops/restore-test` | Restores the newest backup into the scratch database and verifies it |
| `POST /admin/ops/check` | Runs the monitoring checks now and returns the current problems, keyed by alert key |

Roles are `SYSTEM_ADMIN`, `SOURCE_MANAGER`, `CONTENT_REVIEWER` and `ANALYST`. Administrators can't remove their own `SYSTEM_ADMIN` role or disable themselves, and the last active `SYSTEM_ADMIN` can't be removed or disabled.

## GET /health

Returns `{"status":"UP"}` with `200`, or `{"status":"DOWN"}` with `503`.

## GET /articles

Returns the Latest feed, newest first (FR-023), with filters (FR-028) and keyword search (FR-031, FR-032).

| Param | Example | Notes |
|---|---|---|
| `lang` | `en,ur` | Article language codes, comma-separated |
| `category` | `politics,sports` | App category codes (see `/categories`) |
| `source` | `example-news` | Publisher codes (see `/publishers`) |
| `from`, `to` | `2026-02-01` | Whole UTC days, both inclusive |
| `q` | `election results` | Keyword search over title and excerpt, at most 200 characters. Supports `"exact phrase"`, `or` and `-exclude`. |
| `size` | `20` | 1 to 50; the default is 20 |
| `cursor` | from `nextCursor` | Gets the next page |

The feed is ordered by publication time. Two exceptions:

- An article without a publication time is placed at its ingestion time.
- An article can never sort ahead of the time we first saw it, so future-dated items can't pin themselves to the top.

```json
{
  "items": [{
    "id": 42,
    "title": "…",
    "excerpt": "…",
    "publisher": {"code": "example-news", "name": "Example News", "homepageUrl": "https://…"},
    "sourceUrl": "https://publisher.example/story",
    "language": "ur",
    "direction": "rtl",
    "publishedAt": "2026-02-21T09:00:00Z",
    "publisherUpdatedAt": null,
    "ingestedAt": "2026-02-21T09:05:00Z",
    "categories": ["politics"],
    "imageUrl": "https://cdn.publisher.example/lead.jpg"
  }],
  "nextCursor": "MjAyNi0w…"
}
```

`publishedAt` and `publisherUpdatedAt` are what the publisher reported, and are `null` when unknown. `ingestedAt` is when this app first stored the article (FR-022). `nextCursor` is `null` on the last page.

`imageUrl` is the publisher's lead image from the feed: `media:thumbnail`, an image `media:content` or an image enclosure, HTTPS only. It is present only while the publisher's `IMAGE` permission is valid, and `null` otherwise. Only the link is stored. The web app loads images from the publisher's server without a referrer. `GET /articles/{id}` returns the same field.

## GET /articles/offline-status?ids=1,2,3

Tells a device what it may keep for offline reading (FR-061, FR-063, FR-064). Public, because guests can download too. At most 200 IDs.

```json
[{ "id": 1, "level": "FULL_TEXT", "validUntil": "2026-11-15T00:00:00Z" },
 { "id": 2, "level": "METADATA",  "validUntil": "2026-11-24T12:00:00Z" },
 { "id": 3, "level": "NONE",      "validUntil": null }]
```

- **Levels:** `FULL_TEXT` needs both `FULL_TEXT_DISPLAY` and `STORAGE`, plus stored text. `METADATA` means keep the title, excerpt and source link only. `NONE` means delete the download: the article isn't shown to readers or doesn't exist.
- **validUntil:** the earliest expiry among the rights involved, and never more than 14 days ahead. The app hides a download after this time until it has checked again.
- **What counts:** only rights and publisher status. A failing feed doesn't affect downloads (FR-064).

## GET /articles/{id}

Returns the same fields as a list item, plus:

| Field | Notes |
|---|---|
| `author` | Publisher-supplied; may be `null` |
| `lastSeenAt` | When the article last appeared in the publisher's feed |
| `publisherCategories` | The publisher's own labels, kept separate from app categories (FR-027) |
| `fullTextStatus` | `AVAILABLE`, `NOT_PERMITTED` or `NOT_PROVIDED` (FR-018, FR-019) |
| `fullTextHtml` | Sanitized HTML, present only when `fullTextStatus` is `AVAILABLE` |
| `fullTextNarrationPermitted` | `true` when text-to-speech may read the full text. When `false`, only the title and excerpt may be read aloud (FR-059). |
| `videos` | `[{sourceUrl, provider, embedUrl, mimeType}]`, in feed order. Empty when the feed names no video. |

When full text isn't available, clients show the excerpt and link to `sourceUrl`.

### Videos (FR-056, FR-057)

- **Sources:** ingestion takes up to 5 video links per entry from enclosures, Media RSS (`media:content`, `media:group`, `media:player`) and entry links that are YouTube or Vimeo pages. Only the link is stored.
- **provider:** `YOUTUBE`, `VIMEO`, `FILE` (an MP4, WebM or Ogg file over HTTPS) or `LINK` (anything else).
- **embedUrl:** set only when the publisher's `VIDEO_EMBED` permission is valid right now and the provider is known. YouTube uses the `youtube-nocookie.com` player, and Vimeo uses `dnt=1`. Otherwise `embedUrl` is `null` and clients link to `sourceUrl`.
- **Loading:** the web app loads an embed only after the reader presses Play, and says first which site the video comes from. Iframes are sandboxed.
- **Offline copies:** keep video links only.

## GET /categories

Returns the active app categories in display order, for example `[{"code":"world"}, …]`. Clients localize the names.

## GET /publishers

Returns the publishers currently visible to readers: `[{"code","name","homepageUrl"}]`.
