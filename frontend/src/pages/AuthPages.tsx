import { useEffect, useId, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, api } from '../api/client'
import type { Me } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { errorKey } from '../hooks/useArticlePages'
import { useI18n } from '../i18n/I18nProvider'

/** Reads the one-time token from the URL fragment (#token=…), which is never sent to servers. */
function tokenFromHash(): string {
  return new URLSearchParams(window.location.hash.slice(1)).get('token') ?? ''
}

function Field(props: { label: string; type: string; name: string; autoComplete: string; hint?: string; minLength?: number }) {
  const id = useId()
  const hintId = useId()
  return (
    <div className="field">
      <label htmlFor={id}>{props.label}</label>
      <input
        id={id}
        name={props.name}
        type={props.type}
        required
        autoComplete={props.autoComplete}
        minLength={props.minLength}
        maxLength={props.type === 'password' ? 128 : 254}
        aria-describedby={props.hint ? hintId : undefined}
        dir={props.type === 'email' ? 'ltr' : undefined}
      />
      {props.hint && (
        <p id={hintId} className="hint">
          {props.hint}
        </p>
      )}
    </div>
  )
}

export function SignInPage() {
  const { t, formatDateTime } = useI18n()
  const { signedIn } = useAuth()
  const navigate = useNavigate()
  const [message, setMessage] = useState<{ text: string; kind: 'error' | 'info' } | null>(null)
  const [pending, setPending] = useState<{ email: string; password: string; due: string } | null>(null)
  const [unverified, setUnverified] = useState<string | null>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    const email = String(form.get('email'))
    const password = String(form.get('password'))
    setMessage(null)
    setPending(null)
    setUnverified(null)
    try {
      const me = await api.post<Me>('/auth/login', { email, password })
      await signedIn(me)
      navigate('/')
    } catch (err) {
      if (err instanceof ApiError && err.code === 'ACCOUNT_DELETION_PENDING') {
        setPending({ email, password, due: String(err.body.deletionDueAt) })
      } else if (err instanceof ApiError && err.code === 'EMAIL_NOT_VERIFIED') {
        setUnverified(email)
        setMessage({ text: t('auth.notVerified'), kind: 'error' })
      } else if (err instanceof ApiError && err.status === 401) {
        setMessage({ text: t('auth.invalid'), kind: 'error' })
      } else {
        setMessage({ text: t(errorKey(err)), kind: 'error' })
      }
    }
  }

  const restore = async () => {
    if (!pending) return
    try {
      await api.post('/auth/account/restore', { email: pending.email, password: pending.password })
      setPending(null)
      setMessage({ text: t('auth.restored'), kind: 'info' })
    } catch (err) {
      setMessage({ text: t(errorKey(err)), kind: 'error' })
    }
  }

  const resend = async () => {
    if (!unverified) return
    try {
      await api.post('/auth/resend-verification', { email: unverified })
      setMessage({ text: t('auth.checkEmail'), kind: 'info' })
    } catch (err) {
      setMessage({ text: t(errorKey(err)), kind: 'error' })
    }
  }

  return (
    <div className="narrow">
      <h1>{t('auth.signInTitle')}</h1>
      <form onSubmit={submit} className="stack">
        <Field label={t('auth.email')} type="email" name="email" autoComplete="email" />
        <Field label={t('auth.password')} type="password" name="password" autoComplete="current-password" />
        <button type="submit">{t('auth.signInButton')}</button>
      </form>
      {message && (
        <p role={message.kind === 'error' ? 'alert' : 'status'} className={message.kind}>
          {message.text}
        </p>
      )}
      {unverified && (
        <button type="button" className="secondary" onClick={() => void resend()}>
          {t('auth.resend')}
        </button>
      )}
      {pending && (
        <div role="alert" className="notice">
          <p>{t('auth.deletionPending', { date: formatDateTime(pending.due) })}</p>
          <button type="button" onClick={() => void restore()}>
            {t('auth.restore')}
          </button>
        </div>
      )}
      <p>
        <Link to="/forgot-password">{t('auth.forgot')}</Link>
      </p>
      <p>
        {t('auth.noAccount')} <Link to="/register">{t('nav.register')}</Link>
      </p>
    </div>
  )
}

