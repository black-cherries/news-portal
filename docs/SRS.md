# Multilingual News Platform — Software Requirements Specification

**Status:** Approved 2026-10-16, with the Spring AI amendment in §6, §8 and §18.

# 1. Executive Summary

The purpose of this project is to develop a multilingual news platform that collects news from approved publishers and presents it through a clean, accessible reading experience.

The application will let readers browse news, search articles, filter by language and category, follow source links, save bookmarks, organize personal collections, manage reading history, and listen to eligible article content using text-to-speech.

The platform will initially support English, Hindi, Urdu and Arabic. Urdu and Arabic must have correct right-to-left layouts.

The project will prioritize reliable news ingestion, publisher attribution, content rights, privacy, accessibility, and security before adding generative AI.

The architecture will support future AI translations, summaries, semantic search, news question-answering and an AI anchor/avatar experience. These are not required for the initial MVP.

The initial pilot will target approximately 10–30 users, beginning on a trusted local network. Public internet access will follow only after appropriate security, operational and legal reviews.

# 2. Product Goals

The system shall:

1. Aggregate news from a small, reviewed set of legitimate publishers.
2. Respect copyright, publisher terms, licences and content-retention restrictions.
3. Provide a multilingual, responsive reading experience.
4. Support guests as well as registered readers.
5. Offer search, bookmarks, personal collections and user-controlled history.
6. Provide reliable ingestion, duplicate handling, source-health monitoring and corrections.
7. Support authorized video links/embeds and eligible text-to-speech.
8. Provide privacy controls and secure account deletion.
9. Establish a modular foundation for future AI capabilities and mobile applications.
10. Remain economical by preferring suitable free and open-source technologies.

Success does not depend on maximizing the number of articles. A smaller, reliable and rights-reviewed catalogue is preferable to a larger unauthorized catalogue.

# 3. Scope

## 3.1 MVP — included

- React responsive web application and installable PWA
- Java/Spring Boot REST backend
- PostgreSQL database and Flyway migrations
- Guest browsing
- Email registration, verification and password recovery
- Role-based staff authorization
- Approved RSS feeds and selected authorized APIs
- Article metadata and permitted full-text content
- Source attribution and publisher links
- English, Hindi, Urdu and Arabic UI support
- Right-to-left layouts for Urdu and Arabic
- Latest and For You feeds
- Keyword search and basic filters
- Language and category preferences
- Bookmarks and private personal collections
- User-controlled reading/listening history
- Article reading interface
- Authorized publisher video links/embeds
- Browser-based TTS for eligible content
- Basic source and rights administration
- Structured user reports and correction requests
- Operational monitoring and automated daily backups
- Privacy controls and account-deletion workflow
- Development, staging and pilot environments

## 3.2 Later phases

- AI-generated summaries and translations
- Semantic search and source-grounded news Q&A
- Advanced related-story and cross-language duplicate detection
- Automated AI quality evaluation and human review workflows
- Advanced editorial console and configurable approval chains
- More detailed editorial analytics
- Secure public deployment and increased capacity
- Native Android and iOS applications
- AI presenter/avatar and generated news videos
- Advertising integration

## 3.3 Out of scope for the initial MVP

- Training a new foundation model
- Automatically translating every article into every language
- Unauthorized copying or permanent storage of publisher articles
- Paywall or access-control circumvention
- Native mobile apps
- AI-generated avatar videos
- Social networks or public sharing of personal collections
- Unrestricted public APIs for redistributing publisher content
- Advertising-network integration

# 4. Users and Roles

## 4.1 Guest reader

Guests may browse and search available news without registering. They can use permitted article content, publisher links, available filters and eligible media features.

Guest preferences and temporary history may be stored locally or temporarily, with clear privacy behaviour. Guest data must not be represented as synchronized account data.

## 4.2 Registered reader

Registered readers can:

- Save and organize bookmarks
- Create private, named collections
- Synchronize preferences across sessions/devices
- Review and control reading/listening history
- Choose news languages and topics
- Select a digest language
- Request personal-data exports
- Manage notification consent
- Request account deletion
- Submit and track reports

