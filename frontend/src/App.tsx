import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import OrgsPage from './pages/OrgsPage'
import PricingPage from './pages/PricingPage'
import ServicesPage from './pages/ServicesPage'
import CredentialsPage from './pages/CredentialsPage'
import OnboardingPage from './pages/OnboardingPage'
import UsersPage from './pages/UsersPage'
import BimeLocationsPage from './pages/BimeLocationsPage'
import BimeMetadataPage from './pages/BimeMetadataPage'
import BimeProductsPage from './pages/BimeProductsPage'
import BimeStockPage from './pages/BimeStockPage'
import RecoverPage from './pages/RecoverPage'
import VerifyPage from './pages/VerifyPage'
import EmailConfirmPage from './pages/EmailConfirmPage'
import { vassago } from './api/vassago'
import { raum } from './api/raum'
import { bime } from './api/bime'
import { useApiCall } from './hooks/useApiCall'
import { parseJwtClaims, derivePermissions, jwtExp } from './auth'
import type { Permissions } from './auth'
import { parseRoute, buildPath } from './routing'
import type { LoginRequest } from './types'
import { ToastProvider } from './components/Toast'
import { Sidebar, type NavGroup } from './components/Sidebar'
import { Feedback } from './components/Feedback'
import {
  OrgsIcon, PricingIcon, ServicesIcon, CredentialsIcon, OnboardingIcon, UsersIcon,
  LocationsIcon, MetadataIcon, ProductsIcon, StockIcon,
} from './components/icons'

type Page = 'orgs' | 'pricing' | 'services' | 'credentials' | 'onboarding' | 'users'
  | 'bime-locations' | 'bime-metadata' | 'bime-products' | 'bime-stock'

