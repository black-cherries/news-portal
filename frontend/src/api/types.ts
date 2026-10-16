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