Registered accounts are intended for users aged 16 and above, subject to applicable law. Guest access remains available where lawful.

## 4.3 Staff roles

| Role | Permissions |
|---|---|
| System administrator | Users, roles, security, configuration and overall operations |
| Source manager | Publisher configuration, rights reviews, source approvals and ingestion settings |
| Content reviewer | Assigned reports, corrections and future AI-content reviews |
| Read-only analyst | Permitted reporting and analytics without editing |

Permissions must be enforced on the backend, not merely hidden in the frontend. Reader accounts and staff accounts must be separate in authorization and privilege.

# 5. Functional Requirements

## 5.1 Publisher and rights management

**FR-001:** The application shall use RSS feeds first and add authorized APIs where appropriate.

**FR-002:** The initial catalogue shall target approximately 3–5 publishers that have passed a rights and technical review.

**FR-003:** Each publisher shall have a record of source terms, permitted uses, review dates, expiry dates and responsible reviewer.

**FR-004:** Permissions shall be recorded separately, where applicable, for metadata, full-text display, storage, translation, summaries, narration, video embedding and generated media.

**FR-005:** Unknown, expired or disputed permissions shall block the affected use until reviewed.

**FR-006:** Source managers shall be able to suspend a source or a particular use and restore it only after approval.

**FR-007:** RSS/API availability shall not be treated as proof of permission to copy, translate, narrate or retain full content.

## 5.2 News ingestion

**FR-008:** The application shall collect permitted source metadata and content using configured connectors.

**FR-009:** Initial refresh intervals shall be configurable. Approximately 15–30 minutes may be used for major sources where source terms and rate limits allow; other sources may refresh less frequently.

**FR-010:** Every ingestion job shall record attempts, outcomes, timestamps and limited diagnostic details.

**FR-011:** Repeated retrieval of the same article shall update the existing record rather than create another duplicate.

**FR-012:** Canonical URL normalization and publisher identifiers shall be used when available. Distinct publishers' reports shall remain independent.

**FR-013:** Invalid feed records shall be quarantined with a reason and limited diagnostic metadata. Valid records in the same feed shall continue to be processed.

**FR-014:** Source failures shall be isolated. Retries shall use bounded backoff and shall not create unlimited requests.

**FR-015:** The system shall distinguish a valid feed with no new articles from a failed feed, malformed response, authentication failure, rate limit or inaccessible publisher page.

**FR-016:** The system shall retain permitted metadata and source links according to the retention policy. Full text shall be retained only as permitted.

## 5.3 Article display

**FR-017:** Every article page shall show the publisher, source link, article language and available publication/update timestamps.

**FR-018:** Full-text display shall be enabled only when permitted.

**FR-019:** When full text is unavailable, the app shall display permitted metadata or excerpts and a source link, with a clear explanation where useful.

**FR-020:** The app shall distinguish a removed article, subscription restriction, inaccessible publisher page and ingestion failure when the information is available.

**FR-021:** Publisher corrections shall be attributed to the publisher and displayed clearly. The application shall not imply independent verification merely because it displays a correction.

**FR-022:** Publication time, publisher-reported update time and ingestion time shall be stored separately. Unknown timestamps shall remain unknown.

## 5.4 Feeds and categories

**FR-023:** The application shall provide a chronological **Latest** feed.

**FR-024:** The application shall provide a rule-based **For You** feed based on user-selected languages and topics.

**FR-025:** Users shall be able to switch between Latest and For You.

**FR-026:** The initial category set shall include World News, National News, Politics, Business, Technology, Science, Health, Education, Sports, Entertainment, Environment and Lifestyle.

**FR-027:** Publisher categories and normalized app categories shall be stored separately. Category assignment shall initially use publisher metadata and transparent keyword/rule mappings. Uncertain articles may remain uncategorized for review.

**FR-028:** Users shall be able to filter by language, category, publisher and date range.

