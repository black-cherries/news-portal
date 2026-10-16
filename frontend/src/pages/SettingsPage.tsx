import { useEffect, useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, api } from '../api/client'
import type { DigestFrequency, NotificationSettings, Preferences } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES, isLang } from '../i18n/messages'

const RECOVERY_DAYS = 7

type Status = { text: string; kind: 'error' | 'info' } | null

function StatusLine({ status }: { status: Status }) {
  if (!status) return null
  return (
    <p role={status.kind === 'error' ? 'alert' : 'status'} className={status.kind}>
      {status.text}
    </p>
  )
}

function PreferencesForm() {
  const { t, setLang, categoryName } = useI18n()
  const ids = { ui: useId(), digest: useId(), paused: useId() }
  const [prefs, setPrefs] = useState<Preferences | null>(null)
  const [categories, setCategories] = useState<string[]>([])
  const [status, setStatus] = useState<Status>(null)

  useEffect(() => {
    api.get<Preferences>('/me/preferences').then(setPrefs, (err: unknown) =>
      setStatus({ text: t(errorKey(err)), kind: 'error' }),
    )
    api.get<{ code: string }[]>('/categories').then((c) => setCategories(c.map((x) => x.code)), () => {})
  }, [t])

  if (!prefs) return <StatusLine status={status} />

  const toggle = (list: string[], value: string) =>
    list.includes(value) ? list.filter((v) => v !== value) : [...list, value]

  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      const saved = await api.put<Preferences>('/me/preferences', prefs)
      setPrefs(saved)
      if (isLang(saved.uiLanguage)) setLang(saved.uiLanguage)
      setStatus({ text: t('settings.saved'), kind: 'info' })
    } catch (err) {
      setStatus({ text: t(errorKey(err)), kind: 'error' })
    }
  }

  return (
    <form onSubmit={save} className="stack">
      <div className="field">
        <label htmlFor={ids.ui}>{t('settings.uiLanguage')}</label>
        <select id={ids.ui} value={prefs.uiLanguage} onChange={(e) => setPrefs({ ...prefs, uiLanguage: e.target.value })}>
          {LANGUAGES.map((l) => (
            <option key={l} value={l} lang={l}>
              {LANGUAGE_NAMES[l]}
            </option>
          ))}
        </select>
      </div>
      <fieldset>
        <legend>{t('settings.newsLanguages')}</legend>
        <p className="hint">{t('settings.newsLanguagesHint')}</p>
        {LANGUAGES.map((l) => (
          <label key={l} className="check">
            <input
              type="checkbox"
              checked={prefs.newsLanguages.includes(l)}
              onChange={() => setPrefs({ ...prefs, newsLanguages: toggle(prefs.newsLanguages, l) })}
            />
            <span lang={l}>{LANGUAGE_NAMES[l]}</span>
          </label>
        ))}
      </fieldset>
      <fieldset>
        <legend>{t('settings.topics')}</legend>
        <p className="hint">{t('settings.topicsHint')}</p>
        <div className="check-grid">
          {categories.map((c) => (
            <label key={c} className="check">
              <input
                type="checkbox"
                checked={prefs.topics.includes(c)}
                onChange={() => setPrefs({ ...prefs, topics: toggle(prefs.topics, c) })}
              />
              <span>{categoryName(c)}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <div className="field">
        <label htmlFor={ids.digest}>{t('settings.digestLanguage')}</label>
        <select
          id={ids.digest}
          value={prefs.digestLanguage ?? ''}
          onChange={(e) => setPrefs({ ...prefs, digestLanguage: e.target.value || null })}
        >
          <option value="">{t('settings.none')}</option>
          {LANGUAGES.map((l) => (
            <option key={l} value={l} lang={l}>
              {LANGUAGE_NAMES[l]}
            </option>
          ))}
        </select>
      </div>
      <div className="check">
        <input
          id={ids.paused}
          type="checkbox"
          checked={prefs.historyPaused}
          onChange={(e) => setPrefs({ ...prefs, historyPaused: e.target.checked })}
        />
        <label htmlFor={ids.paused}>{t('settings.historyPaused')}</label>
      </div>
      <button type="submit">{t('common.save')}</button>
      <StatusLine status={status} />
    </form>
  )
}

const FREQUENCIES: DigestFrequency[] = ['OFF', 'DAILY', 'WEEKLY']

const FREQUENCY_KEYS = { OFF: 'notify.off', DAILY: 'notify.daily', WEEKLY: 'notify.weekly' } as const

/** FR-065..FR-068: everything off until the reader turns it on. */
function NotificationsForm() {
  const { t, categoryName, formatDateTime } = useI18n()
  const ids = { digest: useId(), alerts: useId() }
  const [settings, setSettings] = useState<NotificationSettings | null>(null)
  const [categories, setCategories] = useState<string[]>([])
  const [status, setStatus] = useState<Status>(null)

  useEffect(() => {
    api.get<NotificationSettings>('/me/notifications').then(setSettings, (err: unknown) =>
      setStatus({ text: t(errorKey(err)), kind: 'error' }),
    )
    api.get<{ code: string }[]>('/categories').then((c) => setCategories(c.map((x) => x.code)), () => {})
  }, [t])

  if (!settings) return <StatusLine status={status} />

  const toggle = (list: string[], value: string) =>
    list.includes(value) ? list.filter((v) => v !== value) : [...list, value]

  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      setSettings(await api.put<NotificationSettings>('/me/notifications', settings))
      setStatus({ text: t('settings.saved'), kind: 'info' })
    } catch (err) {
      setStatus({ text: t(errorKey(err)), kind: 'error' })
    }
  }

  return (
    <form onSubmit={save} className="stack">
      <p className="hint">{t('notify.hint')}</p>
      <div className="field">
        <label htmlFor={ids.digest}>{t('notify.digest')}</label>
        <select
          id={ids.digest}
          value={settings.digest}
          onChange={(e) => setSettings({ ...settings, digest: e.target.value as DigestFrequency })}
        >
          {FREQUENCIES.map((f) => (
            <option key={f} value={f}>
              {t(FREQUENCY_KEYS[f])}
            </option>
          ))}
        </select>
        {settings.digest !== 'OFF' && settings.nextDigestAt && (
          <p className="hint">{t('notify.next', { date: formatDateTime(settings.nextDigestAt) })}</p>
        )}
      </div>
      <div className="check">
        <input
          id={ids.alerts}
          type="checkbox"
          checked={settings.breakingAlerts}
          onChange={(e) => setSettings({ ...settings, breakingAlerts: e.target.checked })}
        />
        <label htmlFor={ids.alerts}>{t('notify.alerts')}</label>
      </div>
      <fieldset>
        <legend>{t('notify.languages')}</legend>
        <p className="hint">{t('notify.languagesHint')}</p>
        {LANGUAGES.map((l) => (
          <label key={l} className="check">
            <input
              type="checkbox"
              checked={settings.languages.includes(l)}
              onChange={() => setSettings({ ...settings, languages: toggle(settings.languages, l) })}
            />
            <span lang={l}>{LANGUAGE_NAMES[l]}</span>
          </label>
        ))}
      </fieldset>
      <fieldset>
        <legend>{t('notify.topics')}</legend>
        <p className="hint">{t('notify.topicsHint')}</p>
        <div className="check-grid">
          {categories.map((c) => (
            <label key={c} className="check">
              <input
                type="checkbox"
                checked={settings.topics.includes(c)}
                onChange={() => setSettings({ ...settings, topics: toggle(settings.topics, c) })}
              />
              <span>{categoryName(c)}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="hint">{t('notify.languageNote')}</p>
      <button type="submit">{t('common.save')}</button>
      <StatusLine status={status} />
    </form>
  )
}

function PasswordInput({ label, name, autoComplete }: { label: string; name: string; autoComplete: string }) {
  const id = useId()
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={name} type="password" required maxLength={128} autoComplete={autoComplete} />
    </div>
  )
}

function ChangePasswordForm() {
  const { t } = useI18n()
  const [status, setStatus] = useState<Status>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    const data = new FormData(form)
    try {
      await api.post('/auth/password/change', {
        currentPassword: String(data.get('current')),
        newPassword: String(data.get('next')),
      })
      form.reset()
      setStatus({ text: t('settings.passwordChanged'), kind: 'info' })
    } catch (err) {
      setStatus({ text: err instanceof ApiError && err.status === 400 ? t('auth.checkDetails') : t(errorKey(err)), kind: 'error' })
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <PasswordInput label={t('auth.currentPassword')} name="current" autoComplete="current-password" />
      <PasswordInput label={t('auth.newPassword')} name="next" autoComplete="new-password" />
      <p className="hint">{t('auth.passwordHint')}</p>
      <button type="submit">{t('settings.changePassword')}</button>
      <StatusLine status={status} />
    </form>
  )
}

function ExportForm() {
  const { t } = useI18n()
  const [status, setStatus] = useState<Status>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = e.currentTarget
    try {
      const data = await api.post<unknown>('/me/export', { password: String(new FormData(form).get('password')) })
      const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }))
      const link = document.createElement('a')
      link.href = url
      link.download = 'news-portal-my-data.json'
      link.click()
      URL.revokeObjectURL(url)
      form.reset()
      setStatus(null)
    } catch (err) {
      setStatus({
        text: err instanceof ApiError && err.status === 400 ? t('settings.wrongPassword') : t(errorKey(err)),
        kind: 'error',
      })
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <p>{t('settings.exportHint')}</p>
      <PasswordInput label={t('settings.confirmPassword')} name="password" autoComplete="current-password" />
      <button type="submit">{t('settings.export')}</button>
      <StatusLine status={status} />
    </form>
  )
}

