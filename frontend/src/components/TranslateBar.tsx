import { useId, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, api } from '../api/client'
import type { ArticleDetail, Translation } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES, isLang, type MessageKey } from '../i18n/messages'

type Problem = Extract<MessageKey, `translate.${string}`> | 'common.error'

/**
 * "Read in [language] · Translate" (SRS amendment 2). Uses a cached
 * translation when there is one; otherwise signed-in readers can ask for a
 * new one. The parent shows the result in place of the original.
 */
export function TranslateBar({
  article,
  translation,
  onChange,
}: {
  article: ArticleDetail
  translation: Translation | null
  onChange: (t: Translation | null) => void
}) {
  const { t, lang } = useI18n()
  const { isReader } = useAuth()
  const selectId = useId()
  const targets = LANGUAGES.filter((l) => l !== article.language)
  const [target, setTarget] = useState<string>((targets as string[]).includes(lang) ? lang : targets[0])
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)

  if (!article.translationAvailable) return null

  const sourceName = isLang(article.language) ? LANGUAGE_NAMES[article.language] : article.language

  if (translation) {
    return (
      <div className="translate-bar translated" role="status">
        <span>{t('translate.notice', { language: sourceName })}</span>
        <button type="button" className="secondary small" onClick={() => onChange(null)}>
          {t('translate.showOriginal')}
        </button>
      </div>
    )
  }

  const run = async () => {
    setBusy(true)
    setProblem(null)
    const path = `/articles/${article.id}/translations/${target}`
    try {
      // Anyone can read a translation that already exists.
      onChange(await api.get<Translation>(path))
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 404)) {
        setProblem('common.error')
      } else if (!isReader) {
        setProblem('translate.signIn')
      } else {
        try {
          onChange(await api.post<Translation>(path))
        } catch (e) {
          const status = e instanceof ApiError ? e.status : 0
          setProblem(
            status === 403
              ? 'translate.notAllowed'
              : status === 429
                ? 'translate.limit'
                : status === 503
                  ? 'translate.unavailable'
                  : 'translate.failed',
          )
        }
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="translate-bar">
      <label htmlFor={selectId}>{t('translate.readIn')}</label>
      <select id={selectId} value={target} onChange={(e) => setTarget(e.target.value)} disabled={busy}>
        {targets.map((l) => (
          <option key={l} value={l} lang={l}>
            {LANGUAGE_NAMES[l]}
          </option>
        ))}
      </select>
      <button type="button" onClick={() => void run()} disabled={busy}>
        {t('translate.button')}
      </button>
      {busy && (
        <span role="status" className="hint">
          {t('translate.working')}
        </span>
      )}
      {problem && (
        <span role="alert" className="hint">
          {problem === 'translate.signIn' ? <Link to="/sign-in">{t(problem)}</Link> : t(problem)}
        </span>
      )}
    </div>
  )
}