**FR-029:** The main feed shall follow the user's preferred languages, with a small, clearly labelled section for other languages.

**FR-030:** Related stories may be linked, but separate reports shall not be merged into one article. A genuine translation shall be distinguished from separate reporting.

## 5.5 Search and saved content

**FR-031:** Initial search shall use PostgreSQL full-text search over permitted indexed content.

**FR-032:** Search shall support keyword, date range, publisher, language, category and saved-item filtering.

**FR-033:** Semantic search and pgvector shall be considered in a later phase.

**FR-034:** Registered readers shall be able to bookmark articles and create private, named collections.

**FR-035:** Collection and bookmark records shall belong to the authenticated user. Users shall not be able to access another user's private content.

**FR-036:** Search indexes, caches and saved views shall respect content-rights restrictions. Restricted content shall not remain accessible through stale indexes or caches.

## 5.6 Accounts and authentication

**FR-037:** Guest browsing shall not require an account.

**FR-038:** Registered accounts shall use email and password in the MVP.

**FR-039:** Email verification shall be required before account activation and synchronized account features become available.

**FR-040:** Account states shall include at least pending verification and active. Verification tokens shall be single-use and expire.

**FR-041:** Password recovery, verification resends and login attempts shall be protected against abuse.

**FR-042:** Error messages and response behaviour shall avoid unnecessarily revealing whether an email address has an account.

**FR-043:** The app shall provide secure password hashing, session/cookie controls, appropriate CSRF protection, and HTTPS for non-local deployments.

**FR-044:** The intended minimum age for registered accounts shall be 16+, subject to launch-market legal review. The implementation shall not assume that a self-declaration alone satisfies every legal requirement.

## 5.7 User preferences, history and privacy

**FR-045:** Users shall have separate settings for interface language, preferred news languages and email digest language.

**FR-046:** Users shall be able to view and correct their profile and preferences.

**FR-047:** Registered users shall be able to review, delete and clear reading/listening history and optionally pause history tracking.

**FR-048:** History shall remain distinct from bookmarks and collections.

**FR-049:** Users shall be able to request a copy of their eligible personal data. Exports must include only the requesting user's information.

**FR-050:** Optional tracking, promotional communications and non-essential data processing shall be disabled by default. Required service processing shall be explained separately.

**FR-051:** Individual reading behaviour shall not be used for analytics by default. Necessary operational monitoring and appropriately protected aggregate analytics may be used.

**FR-052:** The application shall provide an account-deletion workflow. Account access shall be deactivated immediately when deletion begins, followed by a configurable recovery period; seven days is the proposed starting value.

**FR-053:** After the recovery period, eligible personal data shall be deleted or anonymized. Legal retention exceptions shall be limited, documented and access-restricted.

**FR-054:** When an open case exists, account data shall still be deleted or anonymized as appropriate. Only minimum legally or operationally necessary case records may be retained, with minimized identifiers and restricted access.

**FR-055:** Backup retention and restoration procedures shall account for eligible deletion requests.

## 5.8 Video, audio and offline use

**FR-056:** Video shall use authorized publisher embeds or links to original videos. If embedding is not allowed, the app shall provide a source link.

**FR-057:** Third-party embeds shall be handled with privacy in mind. Where practical, they should load only after the user chooses to view them.

**FR-058:** Initial text-to-speech shall use browser-native capabilities where suitable voices are available.

**FR-059:** Full-text narration shall occur only where permitted. Otherwise, only permitted excerpts or metadata may be narrated.

**FR-060:** Audio controls shall identify the correct article language and fail gracefully when an appropriate voice is unavailable.

**FR-061:** Offline full-text storage shall occur only where permitted. Otherwise, the app may store permitted metadata and source links.

**FR-062:** Users shall be able to manage downloads and see storage usage where supported.

**FR-063:** Restrictions affecting downloaded content shall synchronize when devices reconnect. The app shall acknowledge that disconnected devices cannot receive restrictions immediately and that PWA storage may be cleared by the browser or operating system.