export function RegisterPage() {
  const { t, lang } = useI18n()
  const ageId = useId()
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget)
    setError(null)
    try {
      await api.post('/auth/register', {
        email: String(form.get('email')),
        password: String(form.get('password')),
        ageConfirmed: form.get('age') === 'on',
        uiLanguage: lang,
      })
      setDone(true)
    } catch (err) {
      setError(err instanceof ApiError && err.status === 400 ? t('auth.checkDetails') : t(errorKey(err)))
    }
  }

  return (
    <div className="narrow">
      <h1>{t('auth.registerTitle')}</h1>
      {done ? (
        <p role="status">{t('auth.checkEmail')}</p>
      ) : (
        <form onSubmit={submit} className="stack">
          <Field label={t('auth.email')} type="email" name="email" autoComplete="email" />
          <Field
            label={t('auth.password')}
            type="password"
            name="password"
            autoComplete="new-password"
            minLength={10}
            hint={t('auth.passwordHint')}
          />
          <div className="check">
            <input id={ageId} type="checkbox" name="age" required />
            <label htmlFor={ageId}>{t('auth.ageConfirm')}</label>
          </div>
          <button type="submit">{t('auth.registerButton')}</button>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
      )}
    </div>
  )
}

export function VerifyEmailPage() {
  const { t } = useI18n()
  const [token] = useState(tokenFromHash)
  const [result, setResult] = useState<'pending' | 'ok' | 'invalid' | 'error'>(token ? 'pending' : 'invalid')

  useEffect(() => {
    // Drop the token from the address bar and history.
    window.history.replaceState(null, '', window.location.pathname)
    if (!token) return
    api.post('/auth/verify-email', { token }).then(
      () => setResult('ok'),
      (err: unknown) => setResult(err instanceof ApiError && err.status === 400 ? 'invalid' : 'error'),
    )
  }, [token])

  return (
    <div className="narrow">
      <h1>{t('auth.verifyTitle')}</h1>
      {result === 'pending' && <p role="status">{t('common.loading')}</p>}
      {result === 'ok' && (
        <p role="status">
          {t('auth.verified')} <Link to="/sign-in">{t('nav.signIn')}</Link>
        </p>
      )}
      {result === 'invalid' && <p role="alert">{t('auth.linkInvalid')}</p>}
      {result === 'error' && <p role="alert">{t('common.error')}</p>}
    </div>
  )
}

export function ForgotPasswordPage() {
  const { t } = useI18n()
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError(null)
    try {
      await api.post('/auth/password-reset/request', { email: String(new FormData(e.currentTarget).get('email')) })
      setDone(true)
    } catch (err) {
      setError(t(errorKey(err)))
    }
  }

  return (
    <div className="narrow">
      <h1>{t('auth.forgotTitle')}</h1>
      {done ? (
        <p role="status">{t('auth.checkEmail')}</p>
      ) : (
        <form onSubmit={submit} className="stack">
          <Field label={t('auth.email')} type="email" name="email" autoComplete="email" />
          <button type="submit">{t('auth.sendLink')}</button>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
      )}
    </div>
  )
}

export function ResetPasswordPage() {
  const { t } = useI18n()
  const [token] = useState(tokenFromHash)
  const [result, setResult] = useState<'form' | 'ok' | 'invalid'>(token ? 'form' : 'invalid')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    window.history.replaceState(null, '', window.location.pathname)
  }, [])

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError(null)
    try {
      await api.post('/auth/password-reset/confirm', {
        token,
        newPassword: String(new FormData(e.currentTarget).get('password')),
      })
      setResult('ok')
    } catch (err) {
      if (err instanceof ApiError && err.code === 'INVALID_OR_EXPIRED_LINK') setResult('invalid')
      else setError(err instanceof ApiError && err.status === 400 ? t('auth.checkDetails') : t(errorKey(err)))
    }
  }

  return (
    <div className="narrow">
      <h1>{t('auth.resetTitle')}</h1>
      {result === 'ok' && (
        <p role="status">
          {t('auth.resetDone')} <Link to="/sign-in">{t('nav.signIn')}</Link>
        </p>
      )}
      {result === 'invalid' && (
        <p role="alert">
          {t('auth.linkInvalid')} <Link to="/forgot-password">{t('auth.forgot')}</Link>
        </p>
      )}
      {result === 'form' && (
        <form onSubmit={submit} className="stack">
          <Field
            label={t('auth.newPassword')}
            type="password"
            name="password"
            autoComplete="new-password"
            minLength={10}
            hint={t('auth.passwordHint')}
          />
          <button type="submit">{t('common.save')}</button>
          {error && (
            <p role="alert" className="error">
              {error}
            </p>
          )}
        </form>
      )}
    </div>
  )
}
