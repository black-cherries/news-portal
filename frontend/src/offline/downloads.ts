import { api } from '../api/client'
import type { ArticleDetail } from '../api/types'
import { offlineStore, type OfflineArticle } from './store'

export interface OfflineStatus {
  id: number
  level: 'FULL_TEXT' | 'METADATA' | 'NONE'
  validUntil: string | null
}

const BATCH = 200
const LAST_SYNC_KEY = 'np.offline.lastSync'

export class NotDownloadableError extends Error {}

/** Saves an article on this device, keeping full text only when the publisher allows it (FR-061). */
export async function download(id: number): Promise<OfflineArticle> {
  const [status] = await api.get<OfflineStatus[]>(`/articles/offline-status?ids=${id}`)
  if (!status || status.level === 'NONE' || !status.validUntil) throw new NotDownloadableError()
  const article = await api.get<ArticleDetail>(`/articles/${id}`)
  const now = new Date().toISOString()
  const item: OfflineArticle = {
    id,
    article: keepAllowed(article, status.level),
    level: status.level,
    savedAt: now,
    checkedAt: now,
    validUntil: status.validUntil,
    bytes: 0,
  }
  item.bytes = new Blob([JSON.stringify(item)]).size
  await offlineStore().put(item)
  return item
}

export function removeDownload(id: number): Promise<void> {
  return offlineStore().remove(id)
}

/** A copy is shown only until its validity ends; after that the device must check online again (FR-063). */
export function isUsable(item: OfflineArticle, now = Date.now()): boolean {
  return Date.parse(item.validUntil) > now
}

export interface SyncResult {
  removed: number
  reduced: number
  checked: number
}

/**
 * Asks the server what each download may still keep, and applies it (FR-063):
 * removes articles no longer shown, drops full text that is no longer allowed
 * offline, and renews the validity of the rest. Feed outages do not matter (FR-064).
 */
export async function syncDownloads(): Promise<SyncResult> {
  const store = offlineStore()
  const items = await store.list()
  const result: SyncResult = { removed: 0, reduced: 0, checked: 0 }
  for (let i = 0; i < items.length; i += BATCH) {
    const batch = items.slice(i, i + BATCH)
    const statuses = await api.get<OfflineStatus[]>(`/articles/offline-status?ids=${batch.map((b) => b.id).join(',')}`)
    const byId = new Map(statuses.map((s) => [s.id, s]))
    const now = new Date().toISOString()
    for (const item of batch) {
      const status = byId.get(item.id)
      if (!status || status.level === 'NONE' || !status.validUntil) {
        await store.remove(item.id)
        result.removed++
        continue
      }
      let next: OfflineArticle = { ...item, checkedAt: now, validUntil: status.validUntil }
      if (item.level === 'FULL_TEXT' && status.level === 'METADATA') {
        next = { ...next, level: 'METADATA', article: keepAllowed(item.article, 'METADATA') }
        next.bytes = new Blob([JSON.stringify(next)]).size
        result.reduced++
      }
      await store.put(next)
      result.checked++
    }
  }
  try {
    localStorage.setItem(LAST_SYNC_KEY, new Date().toISOString())
  } catch {
    // Storage blocked: the time is only shown for information.
  }
  return result
}

export function lastSync(): string | null {
  try {
    return localStorage.getItem(LAST_SYNC_KEY)
  } catch {
    return null
  }
}

function keepAllowed(source: ArticleDetail, level: OfflineStatus['level']): ArticleDetail {
  // Offline copies keep video links only: embedding is a separate permission, checked online.
  const article = { ...source, videos: (source.videos ?? []).map((v) => ({ ...v, embedUrl: null })) }
  if (level === 'FULL_TEXT') return article
  return {
    ...article,
    fullTextHtml: null,
    fullTextNarrationPermitted: false,
    fullTextStatus: article.fullTextStatus === 'AVAILABLE' ? 'NOT_PERMITTED' : article.fullTextStatus,
  }
}
