import { useState } from 'react'
import type { Video } from '../api/types'
import { useI18n } from '../i18n/I18nProvider'

function host(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

/**
 * FR-056/FR-057: permitted embeds load only after the reader presses Play.
 * Nothing is requested from the video host before that. Videos that may not
 * be embedded are links to the original.
 */
export function VideoList({ videos }: { videos: Video[] }) {
  const { t } = useI18n()
  if (videos.length === 0) return null
  return (
    <section className="videos" aria-label={t('video.title')}>
      {videos.map((v) => (
        <VideoItem key={v.sourceUrl} video={v} />
      ))}
    </section>
  )
}

function VideoItem({ video }: { video: Video }) {
  const { t } = useI18n()
  const [playing, setPlaying] = useState(false)
  const embedHost = video.embedUrl ? host(video.embedUrl) : null
  const original = (
    <a href={video.sourceUrl} target="_blank" rel="noopener noreferrer">
      {t('video.watchAt', { host: host(video.sourceUrl) })}
      <span className="visually-hidden"> {t('common.opensNewTab')}</span>
    </a>
  )

  if (!video.embedUrl || !embedHost) return <p className="video-link">{original}</p>

  if (!playing) {
    return (
      <div className="video-placeholder">
        <button type="button" onClick={() => setPlaying(true)}>
          {t('video.play')}
        </button>
        <p className="hint">{t('video.privacy', { host: embedHost })}</p>
        <p className="hint">{original}</p>
      </div>
    )
  }

  return (
    <div className="video-frame">
      {video.provider === 'FILE' ? (
        <video controls autoPlay preload="metadata" src={video.embedUrl}>
          {original}
        </video>
      ) : (
        <iframe
          src={video.embedUrl}
          title={t('video.frameTitle', { host: embedHost })}
          sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
          allow="fullscreen; picture-in-picture; encrypted-media"
          referrerPolicy="strict-origin-when-cross-origin"
          loading="lazy"
        />
      )}
      <p className="hint">{original}</p>
    </div>
  )
}
