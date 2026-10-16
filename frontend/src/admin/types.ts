// Shapes returned by the staff API (docs/api.md, "Staff API").

export const CONTENT_USES = [
  'METADATA',
  'FULL_TEXT_DISPLAY',
  'STORAGE',
  'TRANSLATION',
  'SUMMARY',
  'NARRATION',
  'VIDEO_EMBED',
  'GENERATED_MEDIA',
] as const
export type ContentUse = (typeof CONTENT_USES)[number]
export type RightsStatus = 'UNKNOWN' | 'PERMITTED' | 'DENIED' | 'DISPUTED'
export type SourceStatus = 'PROPOSED' | 'ACTIVE' | 'SUSPENDED' | 'RETIRED'
export type FeedHealth = 'FAILING' | 'STALE' | 'RECOVERING' | 'RIGHTS_RESTRICTED' | 'PENDING' | 'HEALTHY' | 'DISABLED'

export interface RightsView {
  use: ContentUse
  status: RightsStatus | null
  validFrom: string | null
  expiresAt: string | null
  conditions: string | null
  suspended: boolean
  suspendedReason: string | null
  allowed: boolean
  reason: string
}

export interface FeedView {
  id: number
  feedUrl: string
  refreshIntervalMinutes: number
  enabled: boolean
  health: FeedHealth
  lastOutcome: string | null
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  consecutiveFailures: number
  nextRunAt: string
}

export interface SourceView {
  id: number
  code: string
  name: string
  homepageUrl: string
  primaryLanguage: string
  status: SourceStatus
  statusReason: string | null
  termsUrl: string | null
  termsSummary: string | null
  rights: RightsView[]
  feeds: FeedView[]
}

export interface ReviewView {
  id: number
  use: ContentUse | null
  action: string
  previousStatus: string | null
  newStatus: string | null
  validFrom: string | null
  expiresAt: string | null
  reviewer: string
  notes: string
  evidenceUrl: string | null
  nextReviewDue: string | null
  reviewedAt: string
}

export interface SourceDetail {
  source: SourceView
  reviews: ReviewView[]
}

export interface JobView {
  id: number
  startedAt: string
  finishedAt: string
  outcome: string
  httpStatus: number | null
  itemsSeen: number
  itemsCreated: number
  itemsUpdated: number
  itemsUnchanged: number
  itemsQuarantined: number
  diagnostic: string | null
}

export interface QuarantineView {
  reason: string
  entryGuid: string | null
  entryLink: string | null
  diagnostic: string | null
}

export interface FeedHealthRow {
  feedId: number
  feedUrl: string
  sourceId: number
  sourceCode: string
  sourceName: string
  sourceStatus: SourceStatus
  health: FeedHealth
  lastOutcome: string | null
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  consecutiveFailures: number
  nextRunAt: string
}

export const STAFF_ROLES = ['SYSTEM_ADMIN', 'SOURCE_MANAGER', 'CONTENT_REVIEWER', 'ANALYST'] as const
export type StaffRole = (typeof STAFF_ROLES)[number]

export interface StaffView {
  id: number
  email: string
  status: string
  roles: StaffRole[]
  lastLoginAt: string | null
  createdAt: string
}

export interface AuditEntry {
  id: number
  actorUserId: number | null
  actorEmail: string | null
  action: string
  targetType: string | null
  targetId: string | null
  detail: string | null
  createdAt: string
}

export interface OpsRun {
  kind: string
  startedAt: string
  finishedAt: string
  outcome: 'SUCCESS' | 'FAILED'
  fileName: string | null
  sizeBytes: number | null
  sha256: string | null
  detail: string | null
}

export interface OpsAlert {
  key: string
  status: 'OPEN' | 'RESOLVED'
  message: string
  firstSeenAt: string
  lastSeenAt: string
  resolvedAt: string | null
}

export interface OpsStatus {
  openAlerts: OpsAlert[]
  recentlyResolvedAlerts: OpsAlert[]
  backups: OpsRun[]
  restoreTests: OpsRun[]
  backupDir: string
  backupDiskFreeMb: number | null
}

export interface RunResult {
  kind: string
  success: boolean
  fileName: string | null
  sizeBytes: number | null
  detail: string | null
}

export const REPORT_RESOLUTIONS = [
  'FIXED',
  'PUBLISHER_NOTIFIED',
  'NO_ACTION_NEEDED',
  'DUPLICATE_REPORT',
  'NOT_REPRODUCIBLE',
] as const

export interface StaffReport {
  id: number
  reference: string
  type: string
  status: 'RECEIVED' | 'UNDER_REVIEW' | 'RESOLVED'
  resolution: string | null
  articleId: number | null
  articleTitle: string | null
  sourceCode: string | null
  reporterType: string
  notifyMe: boolean
  description: string
  pageUrl: string | null
  uiLanguage: string | null
  assignedTo: number | null
  assignedToEmail: string | null
  createdAt: string
  updatedAt: string
  resolvedAt: string | null
  history: {
    fromStatus: string | null
    toStatus: string
    changedBy: string | null
    publicNote: string | null
    internalNote: string | null
    at: string
  }[]
}

export interface StaffReportPage {
  items: StaffReport[]
  page: number
  size: number
  hasMore: boolean
}
