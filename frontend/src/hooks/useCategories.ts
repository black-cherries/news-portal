import { useEffect, useState } from 'react'
import { api } from '../api/client'

let cached: Promise<string[]> | null = null

/** Active category codes, fetched once per page load and shared. */
export function useCategories(): string[] {
  const [codes, setCodes] = useState<string[]>([])
  useEffect(() => {
    cached ??= api.get<{ code: string }[]>('/categories').then(
      (c) => c.map((x) => x.code),
      () => {
        cached = null
        return []
      },
    )
    let live = true
    cached.then((c) => {
      if (live) setCodes(c)
    })
    return () => {
      live = false
    }
  }, [])
  return codes
}