**FR-064:** A source-feed outage alone shall not invalidate a previously downloaded article if offline permission remains valid.

## 5.9 Notifications and reports

**FR-065:** Registered users may opt into daily or weekly email digests and breaking-news alerts.

**FR-066:** Notifications shall be disabled by default and configurable by topic, language and frequency.

**FR-067:** The app shall limit duplicates and excessive alerts. Syndicated duplicates shall not count as independent confirmation of a developing story.

**FR-068:** Digests shall use the selected language where a permitted version exists. Otherwise, the original language shall be identified clearly.

**FR-069:** Essential account, security and service messages may be sent as needed. Promotional messages require separate consent.

**FR-070:** Users shall be able to submit structured reports for technical problems, broken links, language errors, translation issues, missing attribution, suspected duplicates, misleading app wording and correction requests.

**FR-071:** Reports shall receive a reference number and use the statuses `Received`, `Under review` and `Resolved`.

**FR-072:** Users may receive meaningful progress updates by email. Private report details shall not be exposed through guessable public URLs.

**FR-073:** Reports shall not automatically remove or rewrite publisher content.

## 5.10 Administration and monitoring

**FR-074:** Staff access shall use role-based authorization enforced by the backend.

**FR-075:** The initial admin interface shall support essential source configuration, rights review, ingestion health, user reports and audit records.

**FR-076:** Sensitive staff actions shall be auditable.

**FR-077:** The system shall monitor backend health, database connectivity, feed freshness, background-job failures, disk space and backup health.

**FR-078:** Automated email alerts shall report configured failures and prolonged source outages. Alerts shall have sensible thresholds and cooldowns.

**FR-079:** A basic admin dashboard shall show healthy, stale, failing and recovering sources, distinguishing ingestion failures from rights restrictions.

**FR-080:** Advanced editorial workflows, detailed analytics and configurable approval chains shall be deferred to later phases.

# 6. Future AI Requirements

Generative AI is not required for the MVP. The core app shall remain useful when no AI model is installed or available.

Future capabilities may include:
- Rights-approved AI summaries
- Rights-approved translations
- Semantic search and source-grounded news Q&A
- Improved related-story detection
- Advanced recommendation systems
- AI anchor/avatar video

Any AI capability shall check the applicable content rights before processing.

AI-generated content shall be labelled and linked to its source. Summaries and translations shall preserve provenance and be evaluated separately in all four languages.

Future automated quality checks shall examine meaning, names, numbers, quotations, attribution, grounding and completeness. Low-confidence, sensitive or potentially misleading outputs shall be held for human review or withheld.

If a source is materially corrected, dependent AI outputs shall be flagged for rechecking. Uncertain results shall fall back to permitted original content or source links.

Serious rights, privacy, security or safety failures may trigger immediate suspension of the affected workflow. Repeated failures shall trigger a configured pause and administrator alert.

Model or prompt changes shall be evaluated in staging using multilingual tests, approved before rollout, monitored after release and rolled back when necessary.

Local open-source models may be evaluated later. Ollama is a candidate runtime, not a confirmed choice. Hardware, model, licence, quality and resource requirements must be assessed before selection.

Spring AI is the candidate integration framework for future AI capabilities, subject to evaluation alongside model and runtime selection. Spring AI does not replace the application's own rights checks, provenance records, human review or recheck-on-correction logic. The project shall not introduce Python services; AI integration stays within the Java/Spring stack.

# 7. Non-Functional Requirements

## 7.1 Security

- Enforce authorization on the server.
- Never expose PostgreSQL directly to public clients.
- Use secure password hashing and protected sessions.
- Protect authentication and reporting endpoints against abuse.
- Keep secrets out of source control and logs.
- Apply HTTPS for public deployment.
- Separate staff and reader permissions.
- Restrict sensitive reports, exports and audit data.
- Review security before internet exposure.

## 7.2 Privacy

