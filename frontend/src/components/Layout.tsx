import { useEffect, useRef, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES, isLang } from '../i18n/messages'

export function Layout({ children }: { children: ReactNode }) {
  const { t, lang, setLang } = useI18n()
  const { me, isReader, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const mainRef = useRef<HTMLElement>(null)

  // Move focus to the new page's heading so screen readers announce it.
  useEffect(() => {
    const heading = mainRef.current?.querySelector('h1')
    if (heading) {
      heading.setAttribute('tabindex', '-1')
      heading.focus()
    }
  }, [location.pathname])

  const changeLanguage = async (value: string) => {
    if (!isLang(value)) return
    setLang(value)
    // Keep a signed-in reader's saved preference in step.
    if (isReader) {
      try {
        const prefs = await api.get<Record<string, unknown>>('/me/preferences')
        await api.put('/me/preferences', { ...prefs, uiLanguage: value })
      } catch {
        // The interface still switches; saving can be retried in Settings.
      }
    }
  }

  return (
    <>
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
      <header className="site-header">
        <div className="header-inner">
          <NavLink to="/" className="brand">
            {t('app.name')}
          </NavLink>
          <nav aria-label={t('nav.main')}>
            <ul className="nav-list">
              <li>
                <NavLink to="/" end>
                  {t('nav.latest')}
                </NavLink>
              </li>
              {isReader && (
                <>
                  <li>
                    <NavLink to="/for-you">{t('nav.forYou')}</NavLink>
                  </li>
                  <li>
                    <NavLink to="/saved">{t('nav.saved')}</NavLink>
                  </li>
                  <li>
                    <NavLink to="/history">{t('nav.history')}</NavLink>
                  </li>
                  <li>
                    <NavLink to="/settings">{t('nav.settings')}</NavLink>
                  </li>
                </>
              )}
            </ul>
          </nav>
          <div className="header-tools">
            <label className="lang-picker">
              <span className="visually-hidden">{t('nav.language')}</span>
              <select value={lang} onChange={(e) => void changeLanguage(e.target.value)}>
                {LANGUAGES.map((l) => (
                  <option key={l} value={l} lang={l}>
                    {LANGUAGE_NAMES[l]}
                  </option>
                ))}
              </select>
            </label>
            {me === null && (
              <>
                <NavLink to="/sign-in">{t('nav.signIn')}</NavLink>
                <NavLink to="/register" className="button-link">
                  {t('nav.register')}
                </NavLink>
              </>
            )}
            {me && (
              <button
                type="button"
                className="secondary"
                onClick={async () => {
                  await signOut()
                  navigate('/')
                }}
              >
                {t('nav.signOut')}
              </button>
            )}
          </div>
        </div>
      </header>
      {me && me.accountType === 'STAFF' && <p className="notice">{t('auth.staffNote')}</p>}
      <main id="main" ref={mainRef}>
        {children}
      </main>
    </>
  )
}
