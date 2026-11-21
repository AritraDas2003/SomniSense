import { lazy, Suspense } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'
import AppLayout from '../components/layout/AppLayout'
import LoginPage from '../pages/auth/LoginPage'
import DashboardPage from '../pages/dashboard/DashboardPage'
import PatientsPage from '../pages/patients/PatientsPage'
import AddPatientPage from '../pages/patients/AddPatientPage'
import DevicesPage from '../pages/devices/DevicesPage'

const PatientDetailsPage = lazy(() => import('../pages/patients/PatientDetailsPage'))

function ProtectedRoute() {
  const { isAuthenticated } = useAuth()
  const location = useLocation()
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" replace state={{ from: location }} />
}

export default function AppRouter() {
  const { isAuthenticated } = useAuth()
  return (
    <Routes>
      <Route
        path="/login"
        element={isAuthenticated ? <Navigate to="/dashboard" replace /> : <LoginPage />}
      />
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/patients" element={<PatientsPage />} />
          <Route path="/patients/new" element={<AddPatientPage />} />
          <Route path="/patients/:patientId" element={<Suspense fallback={<div className="panel loading-panel"><span className="spinner" /><span>Loading patient overview...</span></div>}><PatientDetailsPage /></Suspense>} />
          <Route path="/devices" element={<DevicesPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to={isAuthenticated ? '/dashboard' : '/login'} replace />} />
    </Routes>
  )
}