const NAV: { labelKey: string; items: { id: Page; labelKey: string; perm: keyof Permissions; icon: NavGroup['items'][number]['icon'] }[] }[] = [
  {
    labelKey: 'nav.raum',
    items: [
      { id: 'orgs',        labelKey: 'nav.orgs',        perm: 'canManage',  icon: OrgsIcon },
      { id: 'pricing',     labelKey: 'nav.pricing',     perm: 'canManage',  icon: PricingIcon },
      { id: 'services',    labelKey: 'nav.services',    perm: 'canManage',  icon: ServicesIcon },
      { id: 'credentials', labelKey: 'nav.credentials', perm: 'canManage',  icon: CredentialsIcon },
      { id: 'onboarding',  labelKey: 'nav.onboarding',  perm: 'canOnboard', icon: OnboardingIcon },
    ],
  },
  {
    labelKey: 'nav.vassago',
    items: [
      { id: 'users', labelKey: 'nav.users', perm: 'canViewUsers', icon: UsersIcon },
    ],
  },
  {
    labelKey: 'nav.bime',
    items: [
      { id: 'bime-locations', labelKey: 'nav.bimeLocations', perm: 'canViewBime',        icon: LocationsIcon },
      { id: 'bime-metadata',  labelKey: 'nav.bimeMetadata',  perm: 'canViewBimeCatalog', icon: MetadataIcon },
      { id: 'bime-products',  labelKey: 'nav.bimeProducts',  perm: 'canViewBime',        icon: ProductsIcon },
      { id: 'bime-stock',     labelKey: 'nav.bimeStock',     perm: 'canViewBime',        icon: StockIcon },
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

function AppShell() {
  const { t } = useTranslation()
  const initialRoute = parseRoute(window.location.pathname)
  const [page, setPage] = useState<Page>((initialRoute.page as Page) || 'orgs')
  const [urlOrgId, setUrlOrgId] = useState<string | null>(initialRoute.orgId)
  const [authView, setAuthView] = useState<'login' | 'recover'>('login')
  const [token, setToken] = useState<string | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const timerRef = useRef<number | null>(null)
  const freshLoginRef = useRef(false)

  const loginCall = useApiCall<{ token: string }>()
  const [loginForm, setLoginForm] = useState<LoginRequest>({ orgId: initialRoute.orgId ?? '', username: '', password: '' })

  const permissions: Permissions = token ? safePermissions(token) : EMPTY_PERMISSIONS

  const visibleGroups: NavGroup[] = NAV
    .map(g => ({ labelKey: g.labelKey, items: g.items.filter(i => permissions[i.perm]) }))
    .filter(g => g.items.length > 0)

  const allVisibleItems = visibleGroups.flatMap(g => g.items)
  const activePage: Page = allVisibleItems.find(i => i.id === page)
    ? page
    : ((allVisibleItems[0]?.id as Page) ?? 'orgs')

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

  // Back/forward navigation: re-derive org id and page from the URL.
  useEffect(() => {
    function onPopState() {
      const route = parseRoute(window.location.pathname)
      setUrlOrgId(route.orgId)
      if (route.page) setPage(route.page as Page)
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  // While logged out, keep the login form's org id in sync with whatever org the URL names.
  useEffect(() => {
    if (token) return
    if (urlOrgId) setLoginForm(f => ({ ...f, orgId: urlOrgId }))
  }, [urlOrgId, token])

  // Reconcile the URL with the authenticated session: the URL's org id must match the JWT's,
  // since the JWT (not the URL) is what actually scopes every API call to an org. If they
  // disagree — e.g. a stale session still open in this tab while the URL points at another org —
  // the session is dropped rather than silently serving org A's data under org B's URL.
  useEffect(() => {
    if (!authReady || !token) return
    let claims: ReturnType<typeof parseJwtClaims>
    try { claims = parseJwtClaims(token) } catch { setToken(null); return }

    const freshLogin = freshLoginRef.current
    freshLoginRef.current = false

    if (!freshLogin && urlOrgId && urlOrgId !== claims.orgId) {
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
      vassago.logout(token).catch(() => {})
      setToken(null)
      return
    }

    const currentPageInUrl = parseRoute(window.location.pathname).page
    if (urlOrgId !== claims.orgId || currentPageInUrl !== activePage) {
      setUrlOrgId(claims.orgId)
      window.history.replaceState(null, '', buildPath(claims.orgId, activePage))
    }
  }, [authReady, token, urlOrgId, activePage])

  function handleLogin() {
    loginCall.call(async () => {
      const r = await vassago.login(loginForm)
      freshLoginRef.current = true
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
    if (urlOrgId) window.history.replaceState(null, '', buildPath(urlOrgId))
  }

  function handleNavigate(id: string) {
    setPage(id as Page)
    if (urlOrgId) window.history.pushState(null, '', buildPath(urlOrgId, id))
  }

  if (!authReady) return null

  if (token === null) {
    if (authView === 'recover') {
      return <RecoverPage onBack={() => setAuthView('login')} />
    }
    return (
      <div className="login-wrap">
        <div className="login-card">
          <h1>{t('login.title')}</h1>
          <p>{t('login.subtitle')}</p>
          <div className="login-fields">
            <div className="field">
              <label>{t('login.orgId')}</label>
              <input
                value={loginForm.orgId}
                onChange={e => setLoginForm(f => ({ ...f, orgId: e.target.value }))}
                placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                autoComplete="off"
                spellCheck={false}
              />
            </div>
            <div className="field">
              <label>{t('login.username')}</label>
              <input
                value={loginForm.username}
                onChange={e => setLoginForm(f => ({ ...f, username: e.target.value }))}
                autoComplete="username"
              />
            </div>
            <div className="field">
              <label>{t('login.password')}</label>
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
            {loginCall.state.status === 'loading' ? t('login.submitting') : t('login.submit')}
          </button>
          {loginCall.state.status === 'error' && <Feedback state={loginCall.state} />}
          <div className="login-links">
            <button className="link-btn" onClick={() => setAuthView('recover')} type="button">{t('login.forgotPassword')}</button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="app-layout">
      <Sidebar groups={visibleGroups} activeId={activePage} onSelect={handleNavigate} onLogout={handleLogout} />
      <main className="content">
        {activePage === 'orgs'           && <OrgsPage token={token} />}
        {activePage === 'pricing'        && <PricingPage token={token} />}
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

export default function App() {
  const verifyParams = new URLSearchParams(window.location.search)
  const isVerifyRoute = window.location.pathname === '/verify' && verifyParams.has('token')
  if (isVerifyRoute) {
    switch (verifyParams.get('type')) {
      case 'billing':
        return <EmailConfirmPage i18nPrefix="billingEmailVerifyPage" confirm={(orgId, token) => raum.orgs.confirmBillingEmail(orgId, { token })} />
      case 'contact':
        return <EmailConfirmPage i18nPrefix="contactEmailVerifyPage" confirm={(orgId, token) => raum.orgs.confirmContactEmail(orgId, { token })} />
      case 'location':
        return <EmailConfirmPage i18nPrefix="locationEmailVerifyPage" confirm={(orgId, token) => bime.locations.confirmNotificationEmail({ orgId, token })} />
      default:
        return <VerifyPage />
    }
  }

  return (
    <ToastProvider>
      <AppShell />
    </ToastProvider>
  )
}
