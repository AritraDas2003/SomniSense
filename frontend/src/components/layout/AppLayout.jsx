import { useState } from 'react'
import { Activity, BedDouble, ChevronDown, CircleUserRound, LayoutDashboard, LogOut, Menu, X } from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../../hooks/useAuth'
import { useMockApi } from '../../services/api/apiConfig'

const navigation = [
  { to: '/dashboard', label: 'Overview', icon: LayoutDashboard },
  { to: '/patients', label: 'Patients', icon: BedDouble },
  { to: '/devices', label: 'Devices', icon: Activity },
]

const routeTitles = {
  '/dashboard': 'Overview',
  '/patients': 'Patients',
  '/patients/new': 'Add patient',
  '/devices': 'Devices',
}

export default function AppLayout() {
  const { user, signOut } = useAuth()
  const mockMode = useMockApi
  const location = useLocation()
  const navigate = useNavigate()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const title = location.pathname.startsWith('/patients/') && location.pathname !== '/patients/new'
    ? 'Patient overview'
    : routeTitles[location.pathname] || 'SomniSense'

  function logout() {
    signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="app-shell">
      {sidebarOpen && <button className="sidebar-backdrop" aria-label="Close navigation" onClick={() => setSidebarOpen(false)} />}
      <aside className={`sidebar ${sidebarOpen ? 'sidebar-open' : ''}`}>
        <div className="brand-lockup">
          <div className="brand-mark"><Activity size={20} strokeWidth={2.5} /></div>
          <span>somni<span className="brand-accent">sense</span></span>
          <button className="icon-button sidebar-close" aria-label="Close navigation" onClick={() => setSidebarOpen(false)}><X size={18} /></button>
        </div>
        <div className="workspace-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          {navigation.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) => `nav-link ${isActive ? 'nav-link-active' : ''}`}
              onClick={() => setSidebarOpen(false)}
            >
              <Icon size={18} strokeWidth={1.8} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="environment-card">
            <span className="environment-dot" />
            <div><strong>{mockMode ? 'Demo workspace' : 'Workspace'}</strong><small>{mockMode ? 'Mock API connected' : 'Spring Boot API'}</small></div>
          </div>
          <button className="nav-link logout-link" onClick={logout}>
            <LogOut size={18} strokeWidth={1.8} /><span>Sign out</span>
          </button>
        </div>
      </aside>

      <div className="main-column">
        <header className="topbar">
          <button className="icon-button mobile-menu" aria-label="Open navigation" onClick={() => setSidebarOpen(true)}><Menu size={20} /></button>
          <div className="topbar-context">
            <span className="topbar-overline">SOMNISENSE <span>/</span> MONITORING CONSOLE</span>
            <strong>{title}</strong>
          </div>
          <div className="topbar-actions">
            {mockMode && <span className="mock-pill"><span /> MOCK DATA</span>}
            <div className="profile-wrap">
              <button
                className="profile-button"
                aria-expanded={profileOpen}
                onClick={() => setProfileOpen((open) => !open)}
              >
                <span className="avatar">{user?.name?.split(' ').map((part) => part[0]).join('')}</span>
                <span className="profile-copy"><strong>{user?.name}</strong><small>{user?.role}</small></span>
                <ChevronDown size={15} />
              </button>
              {profileOpen && (
                <div className="profile-menu">
                  <div className="profile-menu-user"><CircleUserRound size={17} /> {user?.isMock ? 'Signed in with mock authentication' : 'Signed in as staff'}</div>
                  <button onClick={logout}><LogOut size={16} /> Sign out</button>
                </div>
              )}
            </div>
          </div>
        </header>
        <main className="page-content"><Outlet /></main>
      </div>
    </div>
  )
}
