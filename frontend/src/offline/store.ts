import type { ArticleDetail } from '../api/types'

export type OfflineLevel = 'FULL_TEXT' | 'METADATA'

/**
 * One downloaded article, kept only on this device. {@code article.fullTextHtml}
 * is null unless the publisher allows full text offline (FR-061).
 */
export interface OfflineArticle {
  id: number
  article: ArticleDetail
  level: OfflineLevel
  savedAt: string
  /** Last time the server confirmed what may be kept. */
  checkedAt: string
  /** Hide the copy after this until the server has been asked again (FR-063). */
  validUntil: string
  bytes: number
}

export interface OfflineStore {
  list(): Promise<OfflineArticle[]>
  get(id: number): Promise<OfflineArticle | undefined>
  put(item: OfflineArticle): Promise<void>
  remove(id: number): Promise<void>
  clear(): Promise<void>
}

const DB_NAME = 'news-portal-offline'
const STORE = 'articles'

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' })
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error ?? new Error('indexedDB open failed'))
  })
}

function run<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return open().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(STORE, mode)
        const req = work(tx.objectStore(STORE))
        tx.oncomplete = () => {
          db.close()
          resolve(req.result)
        }
        tx.onerror = tx.onabort = () => {
          db.close()
          reject(tx.error ?? new Error('indexedDB transaction failed'))
        }
      }),
  )
}

function indexedDbStore(): OfflineStore {
  return {
    list: () => run<OfflineArticle[]>('readonly', (s) => s.getAll() as IDBRequest<OfflineArticle[]>),
    get: (id) => run<OfflineArticle | undefined>('readonly', (s) => s.get(id) as IDBRequest<OfflineArticle | undefined>),
    put: (item) => run('readwrite', (s) => s.put(item)).then(() => undefined),
    remove: (id) => run('readwrite', (s) => s.delete(id)).then(() => undefined),
    clear: () => run('readwrite', (s) => s.clear()).then(() => undefined),
  }
}

/** In memory only; used in tests and where IndexedDB is unavailable. */
export function memoryStore(): OfflineStore {
  const items = new Map<number, OfflineArticle>()
  return {
    list: async () => [...items.values()],
    get: async (id) => items.get(id),
    put: async (item) => void items.set(item.id, item),
    remove: async (id) => void items.delete(id),
    clear: async () => items.clear(),
  }
}

let current: OfflineStore | null = null

export function offlineStore(): OfflineStore {
  current ??= typeof indexedDB === 'undefined' ? memoryStore() : indexedDbStore()
  return current
}

export function setOfflineStoreForTests(store: OfflineStore) {
  current = store
}

/** Whether downloads survive a reload on this device. */
export function downloadsArePersistent(): boolean {
  return typeof indexedDB !== 'undefined'
}
