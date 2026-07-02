import { useCallback, useEffect, useRef, useState } from 'react'
import OrgsPage from './pages/OrgsPage'
import ServicesPage from './pages/ServicesPage'
import CredentialsPage from './pages/CredentialsPage'
import OnboardingPage from './pages/OnboardingPage'
import UsersPage from './pages/UsersPage'
import BimeLocationsPage from './pages/BimeLocationsPage'
import BimeMetadataPage from './pages/BimeMetadataPage'
import BimeProductsPage from './pages/BimeProductsPage'
import BimeStockPage from './pages/BimeStockPage'
import { vassago } from './api/vassago'
import { useApiCall } from './hooks/useApiCall'
import { parseJwtClaims, derivePermissions, jwtExp } from './auth'
import type { Permissions } from './auth'
import type { LoginRequest } from './types'

type Page = 'orgs' | 'services' | 'credentials' | 'onboarding' | 'users'
  | 'bime-locations' | 'bime-metadata' | 'bime-products' | 'bime-stock'

const NAV: { service: string; items: { id: Page; label: string; perm: keyof Permissions }[] }[] = [
  {
    service: 'Raum',
    items: [
      { id: 'orgs',        label: 'Organizations', perm: 'canManage'  },
      { id: 'services',    label: 'Services',       perm: 'canManage'  },
      { id: 'credentials', label: 'Credentials',    perm: 'canManage'  },
      { id: 'onboarding',  label: 'Onboarding',     perm: 'canOnboard' },
    ],
  },
  {
    service: 'Vassago',
    items: [
      { id: 'users', label: 'Users', perm: 'canViewUsers' },
    ],
  },
  {
    service: 'Bime',
    items: [
      { id: 'bime-locations', label: 'Locations', perm: 'canViewBime'        },
      { id: 'bime-metadata',  label: 'Metadata',   perm: 'canViewBimeCatalog' },
      { id: 'bime-products',  label: 'Products',   perm: 'canViewBime'        },
      { id: 'bime-stock',     label: 'Stock',      perm: 'canViewBime'        },
    ],
  },
]

const EMPTY_PERMISSIONS: Permissions = {
  canManage: false, canOnboard: false,
  canViewUsers: false, canCreateUsers: false, canEditUsers: false, canOffboardUsers: false,
  canViewBime: false, canViewBimeCatalog: false, canManageBime: false,
}

function safePermissions(token: string): Permissions {
  try { return derivePermissions(parseJwtClaims(token)) } catch { return EMPTY_PERMISSIONS }
}

export default function App() {
  const [page, setPage] = useState<Page>('orgs')
  const [token, setToken] = useState<string | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const timerRef = useRef<number | null>(null)

  const loginCall = useApiCall<{ token: string }>()
  const [loginForm, setLoginForm] = useState<LoginRequest>({ orgId: '', username: '', password: '' })

  const permissions: Permissions = token ? safePermissions(token) : EMPTY_PERMISSIONS

  const visibleGroups = NAV
    .map(g => ({ ...g, items: g.items.filter(i => permissions[i.perm]) }))
    .filter(g => g.items.length > 0)

  const allVisibleItems = visibleGroups.flatMap(g => g.items)
  const activePage: Page = allVisibleItems.find(i => i.id === page)
    ? page
    : (allVisibleItems[0]?.id ?? 'orgs')

  const startRefreshLoop = useCallback(function loop(t: string) {
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    const ms = Math.max(5_000, (jwtExp(t) - Date.now() / 1_000 - 30) * 1_000)
    timerRef.current = window.setTimeout(async () => {
      try { const r = await vassago.refresh(); setToken(r.token); loop(r.token) }
      catch { setToken(null) }
    }, ms)
  }, [])

  useEffect(() => {
    vassago.refresh()
      .then(r => { setToken(r.token); startRefreshLoop(r.token) })
      .catch(() => {})
      .finally(() => setAuthReady(true))
    return () => { if (timerRef.current !== null) window.clearTimeout(timerRef.current) }
  }, [startRefreshLoop])

  function handleLogin() {
    loginCall.call(async () => {
      const r = await vassago.login(loginForm)
      setToken(r.token)
      startRefreshLoop(r.token)
      return r
    })
  }

  function handleLogout() {
    if (!token) return
    if (timerRef.current !== null) window.clearTimeout(timerRef.current)
    vassago.logout(token).catch(() => {})
    setToken(null)
  }

  if (!authReady) return null

  if (token === null) {
    return (
      <div className="login-wrap">
        <div className="login-card">
          <h1>Kenoma</h1>
          <p>Administration Console</p>
          <div className="login-fields">
            <div className="field">
              <label>Org ID</label>
              <input
                value={loginForm.orgId}
                onChange={e => setLoginForm(f => ({ ...f, orgId: e.target.value }))}
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <div className="field">
              <label>Username</label>
              <input
                value={loginForm.username}
                onChange={e => setLoginForm(f => ({ ...f, username: e.target.value }))}
                autoComplete="username"
              />
            </div>
            <div className="field">
              <label>Password</label>
              <input
                type="password"
                value={loginForm.password}
                onChange={e => setLoginForm(f => ({ ...f, password: e.target.value }))}
                autoComplete="current-password"
                onKeyDown={e => { if (e.key === 'Enter') handleLogin() }}
              />
            </div>
          </div>
          <button
            className="btn btn-primary btn-full"
            disabled={
              loginCall.state.status === 'loading' ||
              !loginForm.orgId.trim() ||
              !loginForm.username.trim() ||
              !loginForm.password
            }
            onClick={handleLogin}
          >
            {loginCall.state.status === 'loading' ? 'Logging in…' : 'Log in'}
          </button>
          {loginCall.state.status === 'error' && (
            <div className="error">{loginCall.state.message}</div>
          )}
        </div>
      </div>
    )
  }

  return (
    <div className="app-layout">
      <aside className="sidebar">
        <div className="sidebar-logo">Kenoma</div>
        <nav className="sidebar-nav">
          {visibleGroups.length === 0 ? (
            <div className="sidebar-empty">No permissions assigned.</div>
          ) : (
            visibleGroups.map(group => (
              <div key={group.service} className="sidebar-group">
                <div className="sidebar-group-label">{group.service}</div>
                {group.items.map(item => (
                  <button
                    key={item.id}
                    className={`sidebar-item${activePage === item.id ? ' active' : ''}`}
                    onClick={() => setPage(item.id)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            ))
          )}
        </nav>
        <div className="sidebar-footer">
          <span className="session-dot" />
          <button className="btn btn-ghost" onClick={handleLogout}>Log out</button>
        </div>
      </aside>
      <main className="content">
        {activePage === 'orgs'           && <OrgsPage token={token} />}
        {activePage === 'services'       && <ServicesPage token={token} />}
        {activePage === 'credentials'    && <CredentialsPage token={token} />}
        {activePage === 'onboarding'     && <OnboardingPage token={token} />}
        {activePage === 'users'          && <UsersPage token={token} permissions={permissions} />}
        {activePage === 'bime-locations' && <BimeLocationsPage token={token} permissions={permissions} />}
        {activePage === 'bime-metadata'  && <BimeMetadataPage token={token} permissions={permissions} />}
        {activePage === 'bime-products'  && <BimeProductsPage token={token} permissions={permissions} />}
        {activePage === 'bime-stock'     && <BimeStockPage token={token} permissions={permissions} />}
      </main>
    </div>
  )
}