- Collect only data needed for defined purposes.
- Make optional tracking and promotional communication opt-in.
- Provide understandable privacy and deletion controls.
- Minimize retained data and case identifiers.
- Apply retention and deletion policies consistently to application data, indexes, caches and backups.
- Review applicable legal requirements before launch.

## 7.3 Reliability

- Isolate source failures.
- Use bounded retries and idempotent ingestion.
- Monitor job outcomes and source freshness.
- Alert on backup failure.
- Test restoration.
- Provide graceful degradation during maintenance and outages.
- Do not display stale content as fresh.

## 7.4 Performance and capacity

The first pilot should support approximately 10–30 users. The system must be benchmarked on the actual available hardware before making larger capacity claims.

Measure:
- API response times under representative load
- Search latency
- Concurrent reading sessions
- Ingestion duration and recovery
- Database resource use
- Memory and disk usage

Do not set unsupported performance guarantees before measuring a working baseline.

## 7.5 Accessibility and internationalization

- Support keyboard navigation and screen readers.
- Provide scalable text and adequate contrast.
- Test RTL layouts and mixed-script content.
- Test all four supported languages on actual devices.
- Use clear, localized error messages and controls.
- Make audio and video controls accessible.

## 7.6 Maintainability

- Separate frontend and backend projects.
- Use REST API versioning from the beginning.
- Use Flyway for database migrations.
- Keep configuration separate from source code.
- Document setup, deployment, APIs and architecture.
- Keep modules loosely coupled, especially the future AI service layer.

# 8. Proposed Architecture

The following is a logical architecture, not a final deployment diagram.

```text
Readers: Browser / PWA
          |
          v
React Frontend
          |
          v
Spring Boot REST API (/api/v1)
   |       |       |       |
   |       |       |       +-- Authentication / Authorization
   |       |       +---------- Reader Preferences / Saved Content
   |       +------------------ Search / Article Reader
   +-------------------------- Admin / Reports / Privacy Controls
          |
          v
      PostgreSQL
          ^
          |
Spring Boot Scheduled Ingestion Jobs
          |
          v
Approved RSS Feeds / Authorized Publisher APIs

Future, separately controlled modules:
AI Translation / Summaries / Semantic Search / Q&A / Avatar
   (Spring AI module, reached only through a rights-checking gateway)
```

PostgreSQL shall be the initial primary database. Object storage is not required unless a later approved feature needs permitted stored media or generated assets.

The future AI module shall be a separate Spring module or service built with Spring AI. All AI calls shall pass through a single rights-checking gateway that consults `source_rights` before any content reaches a model. If semantic search is approved, Spring AI's pgvector store may use the existing PostgreSQL instance, subject to benchmarking against the reader workload (§7.4).

# 9. Proposed Data Model

The following are logical entities; exact table names and relationships will be finalized during detailed design.

| Area | Proposed entities |
|---|---|
| Publisher management | `news_source`, `source_rights`, `source_review` |
| News catalogue | `article`, `article_content`, `category`, `article_category`, `article_relation` |
| Ingestion | `ingestion_job`, `source_health`, `quarantined_record` |
| Identity and authorization | `app_user`, `role`, `permission`, `user_role` |
| Reader features | `bookmark`, `collection`, `collection_item`, `reading_history`, `listening_history` |
| Preferences | `user_language`, `user_topic`, `digest_setting`, `notification_setting` |
| Support and editorial | `report`, `report_status_history`, `audit_log` |
| Future AI | `ai_job`, `translation`, `article_summary`, `ai_review`, `ai_version` |
| Operations | Backup/health information as appropriate to the chosen implementation |

Rights metadata and permitted content must be separated so that a rights change can restrict content without destroying records that may legally be retained.

AI outputs must be linked to the source article, provenance, model/version, creation time, rights status and review decision as appropriate.

# 10. Database and API Standards

