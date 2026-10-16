import { useI18n } from '../i18n/I18nProvider'

interface Props {
  publishedAt: string | null
  publisherUpdatedAt: string | null
  ingestedAt: string
  /** Compact form for lists: only the publication time (or ingestion time if unknown). */
  compact?: boolean
}

/**
 * FR-017/FR-022: publication, publisher-update and ingestion times are shown
 * separately, and an unknown time is said to be unknown rather than guessed.
 */
export function Timestamps({ publishedAt, publisherUpdatedAt, ingestedAt, compact }: Props) {
  const { t, formatDateTime } = useI18n()

  if (compact) {
    return publishedAt ? (
      <time dateTime={publishedAt}>{formatDateTime(publishedAt)}</time>
    ) : (
      <span>
        {t('article.ingested')}: <time dateTime={ingestedAt}>{formatDateTime(ingestedAt)}</time>
      </span>
    )
  }

  const row = (label: string, value: string | null) => (
    <>
      <dt>{label}</dt>
      <dd>{value ? <time dateTime={value}>{formatDateTime(value)}</time> : t('article.unknownTime')}</dd>
    </>
  )

  return (
    <dl className="timestamps">
      {row(t('article.published'), publishedAt)}
      {row(t('article.updated'), publisherUpdatedAt)}
      {row(t('article.ingested'), ingestedAt)}
    </dl>
  )
}
