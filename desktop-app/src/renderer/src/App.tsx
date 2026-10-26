import { useEffect } from 'react'
import { Icon, IconName } from './components/Icon'
import { Loading, Logo } from './components/ui'
import { useAuth, useEnforcement, useSyncStatus } from './lib/hooks'
import { RouterProvider, routeParts, useRouter } from './lib/router'
import { AuthScreen } from './screens/Auth'
import { BlockingRulesScreen, RuleEditorScreen } from './screens/BlockingRules'
import { ContemplationsScreen, DeckEditorScreen } from './screens/Contemplations'
import { DebugScreen } from './screens/Debug'
import { HomeLocationScreen } from './screens/HomeLocation'
import { DeviceSetupScreen, GuideScreen, useOnboardingFlow } from './screens/Onboarding'
import { MetricEditorScreen, ReportEditorScreen } from './screens/Reports'
import { ReviewScreen } from './screens/Review'
import { SettingsScreen } from './screens/Settings'
import { TaskEditorScreen } from './screens/TaskEditor'
import { TaskListScreen } from './screens/TaskList'
import { TaskScreen } from './screens/TaskScreen'

export function App() {
  const auth = useAuth()
  if (auth.status === 'loading') return <Loading />
  if (auth.status === 'signedOut') return <AuthScreen />
  return (
    <RouterProvider initial="home">
      <Shell email={auth.email} />
    </RouterProvider>
  )
}

// The same four sections as Android's bottom bar.
const NAV: { route: string; label: string; icon: IconName }[] = [
  { route: 'home', label: 'Tasks', icon: 'tasks' },
  { route: 'blocking', label: 'Blocking rules', icon: 'block' },
  { route: 'review', label: 'Review', icon: 'article' },
  { route: 'settings', label: 'Settings', icon: 'settings' },
]

/** The sidebar section each route belongs to. */
const SECTION_OF: Record<string, string> = {
  task: 'home',
  editor: 'home',
  reports: 'review',
  contemplations: 'settings',
  'home-location': 'settings',
  debug: 'settings',
}

function Shell({ email }: { email: string | null }) {
  const { route, navigate } = useRouter()
  const enforcement = useEnforcement()
  const sync = useSyncStatus()
  const section = routeParts(route)[0]
  const navSection = SECTION_OF[section] ?? section
  const blockedCount = (enforcement?.state.blockedApps.length ?? 0) + (enforcement?.state.blockedDomains.length ?? 0)
  const onboarding = useOnboardingFlow()

  useEffect(() => {
    document.querySelector('.main')?.scrollTo(0, 0)
  }, [route])

  // The setup guide and device setup take over the window until the user leaves them.
  const leaveOnboarding = () => {
    onboarding.show(null)
    navigate('home', { reset: true })
  }
  if (onboarding.open === 'guide' || route === 'guide') return <GuideScreen onExit={leaveOnboarding} />
  if (onboarding.open === 'device_setup') return <DeviceSetupScreen onExit={leaveOnboarding} />

  return (
    <div className="shell">
      <nav className="nav">
        <div className="nav-brand">
          <Logo />
          <span>Brainfence</span>
        </div>
        {NAV.map((n) => (
          <button key={n.route} className={`nav-item ${navSection === n.route ? 'active' : ''}`} onClick={() => navigate(n.route, { reset: true })} title={n.label}>
            <Icon name={n.icon} />
            <span>{n.label}</span>
            {n.route === 'blocking' && blockedCount > 0 && <span className="badge small blocked-text">{blockedCount} blocked</span>}
          </button>
        ))}
        <div className="nav-spacer" />
        <div className="nav-foot" title={sync?.error ?? undefined}>
          <Icon name="sync" size="sm" style={{ color: sync?.connected ? 'var(--primary)' : sync?.error ? 'var(--error)' : 'var(--outline)' }} />
          <span>{sync?.connected ? (sync.uploading || sync.downloading ? 'Syncing…' : 'Synced') : sync?.connecting ? 'Connecting…' : 'Offline'}</span>
        </div>
        {email && <div className="nav-foot"><span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{email}</span></div>}
      </nav>
      <main className="main">
        <div className="page">
          <Route route={route} />
        </div>
      </main>
    </div>
  )
}

function Route({ route }: { route: string }) {
  const [section, a, b] = routeParts(route)
  switch (section) {
    case 'home': return <TaskListScreen />
    case 'task': return <TaskScreen key={a} taskId={a} />
    case 'editor': return <TaskEditorScreen key={a ?? 'new'} taskId={a ?? null} />
    case 'blocking': return a ? <RuleEditorScreen key={a} ruleId={a === 'new' ? null : a} /> : <BlockingRulesScreen />
    case 'contemplations': return a === 'deck' && b ? <DeckEditorScreen key={b} deckId={b} /> : <ContemplationsScreen />
    case 'review': return <ReviewScreen tab={a === 'reports' ? 'reports' : 'journal'} />
    case 'reports':
      if (a === 'metric' && b) return <MetricEditorScreen key={b} metricId={b} />
      return a ? <ReportEditorScreen key={a} reportId={a} /> : <ReviewScreen tab="reports" />
    case 'home-location': return <HomeLocationScreen />
    case 'settings': return <SettingsScreen />
    case 'debug': return <DebugScreen />
    default: return <TaskListScreen />
  }
}