- Use PostgreSQL and Flyway.
- Use ordered SQL migration files stored in Git.
- Never edit a migration already applied to an environment; introduce a new migration for subsequent changes.
- Disable Hibernate schema auto-update in staging and pilot environments.
- Test migrations against both clean and populated databases.
- Back up before destructive migrations.
- Document API endpoints and request/response contracts.
- Use `/api/v1/` for the initial REST API.
- Introduce a new API version when a breaking change requires it.
- Keep secrets in environment variables or ignored local configuration files.
- Include a safe `.env.example` without real credentials.
- Fail safely when required production configuration is missing.

# 11. Deployment and Environments

Maintain three logically separate environments:
1. Development
2. Testing/staging
3. Pilot/production

They may initially run on one physical machine if resource and isolation requirements permit, but must use separate configuration, databases and credentials.

Deployment stages:
1. Local development
2. Local testing
3. Trusted local-network pilot
4. Security, rights and recovery review
5. Secure internet-access pilot

Before public deployment, inspect the actual hardware, storage health, RAM, CPU, operating system, network and internet connection. Compare Windows and Linux after inspection; do not format drives or change operating systems without explicit approval.

Public access requires a security review of HTTPS, firewall rules, remote access, ISP restrictions/CGNAT, dynamic IP, uptime, backups and recovery. PostgreSQL must not be publicly exposed.

# 12. Backup and Recovery

- Perform automated daily PostgreSQL backups.
- Initially evaluate configurable retention of 7–14 days based on available storage and legal requirements.
- Alert when backups fail or become stale.
- Perform periodic restoration tests.
- Monitor backup disk capacity.
- Store deployment instructions separately from production secrets.
- Document recovery steps and the expected data-loss window.
- Apply eligible deletion requests after restoration.

A backup shall not be considered reliable until a restoration test succeeds. A separate-device or off-site backup may be added in a later phase.

# 13. Legal and Operational Dependencies

Before launch, the project must resolve or validate:

1. Publisher terms and content-use permissions.
2. Applicable privacy and personal-data obligations.
3. Minimum-age, age-assurance and child-data requirements.
4. Terms of use and privacy-policy wording.
5. Lawful data retention and deletion exceptions.
6. Permissions for translations, summaries, narration, video embeds and future generated media.
7. Email provider, verification delivery and notification delivery.
8. Secure hosting and internet connectivity.
9. Backup storage and restoration capability.
10. Any additional obligations in the intended launch jurisdictions.

The application shall provide clear, separate disclosures that distinguish publisher reporting from app-generated material, identify AI-generated outputs when introduced, explain limitations and provide complaint channels. Article pages should show relevant source attribution and notices without being overwhelmed by legal text.

This SRS does not itself establish legal compliance. Appropriate review is required before deployment.

# 14. Testing Strategy

Testing shall be layered.

## Automated tests
- Unit tests for core business rules
- Integration tests for REST APIs and PostgreSQL
- Authentication and authorization tests
- Rights enforcement and retention tests
- Ingestion, retry, deduplication and recovery tests
- Privacy export and deletion tests
- Flyway migration tests

## Manual tests
- English, Hindi, Urdu and Arabic layouts
- RTL and mixed-script behaviour
- Search, filters, bookmarks, collections and history
- Article links and publisher attribution
- Accessible controls and TTS behaviour
- Email verification and password recovery
- Account deletion and report handling
- Rights expiry and cached/offline restrictions
- Source outage and correction handling
- Backup restoration

## Pilot tests
- Representative phones, tablets and computers
- Several simultaneous readers
- Local-network connectivity and Wi-Fi isolation
- Feed freshness and failure recovery
- Disk, CPU, RAM and database use
- User feedback and usability

Critical security, content-rights, privacy and data-loss defects must be resolved before pilot release.

# 15. Measurable MVP Acceptance Criteria

The MVP shall be considered ready for the pilot only when the following can be demonstrated.

