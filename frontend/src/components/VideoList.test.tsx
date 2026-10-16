import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { ArticleDetail, Video } from '../api/types'
import { download } from '../offline/downloads'
import { memoryStore, setOfflineStoreForTests } from '../offline/store'
import { mockFetch, renderAt } from '../test/render'
import { VideoList } from './VideoList'

const youtube: Video = {
  sourceUrl: 'https://youtu.be/dQw4w9WgXcQ',
  provider: 'YOUTUBE',
  embedUrl: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
  mimeType: null,
}

beforeEach(() => {
  mockFetch({ '/api/v1/auth/me': { status: 401, body: {} } })
})

describe('videos', () => {
  it('load nothing from the video host until the reader presses Play (FR-057)', async () => {
    const { container } = renderAt(<VideoList videos={[youtube]} />)
    expect(await screen.findByText(/loads from youtube-nocookie.com only if you choose to play it/)).toBeInTheDocument()
    expect(container.querySelector('iframe')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Play video' }))
    const frame = container.querySelector('iframe')
    expect(frame?.getAttribute('src')).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
    expect(frame?.getAttribute('sandbox')).toContain('allow-scripts')
    expect(frame?.getAttribute('title')).toBe('Video from youtube-nocookie.com')
  })

  it('play permitted video files in a video element', async () => {
    const file: Video = {
      sourceUrl: 'https://cdn.example.org/clip.mp4',
      provider: 'FILE',
      embedUrl: 'https://cdn.example.org/clip.mp4',
      mimeType: 'video/mp4',
    }
    const { container } = renderAt(<VideoList videos={[file]} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Play video' }))
    expect(container.querySelector('video')?.getAttribute('src')).toBe('https://cdn.example.org/clip.mp4')
  })

  it('are only linked when embedding is not permitted (FR-056)', async () => {
    const { container } = renderAt(<VideoList videos={[{ ...youtube, embedUrl: null }]} />)
    const link = await screen.findByRole('link', { name: /Watch the video at youtu.be/ })
    expect(link).toHaveAttribute('href', 'https://youtu.be/dQw4w9WgXcQ')
    expect(screen.queryByRole('button', { name: 'Play video' })).not.toBeInTheDocument()
    expect(container.querySelector('iframe')).toBeNull()
  })

  it('are kept as links only in offline copies', async () => {
    setOfflineStoreForTests(memoryStore())
    const article = { id: 5, title: 'T', videos: [youtube], fullTextStatus: 'NOT_PERMITTED', fullTextHtml: null }
    mockFetch({
      '/api/v1/articles/offline-status?ids=5': {
        body: [{ id: 5, level: 'METADATA', validUntil: new Date(Date.now() + 86_400_000).toISOString() }],
      },
      '/api/v1/articles/5': { body: article as unknown as ArticleDetail },
    })
    const saved = await download(5)
    expect(saved.article.videos[0].embedUrl).toBeNull()
    expect(saved.article.videos[0].sourceUrl).toBe('https://youtu.be/dQw4w9WgXcQ')
  })
})
