// Shapes returned by the backend REST API (see docs/api.md).

export interface Publisher {
  code: string
  name: string
  homepageUrl: string
}

export interface ArticleSummary {
  id: number
  title: string
  excerpt: string | null
  publisher: Publisher
  sourceUrl: string
  language: string
  direction: 'ltr' | 'rtl'
  publishedAt: string | null
  publisherUpdatedAt: string | null
  ingestedAt: string
  categories: string[]
}

export interface ArticlePage {
  items: ArticleSummary[]
  nextCursor: string | null
}

export type FullTextStatus = 'AVAILABLE' | 'NOT_PERMITTED' | 'NOT_PROVIDED'

export interface ArticleDetail extends ArticleSummary {
  author: string | null
  lastSeenAt: string
  publisherCategories: string[]
  fullTextStatus: FullTextStatus
  fullTextHtml: string | null
  fullTextNarrationPermitted: boolean
  videos: Video[]
}

/**
 * A publisher's video (FR-056). embedUrl is set only when embedding is
 * permitted and the provider is known; otherwise link to sourceUrl.
 */
export interface Video {
  sourceUrl: string
  provider: 'YOUTUBE' | 'VIMEO' | 'FILE' | 'LINK'
  embedUrl: string | null
  mimeType: string | null
}

export interface Me {
  id: number
  email: string
  accountType: 'READER' | 'STAFF'
  roles: string[]
}

export interface Preferences {
  uiLanguage: string
  newsLanguages: string[]
  topics: string[]
  digestLanguage: string | null
  historyPaused: boolean
}

export type DigestFrequency = 'OFF' | 'DAILY' | 'WEEKLY'

/** Empty languages/topics mean "same as my feed settings". nextDigestAt is read-only. */
export interface NotificationSettings {
  digest: DigestFrequency
  breakingAlerts: boolean
  languages: string[]
  topics: string[]
  nextDigestAt: string | null
}

export interface SavedItem {
  savedAt: string
  article: ArticleSummary
}

export interface SavedPage {
  items: SavedItem[]
  page: number
  size: number
  hasMore: boolean
  hiddenCount: number
}

export interface Collection {
  id: number
  name: string
  itemCount: number
  createdAt: string
  updatedAt: string
}

export const REPORT_TYPES = [
  'TECHNICAL',
  'BROKEN_LINK',
  'LANGUAGE_ERROR',
  'TRANSLATION_ISSUE',
  'MISSING_ATTRIBUTION',
  'SUSPECTED_DUPLICATE',
  'MISLEADING_WORDING',
  'CORRECTION_REQUEST',
] as const
export type ReportType = (typeof REPORT_TYPES)[number]
export type ReportStatus = 'RECEIVED' | 'UNDER_REVIEW' | 'RESOLVED'

export interface MyReport {
  reference: string
  type: ReportType
  status: ReportStatus
  articleId: number | null
  articleTitle: string | null
  description: string
  notifyMe: boolean
  createdAt: string
  updatedAt: string
  updates: { status: ReportStatus; note: string | null; at: string }[]
}

export type HistoryKind = 'READ' | 'LISTEN'

export interface HistoryItem {
  kind: HistoryKind
  lastAt: string
  article: ArticleSummary
}

export interface HistoryPage {
  items: HistoryItem[]
  page: number
  size: number
  hasMore: boolean
  paused: boolean
}