function DeleteAccountForm() {
  const { t, formatDateTime } = useI18n()
  const { clear } = useAuth()
  const navigate = useNavigate()
  const confirmId = useId()
  const [status, setStatus] = useState<Status>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    try {
      const result = await api.post<{ deletionDueAt: string }>('/me/account/delete', {
        password: String(new FormData(e.currentTarget).get('password')),
      })
      clear()
      navigate('/sign-in', { replace: true })
      window.alert(t('settings.deleted', { date: formatDateTime(result.deletionDueAt) }))
    } catch (err) {
      setStatus({
        text: err instanceof ApiError && err.status === 400 ? t('settings.wrongPassword') : t(errorKey(err)),
        kind: 'error',
      })
    }
  }

  return (
    <form onSubmit={submit} className="stack">
      <p>{t('settings.deleteHint', { days: RECOVERY_DAYS })}</p>
      <PasswordInput label={t('settings.confirmPassword')} name="password" autoComplete="current-password" />
      <div className="check">
        <input id={confirmId} type="checkbox" required />
        <label htmlFor={confirmId}>{t('settings.deleteConfirm')}</label>
      </div>
      <button type="submit" className="danger">
        {t('settings.deleteButton')}
      </button>
      <StatusLine status={status} />
    </form>
  )
}

export function SettingsPage() {
  const { t } = useI18n()
  return (
    <>
      <h1>{t('settings.title')}</h1>
      <section aria-labelledby="prefs-h">
        <h2 id="prefs-h">{t('settings.preferences')}</h2>
        <PreferencesForm />
      </section>
      <section aria-labelledby="notify-h">
        <h2 id="notify-h">{t('notify.title')}</h2>
        <NotificationsForm />
      </section>
      <section aria-labelledby="password-h">
        <h2 id="password-h">{t('settings.changePassword')}</h2>
        <ChangePasswordForm />
      </section>
      <section aria-labelledby="data-h">
        <h2 id="data-h">{t('settings.yourData')}</h2>
        <ExportForm />
      </section>
      <section aria-labelledby="delete-h">
        <h2 id="delete-h">{t('settings.deleteTitle')}</h2>
        <DeleteAccountForm />
      </section>
    </>
  )
}