| Area | Acceptance criterion |
|---|---|
| Project setup | Frontend and backend build and run independently using documented steps |
| Database | A clean database can be initialized using Flyway migrations |
| API | Documented `/api/v1/` endpoints pass relevant automated tests |
| Guest access | Users can browse and search without registration |
| Authentication | Email verification and password recovery work securely |
| Access control | Users cannot access other users' private records or unauthorized staff functions |
| Publisher rights | Unapproved or restricted uses are blocked |
| Ingestion | Approved feeds are collected, normalized and monitored |
| Duplicate handling | Re-fetching the same article does not create duplicate records |
| Source failures | Failed feeds are reported without incorrectly treating valid empty feeds as failures |
| Reader | Attribution, source links and available timestamps are displayed correctly |
| Search | Keyword and agreed metadata filters return appropriate permitted records |
| Languages | All four interfaces are tested; Urdu/Arabic RTL works for essential screens |
| Saved content | Bookmarks, collections and user-controlled history work and remain private |
| Privacy | Users can manage preferences, review history and initiate export/deletion requests |
| Media | Only authorized embeds and permitted narration are available |
| Monitoring | Major backend, database, feed and backup failures produce appropriate alerts |
| Backups | A backup can be restored successfully in a test environment |
| Accessibility | Essential workflows can be used with keyboard navigation and readable controls |
| Release readiness | Critical rights, security, privacy and data-loss defects are closed |

Numeric performance thresholds shall be established after benchmarking the actual hardware and measuring the first working implementation.

# 16. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Insufficient publisher rights | Review each source and use before activation |
| Feeds become unavailable | Bounded retries, health monitoring, stale indicators and alerts |
| Incorrect duplicate merging | Preserve source identity; use conservative matching |
| Misleading article timestamps | Keep publication, update and ingestion times separate |
| Poor RTL or multilingual quality | Test all four languages on real devices |
| Personal-data exposure | Least privilege, server-side ownership checks, secure exports and audit records |
| Incomplete deletion | Separate case retention, defined backup expiry and deletion after restore |
| Local server failure | Tested backups, recovery instructions and later redundancy |
| Future AI errors | Rights checks, quality evaluation, human review and safe fallback |
| Hardware limitations | Inspect and benchmark before selecting models or increasing capacity |
| Unplanned cost | Identify email, domain, hosting, storage and connectivity costs before public deployment |

# 17. Delivery Roadmap

## Phase 0 — Requirements approval
Finalize and approve the SRS, resolve genuine blockers, and confirm launch dependencies.

## Phase 1 — Core MVP
Implement the project structure, database, authentication, ingestion, reader, filters, search, saved content, privacy controls, source administration, monitoring and backups.

## Phase 2 — Local pilot
Test on the local network with selected users. Validate rights, multilingual UX, security, backup restoration and resource usage.

## Phase 3 — AI features
Introduce rights-approved summaries and translations with multilingual evaluation, quality checks, human review and auditability.

## Phase 4 — Advanced platform
Consider semantic search, source-grounded Q&A, expanded editorial workflows, more publishers/languages, public scaling, native mobile apps and AI avatar features.

Each phase requires its own acceptance review. Later features must not compromise the rights, privacy and reliability of the core platform.

# 18. Assumptions and Remaining Decisions

The following are not yet final implementation choices:

- Exact publishers and their permitted content uses
- Final source list and source-specific refresh schedules
- Actual server hardware, operating system and available capacity
- Email delivery provider
- Public hosting, domain and remote-access approach
- Final retention periods where law or publisher terms require more specific rules
- Final launch jurisdictions and legal review
- Numeric performance targets after benchmarking
- Future AI models, runtime, hardware and evaluation thresholds
- AI integration framework (Spring AI is the candidate; confirm version and Spring Boot compatibility at Phase 3)
- Exact database schema, endpoint contracts and UI wireframes

These should be resolved only when necessary for the relevant design or implementation phase. They should not trigger another broad discovery cycle before the SRS can be reviewed.

# 19. Approval Gate

This document is the approved baseline for the multilingual news platform.

**Approval record:** Approved by the project owner on 2026-10-16, including the Spring AI amendment (§6, §8, §18). Phase 1 implementation is authorized.

Implementation shall proceed in small, verifiable steps with expected outcomes, tests and rollback guidance.
