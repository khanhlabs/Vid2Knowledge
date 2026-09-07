import { Navigate, Route, Routes } from 'react-router-dom'
import { LoginPage } from '../features/auth/LoginPage'
import { RequireAuth } from '../features/auth/AuthProvider'
import { AcceptInvitationPage } from '../features/auth/AcceptInvitationPage'
import { PreviewPage } from '../features/analysis/PreviewPage'
import { LearnerPage } from '../features/learner/LearnerPage'
import { PackageReviewPage } from '../features/workspace/PackageReviewPage'
import { WorkspacePage } from '../features/workspace/WorkspacePage'
import { CatalogPage } from '../features/workspace/CatalogPage'
import { AnalyticsPage } from '../features/workspace/AnalyticsPage'
import { AuthoringPage } from '../features/workspace/AuthoringPage'
import { IntegrationsPage } from '../features/workspace/IntegrationsPage'
import { LandingPage } from './LandingPage'
import { LegalDocumentPlaceholder } from './LegalDocumentPlaceholder'
import { SampleCoursePage } from './SampleCoursePage'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/preview" element={<PreviewPage />} />
      <Route path="/sample" element={<SampleCoursePage />} />
      <Route path="/legal/:policy" element={<LegalDocumentPlaceholder />} />
      <Route
        path="/app"
        element={
          <RequireAuth>
            <WorkspacePage />
          </RequireAuth>
        }
      />
      <Route
        path="/app/packages/:packageId"
        element={
          <RequireAuth>
            <PackageReviewPage />
          </RequireAuth>
        }
      />
      <Route
        path="/app/catalog"
        element={
          <RequireAuth>
            <CatalogPage />
          </RequireAuth>
        }
      />
      <Route
        path="/app/analytics"
        element={
          <RequireAuth>
            <AnalyticsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/app/authoring"
        element={
          <RequireAuth>
            <AuthoringPage />
          </RequireAuth>
        }
      />
      <Route
        path="/app/integrations"
        element={
          <RequireAuth>
            <IntegrationsPage />
          </RequireAuth>
        }
      />
      <Route
        path="/accept-invitation"
        element={
          <RequireAuth>
            <AcceptInvitationPage />
          </RequireAuth>
        }
      />
      <Route
        path="/learn/:organizationId"
        element={
          <RequireAuth>
            <LearnerPage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
