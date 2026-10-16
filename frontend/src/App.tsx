import type { ReactNode } from 'react'
import { Link, Navigate, Route, Routes } from 'react-router'
import { AdminLayout } from './admin/AdminLayout'
import { AlertsPage } from './admin/AlertsPage'
import { DashboardPage } from './admin/DashboardPage'
import { ReportAdminDetailPage, ReportsAdminPage } from './admin/ReportsAdminPages'
import { SourceDetailPage } from './admin/SourceDetailPage'
import { HealthPage, SourcesPage } from './admin/SourcesPage'
import { AuditPage, OpsPage, StaffPage } from './admin/SystemPages'
import { useAuth } from './auth/AuthProvider'
import { Layout } from './components/Layout'
import { useI18n } from './i18n/I18nProvider'
import { ArticlePage } from './pages/ArticlePage'
import { ForgotPasswordPage, RegisterPage, ResetPasswordPage, SignInPage, VerifyEmailPage } from './pages/AuthPages'
import { ForYouPage } from './pages/ForYouPage'
import { HistoryPage } from './pages/HistoryPage'
import { LatestPage } from './pages/LatestPage'
import { MyReportsPage, ReportPage } from './pages/ReportPages'
import { CollectionPage, SavedPage } from './pages/SavedPages'
import { SettingsPage } from './pages/SettingsPage'
import { UnsubscribePage } from './pages/UnsubscribePage'

/** Reader-only pages: guests go to sign-in. The backend enforces this too. */
function ReaderOnly({ children }: { children: ReactNode }) {
  const { me, isReader } = useAuth()
  const { t } = useI18n()
  if (me === undefined) return <p role="status">{t('common.loading')}</p>
  if (!isReader) return <Navigate to="/sign-in" replace />
  return children
}

function NotFound() {
  const { t } = useI18n()
  return (
    <>
      <h1>{t('common.notFound')}</h1>
      <Link to="/">{t('common.backHome')}</Link>
    </>
  )
}

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<LatestPage />} />
        <Route path="/articles/:id" element={<ArticlePage />} />
        <Route path="/for-you" element={<ForYouPage />} />
        <Route path="/sign-in" element={<SignInPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/saved" element={<ReaderOnly><SavedPage /></ReaderOnly>} />
        <Route path="/saved/collections/:id" element={<ReaderOnly><CollectionPage /></ReaderOnly>} />
        <Route path="/history" element={<ReaderOnly><HistoryPage /></ReaderOnly>} />
        <Route path="/settings" element={<ReaderOnly><SettingsPage /></ReaderOnly>} />
        <Route path="/unsubscribe" element={<UnsubscribePage />} />
        <Route path="/report" element={<ReportPage />} />
        <Route path="/reports" element={<ReaderOnly><MyReportsPage /></ReaderOnly>} />
        <Route path="/admin" element={<AdminLayout />}>
          <Route index element={<DashboardPage />} />
          <Route path="sources" element={<SourcesPage />} />
          <Route path="sources/:id" element={<SourceDetailPage />} />
          <Route path="health" element={<HealthPage />} />
          <Route path="reports" element={<ReportsAdminPage />} />
          <Route path="reports/:id" element={<ReportAdminDetailPage />} />
          <Route path="alerts" element={<AlertsPage />} />
          <Route path="staff" element={<StaffPage />} />
          <Route path="ops" element={<OpsPage />} />
          <Route path="audit" element={<AuditPage />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Routes>
    </Layout>
  )
}
