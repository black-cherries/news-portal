import { useEffect, useRef, type FormEvent, type ReactNode } from 'react'
import { Link, NavLink, useLocation, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { useCategories } from '../hooks/useCategories'
import { useI18n } from '../i18n/I18nProvider'
import { LANGUAGES, LANGUAGE_NAMES, isLang } from '../i18n/messages'
import { Icon } from './Icon'

export function Layout({ children }: { children: ReactNode }) {
  const { t, lang, setLang } = useI18n()
  const { me, isReader } = useAuth()
  const location = useLocation()
  const mainRef = useRef<HTMLElement>(null)
  const isAdmin = location.pathname.startsWith('/admin')

  // Move focus to the new page's heading so screen readers announce it.
  useEffect(() => {
    const heading = mainRef.current?.querySelector('h1')
    if (heading) {
      heading.setAttribute('tabindex', '-1')
      heading.focus({ preventScroll: true })
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
        <div className="topbar">
          <Link to="/" className="brand" aria-label={t('app.name')}>
            <img src="/favicon.svg" alt="" width="32" height="32" />
            <span>{t('app.name')}</span>
          </Link>
          <HeaderSearch />
          <div className="header-tools">
            <label className="lang-picker">
              <Icon name="globe" />
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
                <NavLink to="/sign-in" className="header-link">
                  {t('nav.signIn')}
                </NavLink>
                <NavLink to="/register" className="button-link">
                  {t('nav.register')}
                </NavLink>
              </>
            )}
            {me && <AccountMenu />}
          </div>
        </div>
        {!isAdmin && <SectionBar />}
      </header>
      {me && me.accountType === 'STAFF' && !isAdmin && (
        <p className="notice staff-note">
          {t('auth.staffNote')}{' '}
          <NavLink to="/admin" lang="en">
            Open the staff console
          </NavLink>
        </p>
      )}
      <main id="main" ref={mainRef}>
        {children}
      </main>
      <footer className="site-footer">
        <div className="footer-inner">
          <div>
            <p className="footer-brand">{t('app.name')}</p>
            <p className="hint">{t('footer.tagline')}</p>
          </div>
          <ul className="footer-links">
            <li>
              <NavLink to="/downloads">{t('nav.downloads')}</NavLink>
            </li>
            <li>
              <NavLink to="/report">{t('report.title')}</NavLink>
            </li>
            {isReader && (
              <li>
                <NavLink to="/settings">{t('nav.settings')}</NavLink>
              </li>
            )}
          </ul>
        </div>
      </footer>
      {!isAdmin && <BottomNav />}
    </>
  )
}

/** Search box in the header; results show on the home page. */
function HeaderSearch() {
  const { t } = useI18n()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const location = useLocation()
  const current = location.pathname === '/' ? (params.get('q') ?? '') : ''

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const q = String(new FormData(e.currentTarget).get('q') ?? '').trim()
    navigate(q ? `/?q=${encodeURIComponent(q)}` : '/')
  }

  return (
    <form className="header-search" role="search" onSubmit={submit} key={current}>
      <Icon name="search" />
      <label className="visually-hidden" htmlFor="site-search">
        {t('filters.search')}
      </label>
      <input
        id="site-search"
        name="q"
        type="search"
        maxLength={200}
        defaultValue={current}
        placeholder={t('filters.searchPlaceholder')}
      />
    </form>
  )
}

/** Scrollable section chips: top stories, For You and each topic. */
function SectionBar() {
  const { t, categoryName } = useI18n()
  const { isReader } = useAuth()
  const categories = useCategories()
  const location = useLocation()
  const [params] = useSearchParams()
  const home = location.pathname === '/'
  const active = home ? (params.get('category') ?? '') : null

  return (
    <nav aria-label={t('nav.sections')} className="section-bar">
      <ul>
        <li>
          <Link to="/" className="chip" aria-current={active === '' && !params.get('q') ? 'page' : undefined}>
            {t('nav.topStories')}
          </Link>
        </li>
        {isReader && (
          <li>
            <NavLink to="/for-you" className="chip">
              {t('nav.forYou')}
            </NavLink>
          </li>
        )}
        {categories.map((c) => (
          <li key={c}>
            <Link
              to={`/?category=${encodeURIComponent(c)}`}
              className="chip"
              aria-current={active === c ? 'page' : undefined}
            >
              {categoryName(c)}
            </Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}

/** Signed-in menu: reader pages and sign-out. */
function AccountMenu() {
  const { t } = useI18n()
  const { me, isReader, signOut } = useAuth()
  const navigate = useNavigate()
  const ref = useRef<HTMLDetailsElement>(null)
  const location = useLocation()

  // Close after navigating.
  useEffect(() => {
    ref.current?.removeAttribute('open')
  }, [location])

  if (!me) return null
  return (
    <details className="account-menu" ref={ref}>
      <summary aria-label={t('nav.account')}>
        <span className="avatar" aria-hidden="true">
          {me.email.slice(0, 1).toUpperCase()}
        </span>
      </summary>
      <div className="menu-panel">
        <p className="menu-email">{me.email}</p>
        <ul>
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
                <NavLink to="/reports">{t('report.mine')}</NavLink>
              </li>
            </>
          )}
          <li>
            <NavLink to="/downloads">{t('nav.downloads')}</NavLink>
          </li>
          {isReader && (
            <li>
              <NavLink to="/settings">{t('nav.settings')}</NavLink>
            </li>
          )}
          {me.accountType === 'STAFF' && (
            <li>
              <NavLink to="/admin" lang="en">
                Staff console
              </NavLink>
            </li>
          )}
        </ul>
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
      </div>
    </details>
  )
}

/** Phone-only tab bar for the main destinations. */
function BottomNav() {
  const { t } = useI18n()
  const { isReader } = useAuth()
  return (
    <nav className="bottom-nav" aria-label={t('nav.main')}>
      <NavLink to="/" end>
        <Icon name="home" />
        <span>{t('nav.home')}</span>
      </NavLink>
      {isReader ? (
        <>
          <NavLink to="/for-you">
            <Icon name="spark" />
            <span>{t('nav.forYou')}</span>
          </NavLink>
          <NavLink to="/saved">
            <Icon name="bookmark" />
            <span>{t('nav.saved')}</span>
          </NavLink>
        </>
      ) : (
        <NavLink to="/sign-in">
          <Icon name="user" />
          <span>{t('nav.signIn')}</span>
        </NavLink>
      )}
      <NavLink to="/downloads">
        <Icon name="download" />
        <span>{t('nav.downloads')}</span>
      </NavLink>
    </nav>
  )
}
