import { ReactNode, useEffect, useMemo, useState } from 'react'
import { PLATFORM } from '@shared/db'
import type { AppRef } from '@shared/models'
import {
  OnboardingChoices, OnboardingRow, PlaceChoice, STARTERS, SUGGESTED_DOMAINS, StarterChoice, StarterKey,
  buildOnboardingPlan, deferredChoices, onboardingRoute, starterDef,
} from '@shared/onboarding'
import { RULE_TABLES, getRule, normalizeDomain, windowsAppRef } from '@shared/repos/blocking'
import { DECK_TABLES, getDecks } from '@shared/repos/contemplations'
import { ONBOARDING_TABLES, applyGuide, dismissOnboarding, finishDeviceSetup, getOnboarding } from '@shared/repos/onboarding'
import { parseJsonObject } from '@shared/json'
import { AppPicker, PickedApp } from '../components/AppPicker'
import { Icon, IconName } from '../components/Icon'
import { Chip, ErrorText, Field, Switch, TextInput } from '../components/ui'
import { db, useAction, useQuery, useSyncStatus } from '../lib/hooks'

/**
 * Decides once per launch whether to show the setup guide or this device's
 * setup (shared/CONTRACTS.md, Onboarding). The open screen stays up until the
 * user leaves it, even after the row it was opened for changes.
 */
export function useOnboardingFlow(): { open: 'guide' | 'device_setup' | null; show: (screen: 'guide' | null) => void } {
  const sync = useSyncStatus()
  const hasSynced = sync?.hasSynced ?? false
  const [open, setOpen] = useState<'guide' | 'device_setup' | null>(null)
  const [decided, setDecided] = useState(false)
  useEffect(() => {
    if (decided) return
    let alive = true
    // Read after the first sync has landed, never from a query made before it.
    void (async () => {
      const onboarding = await getOnboarding(db)
      const taskCount = Number((await db.getOptional<{ n: number }>("SELECT COUNT(*) AS n FROM tasks WHERE status = 'active'"))?.n ?? 0)
      const route = onboardingRoute({ platform: PLATFORM, hasSynced, onboarding, taskCount })
      if (!alive || route === 'wait') return
      setDecided(true)
      if (route === 'auto_dismiss') await dismissOnboarding(db)
      else if (route === 'guide' || route === 'device_setup') setOpen(route)
    })()
    return () => {
      alive = false
    }
  }, [decided, hasSynced])
  return { open, show: (screen) => { setDecided(true); setOpen(screen) } }
}

// ── Shared layout ─────────────────────────────────────────────────────────

function Page({ step, total, title, children, footer }: { step?: number; total?: number; title: string; children: ReactNode; footer: ReactNode }) {
  return (
    <div className="shell">
      <main className="main">
      <div className="page" style={{ maxWidth: 640, paddingTop: 32 }}>
        <div className="stack lg">
          {step != null && total != null && (
            <div className="row" aria-label={`Step ${step + 1} of ${total}`}>
              {Array.from({ length: total }, (_, i) => (
                <div key={i} className="progress grow"><div style={{ width: i <= step ? '100%' : '0%' }} /></div>
              ))}
            </div>
          )}
          <h1 className="title-lg" style={{ fontSize: 26 }}>{title}</h1>
          {children}
          <div className="row between" style={{ marginTop: 8 }}>{footer}</div>
        </div>
      </div>
      </main>
    </div>
  )
}

function Point({ icon, title, children }: { icon: IconName; title: string; children: ReactNode }) {
  return (
    <div className="card row top" style={{ gap: 14 }}>
      <Icon name={icon} style={{ color: 'var(--primary)', marginTop: 2 }} />
      <div className="stack sm grow">
        <div className="title-sm">{title}</div>
        <div className="small muted">{children}</div>
      </div>
    </div>
  )
}

function TimeField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <Field label={label}>
      <input className="input" type="time" value={value} onChange={(e) => onChange(e.target.value)} />
    </Field>
  )
}

const STARTER_ICONS: Record<StarterKey, IconName> = {
  morning_routine: 'tasks', morning_walk: 'place', meditation: 'meditation', journaling: 'article', groceries: 'cart', five_remembrances: 'history',
}

function isValidPlace(lat: string, lng: string): PlaceChoice | null {
  const la = Number(lat)
  const lo = Number(lng)
  if (!lat.trim() || !lng.trim() || !Number.isFinite(la) || !Number.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) return null
  return { latitude: la, longitude: lo }
}

/** Latitude / longitude with "use this PC's location". */
function PlaceInput({ title, help, lat, lng, onChange }: { title: string; help: ReactNode; lat: string; lng: string; onChange: (lat: string, lng: string) => void }) {
  const [locating, setLocating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const useCurrent = async () => {
    setLocating(true)
    setError(null)
    const s = await window.api.location.refresh()
    setLocating(false)
    if (s.lastFix && s.access === 'allowed') onChange(s.lastFix.latitude.toFixed(6), s.lastFix.longitude.toFixed(6))
    else setError(s.error ?? 'Location unavailable')
  }
  return (
    <div className="card stack">
      <div className="title-sm">{title}</div>
      <div className="small muted">{help}</div>
      <div className="grid2">
        <Field label="Latitude"><TextInput value={lat} onChange={(v) => onChange(v, lng)} placeholder="40.7128" /></Field>
        <Field label="Longitude"><TextInput value={lng} onChange={(v) => onChange(lat, v)} placeholder="-74.0060" /></Field>
      </div>
      <div><button className="btn tonal sm" disabled={locating} onClick={() => void useCurrent()}><Icon name="place" size="sm" />{locating ? 'Locating…' : "Use this PC's location"}</button></div>
      <ErrorText error={error} />
    </div>
  )
}

const toRef = (a: PickedApp): AppRef => ({ ...windowsAppRef(a.exe, a.name), ...(a.path ? { path: a.path } : {}) })
const toPicked = (a: AppRef): PickedApp => ({ exe: String(a.exe), name: String(a.name ?? a.exe), path: (a.path as string) ?? null })
const togglePicked = (list: PickedApp[], app: PickedApp) => list.some((a) => a.exe === app.exe) ? list.filter((a) => a.exe !== app.exe) : [...list, app]

async function enableContemplationsHere(): Promise<void> {
  const s = await window.api.settings.getContemplation()
  if (!s.enabled) await window.api.settings.setContemplation({ ...s, enabled: true })
}

// ── Setup guide ───────────────────────────────────────────────────────────

type GuideStep = 'welcome' | 'starters' | 'places' | 'meditation' | 'blocking' | 'review' | 'done'

/** The first-time setup guide: what Brainfence does, then a starter set of tasks and a rule. */
export function GuideScreen({ onExit }: { onExit: () => void }) {
  const [step, setStep] = useState<GuideStep>('welcome')
  const [starters, setStarters] = useState<Partial<Record<StarterKey, StarterChoice>>>(
    Object.fromEntries(STARTERS.map((s) => [s.key, {}])),
  )
  const [home, setHome] = useState({ lat: '', lng: '' })
  const [store, setStore] = useState({ lat: '', lng: '' })
  const [companions, setCompanions] = useState<PickedApp[]>([])
  const [blockedApps, setBlockedApps] = useState<PickedApp[]>([])
  const [domains, setDomains] = useState<string[]>([])
  const [domainInput, setDomainInput] = useState('')
  const action = useAction()

  useEffect(() => {
    void window.api.settings.getHome().then((h) => h && setHome({ lat: String(h.latitude), lng: String(h.longitude) }))
  }, [])

  const on = (key: StarterKey) => key in starters
  const needsPlaces = on('morning_walk') || on('groceries')
  const steps: GuideStep[] = ['welcome', 'starters', ...(needsPlaces ? ['places' as const] : []), ...(on('meditation') ? ['meditation' as const] : []), 'blocking', 'review', 'done']
  const index = steps.indexOf(step)
  const next = () => setStep(steps[index + 1])
  const back = () => setStep(steps[index - 1])

  const homePlace = isValidPlace(home.lat, home.lng)
  const choices: OnboardingChoices = {
    starters,
    home: homePlace ? { ...homePlace, radius_meters: 100 } : null,
    store: isValidPlace(store.lat, store.lng),
    companion_apps: companions.map(toRef),
    blocked_apps: blockedApps.map(toRef),
    blocked_domains: domains,
  }
  const plan = useMemo(() => buildOnboardingPlan(choices), [JSON.stringify(choices)]) // eslint-disable-line react-hooks/exhaustive-deps

  const create = () => action.run(async () => {
    await applyGuide(db, plan)
    if (homePlace && on('morning_walk')) {
      const existing = await window.api.settings.getHome()
      await window.api.settings.setHome({ ...homePlace, radiusMeters: existing?.radiusMeters ?? 100 })
    }
    if (plan.add_remembrances) await enableContemplationsHere()
    await window.api.blocking.reevaluate()
    setStep('done')
  })

  const skip = async () => {
    await dismissOnboarding(db)
    onExit()
  }

  const addDomain = () => {
    const d = normalizeDomain(domainInput)
    if (d && !domains.includes(d)) setDomains([...domains, d])
    setDomainInput('')
  }

  const nav = (nextLabel = 'Next', disabled = false) => (
    <>
      <button className="btn text" onClick={back}>Back</button>
      <button className="btn" onClick={next} disabled={disabled}>{nextLabel}</button>
    </>
  )

  switch (step) {
    case 'welcome':
      return (
        <Page title="Welcome to Brainfence" footer={<><button className="btn text" onClick={() => void skip()}>Skip setup</button><button className="btn" onClick={next}>Get started</button></>}>
          <div className="muted">Brainfence keeps distracting apps and websites locked until you've done the things that matter to you today.</div>
          <div className="stack">
            <Point icon="tasks" title="Tasks with a time window">Each task has a window: it can be done from its start time, and it's due by its end time. Repeating tasks come back every day.</Point>
            <Point icon="lock" title="Blocking rules">A rule lists apps and sites to block, and the tasks that unlock them. Once a task is past due and not done, the rule's apps and sites stay blocked until you finish it.</Point>
            <Point icon="sync" title="On all your devices">Tasks and rules sync to your phone and computers. Each device blocks its own apps.</Point>
          </div>
          <div className="small muted">The next few steps set up a small starter routine. You can change any of it later.</div>
        </Page>
      )

    case 'starters':
      return (
        <Page step={index - 1} total={steps.length - 2} title="Pick your starter tasks" footer={nav('Next', Object.keys(starters).length === 0)}>
          <div className="muted">Turn on the ones you want. Tasks marked "Unlocks apps" will be part of your blocking rule.</div>
          <div className="stack">
            {STARTERS.map((def) => {
              const choice = starters[def.key]
              const enabled = choice != null
              return (
                <div key={def.key} className={`card ${enabled ? '' : 'outlined'} stack`}>
                  <div className="row top" style={{ gap: 14 }}>
                    <Icon name={STARTER_ICONS[def.key]} style={{ color: 'var(--primary)', marginTop: 2 }} />
                    <div className="grow stack sm">
                      <div className="row"><span className="title-sm">{def.title}</span>{def.blocks && <span className="small primary-text">· Unlocks apps</span>}</div>
                      <div className="small muted">{def.summary}</div>
                      {def.key === 'morning_walk' && <div className="small muted">Needs a home location. This PC's location works, but your phone tracks it best.</div>}
                    </div>
                    <Switch checked={enabled} label={def.title} onChange={(v) => {
                      const nextStarters = { ...starters }
                      if (v) nextStarters[def.key] = {}
                      else delete nextStarters[def.key]
                      setStarters(nextStarters)
                    }} />
                  </div>
                  {enabled && def.hasWindow && (
                    <div className="grid2">
                      <TimeField label="Available from" value={choice.available_from ?? def.availableFrom!} onChange={(v) => setStarters({ ...starters, [def.key]: { ...choice, available_from: v } })} />
                      <TimeField label="Due by" value={choice.due_at ?? def.dueAt!} onChange={(v) => setStarters({ ...starters, [def.key]: { ...choice, due_at: v } })} />
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </Page>
      )

    case 'places':
      return (
        <Page step={index - 1} total={steps.length - 2} title="Where are home and the store?" footer={nav()}>
          <div className="muted">Location tasks complete on their own. Leave a place empty to finish it later on your phone.</div>
          {on('morning_walk') && (
            <PlaceInput title="Home" help="Morning Walk completes when you leave here. It's also saved as this PC's home location." lat={home.lat} lng={home.lng} onChange={(lat, lng) => setHome({ lat, lng })} />
          )}
          {on('groceries') && (
            <PlaceInput title="Grocery store" help="Brainfence reminds you of your list when you arrive here." lat={store.lat} lng={store.lng} onChange={(lat, lng) => setStore({ lat, lng })} />
          )}
          {plan.deferred_steps.length > 0 && (
            <div className="small muted">{plan.deferred_steps.map((d) => starterDef(d.key).title).join(' and ')} will be finished on a device with a location.</div>
          )}
        </Page>
      )

    case 'meditation':
      return (
        <Page step={index - 1} total={steps.length - 2} title="Meditation apps" footer={nav()}>
          <div className="muted">Meditation can use the built-in timer. If you use an app on this PC instead, pick it: time spent in it counts toward your ten minutes.</div>
          <AppPicker selected={companions} onToggle={(app) => setCompanions(togglePicked(companions, app))} />
        </Page>
      )

    case 'blocking': {
      const conditions = STARTERS.filter((s) => s.blocks && on(s.key))
      return (
        <Page step={index - 1} total={steps.length - 2} title="What should be blocked?" footer={nav()}>
          <div className="muted">
            {conditions.length > 0
              ? <>Until {conditions.map((s) => s.title).join(', ')} {conditions.length > 1 ? 'are' : 'is'} done, these will be blocked once each task is past due.</>
              : <>None of your starters unlock apps, so no rule will be made. Go back and turn on one marked "Unlocks apps" to block something.</>}
          </div>
          <div className="stack sm">
            <div className="section-label">Apps on this PC</div>
            <AppPicker selected={blockedApps} onToggle={(app) => setBlockedApps(togglePicked(blockedApps, app))} />
          </div>
          <div className="stack sm">
            <div className="section-label">Websites, in every browser</div>
            <div className="chips">
              {[...new Set([...SUGGESTED_DOMAINS, ...domains])].map((d) => (
                <Chip key={d} selected={domains.includes(d)} onClick={() => setDomains(domains.includes(d) ? domains.filter((x) => x !== d) : [...domains, d])}>{d}</Chip>
              ))}
            </div>
            <div className="row">
              <div className="grow"><TextInput value={domainInput} onChange={setDomainInput} placeholder="Add a site, e.g. news.ycombinator.com" onEnter={addDomain} /></div>
              <button className="btn tonal sm" onClick={addDomain} disabled={!normalizeDomain(domainInput)}>Add</button>
            </div>
          </div>
          <Point icon="timer" title="Loosening takes a day">Adding to a rule applies at once. Removing an app, a site or a task waits 24 hours, so blocking can't be undone on a whim.</Point>
        </Page>
      )
    }

    case 'review':
      return (
        <Page step={index - 1} total={steps.length - 2} title="Ready to go" footer={<><button className="btn text" onClick={back}>Back</button><button className="btn" disabled={action.busy} onClick={() => void create()}>{action.busy ? 'Creating…' : 'Create'}</button></>}>
          <div className="stack sm">
            {plan.tasks.map((t) => (
              <div key={t.key} className="card row">
                <Icon name={STARTER_ICONS[t.key]} style={{ color: 'var(--primary)' }} />
                <div className="grow">
                  <div className="title-sm">{t.title}</div>
                  <div className="small muted">{t.available_from ? `Daily, ${t.available_from}–${t.due_at}` : 'Shopping list'}{t.is_blocking_condition && plan.rule ? ' · unlocks apps' : ''}</div>
                </div>
              </div>
            ))}
            {plan.add_remembrances && <div className="card row"><Icon name="history" style={{ color: 'var(--primary)' }} /><div className="grow title-sm">The Five Remembrances, delivered on this PC</div></div>}
            {plan.deferred_steps.map((d) => (
              <div key={d.key} className="card outlined row"><Icon name="place" /><div className="grow"><div className="title-sm">{starterDef(d.key).title}</div><div className="small muted">Finished on a device with a location</div></div></div>
            ))}
            {plan.rule
              ? <div className="card primary row"><Icon name="lock" /><div className="grow"><div className="title-sm">Rule "{plan.rule.name}"</div><div className="small">{[plan.rule.blocked_apps.length && `${plan.rule.blocked_apps.length} app${plan.rule.blocked_apps.length > 1 ? 's' : ''}`, plan.rule.blocked_domains.length && plan.rule.blocked_domains.join(', ')].filter(Boolean).join(' · ')}</div></div></div>
              : <div className="small muted">No blocking rule: pick something to block and a task that unlocks it. You can add rules later under Blocking rules.</div>}
          </div>
          <ErrorText error={action.error} />
        </Page>
      )

    case 'done':
      return (
        <Page title="You're set up" footer={<><span />{<button className="btn" onClick={onExit}>Go to my tasks</button>}</>}>
          <div className="stack">
            <Point icon="play" title="Doing a task">Click a task on the Tasks page to start it. Routines walk you through each step, and meditation runs a timer.</Point>
            <Point icon="block" title="When something is blocked">Brainfence covers the app and lists what's left to do. Finish the task and it unlocks straight away.</Point>
            {plan.rule && plan.rule.blocked_domains.length > 0 && <Point icon="globe" title="Website blocking">Windows needs a one-time permission to block sites. Settings → Website blocking.</Point>}
            {plan.add_remembrances && <Point icon="history" title="Contemplations">A remembrance arrives as a notification a few times a day. Change how often under Contemplations.</Point>}
            <Point icon="settings" title="Run this again">Settings → Run setup guide.</Point>
          </div>
        </Page>
      )
  }
}

// ── Device setup ──────────────────────────────────────────────────────────

/** A device joining an account that finished the guide elsewhere: the per-device parts. */
export function DeviceSetupScreen({ onExit }: { onExit: () => void }) {
  const { data } = useQuery(async () => {
    const row = await getOnboarding(db)
    const rule = row?.blockingRuleId ? await getRule(db, row.blockingRuleId) : null
    const meditation = row?.meditationTaskId
      ? await db.getOptional<{ verification_config: string | null }>('SELECT verification_config FROM tasks WHERE id = ?', [row.meditationTaskId])
      : null
    const decks = (await getDecks(db)).filter((d) => d.isEnabled)
    return {
      row, rule, decks,
      companions: meditation ? (parseJsonObject(meditation.verification_config).companion_apps ?? []).filter((a: any) => a?.platform === PLATFORM) as AppRef[] : null,
      home: await window.api.settings.getHome(),
      contemplations: await window.api.settings.getContemplation(),
    }
  }, [...ONBOARDING_TABLES, ...RULE_TABLES, ...DECK_TABLES, 'tasks'], [], null)
  if (!data) return null
  return <DeviceSetupForm key={data.row?.blockingRuleId ?? 'none'} {...data} onExit={onExit} />
}

function DeviceSetupForm({ row, rule, companions: initialCompanions, decks, home: savedHome, contemplations, onExit }: {
  row: OnboardingRow | null
  rule: Awaited<ReturnType<typeof getRule>>
  companions: AppRef[] | null
  decks: unknown[]
  home: { latitude: number; longitude: number; radiusMeters: number } | null
  contemplations: { enabled: boolean }
  onExit: () => void
}) {
  const [blockedApps, setBlockedApps] = useState<PickedApp[]>([])
  const [companions, setCompanions] = useState<PickedApp[]>((initialCompanions ?? []).map(toPicked))
  const [home, setHome] = useState({ lat: savedHome ? String(savedHome.latitude) : '', lng: savedHome ? String(savedHome.longitude) : '' })
  const [store, setStore] = useState({ lat: '', lng: '' })
  const deferred = row?.deferredSteps ?? []
  const [keep, setKeep] = useState<Set<StarterKey>>(new Set(deferred.map((d) => d.key)))
  const [deliver, setDeliver] = useState(true)
  const action = useAction()

  const alreadyBlocked = rule?.blockedAppRefs.filter((a) => a.platform === PLATFORM).map((a) => String(a.name ?? a.exe)) ?? []
  const homePlace = isValidPlace(home.lat, home.lng)
  const kept = deferred.filter((d) => keep.has(d.key))
  const deferredPlan = buildOnboardingPlan(deferredChoices(kept, homePlace ? { ...homePlace, radius_meters: savedHome?.radiusMeters ?? 100 } : null, isValidPlace(store.lat, store.lng)))
  const askContemplations = decks.length > 0 && !contemplations.enabled
  const wantsHome = !savedHome || keep.has('morning_walk')

  const finish = (skip: boolean) => action.run(async () => {
    if (skip) {
      await finishDeviceSetup(db, { blockedApps: [], companionApps: null, deferredPlan: null })
    } else {
      await finishDeviceSetup(db, {
        blockedApps: blockedApps.map(toRef),
        companionApps: initialCompanions ? companions.map(toRef) : null,
        deferredPlan,
      })
      if (homePlace && (homePlace.latitude !== savedHome?.latitude || homePlace.longitude !== savedHome?.longitude)) {
        await window.api.settings.setHome({ ...homePlace, radiusMeters: savedHome?.radiusMeters ?? 100 })
      }
      if (askContemplations && deliver) await enableContemplationsHere()
      await window.api.blocking.reevaluate()
    }
    onExit()
  })

  return (
    <Page title="Set up this PC" footer={<><button className="btn text" disabled={action.busy} onClick={() => void finish(true)}>Skip</button><button className="btn" disabled={action.busy} onClick={() => void finish(false)}>{action.busy ? 'Saving…' : 'Done'}</button></>}>
      <div className="muted">Your tasks and rules are already here from your other devices. A few things are specific to this PC.</div>
      {rule && (
        <div className="stack sm">
          <div className="section-label">Block on this PC with "{rule.name}"</div>
          {alreadyBlocked.length > 0 && <div className="small muted">Already blocked here: {alreadyBlocked.join(', ')}</div>}
          <AppPicker selected={blockedApps} onToggle={(app) => setBlockedApps(togglePicked(blockedApps, app))} />
        </div>
      )}
      {initialCompanions && (
        <div className="stack sm">
          <div className="section-label">Meditation apps on this PC</div>
          <div className="small muted">Time spent in these counts toward your Meditation task.</div>
          <AppPicker selected={companions} onToggle={(app) => setCompanions(togglePicked(companions, app))} />
        </div>
      )}
      {deferred.map((d) => (
        <div key={d.key} className="card row">
          <Icon name={STARTER_ICONS[d.key]} style={{ color: 'var(--primary)' }} />
          <div className="grow">
            <div className="title-sm">Add {starterDef(d.key).title}</div>
            <div className="small muted">Chosen on another device that couldn't set its location.</div>
          </div>
          <Switch checked={keep.has(d.key)} label={`Add ${starterDef(d.key).title}`} onChange={(v) => {
            const next = new Set(keep)
            if (v) next.add(d.key)
            else next.delete(d.key)
            setKeep(next)
          }} />
        </div>
      ))}
      {wantsHome && (
        <PlaceInput title="Home" help={keep.has('morning_walk') ? 'Morning Walk completes when you leave here. Also used for home-only blocking.' : 'Used for tasks that only block at home.'} lat={home.lat} lng={home.lng} onChange={(lat, lng) => setHome({ lat, lng })} />
      )}
      {keep.has('groceries') && (
        <PlaceInput title="Grocery store" help="Brainfence reminds you of your list when you arrive here." lat={store.lat} lng={store.lng} onChange={(lat, lng) => setStore({ lat, lng })} />
      )}
      {deferredPlan.deferred_steps.length > 0 && (
        <div className="small muted">Without a location, {deferredPlan.deferred_steps.map((d) => starterDef(d.key).title).join(' and ')} will be asked about again next time.</div>
      )}
      {askContemplations && (
        <div className="card row">
          <div className="grow">
            <div className="title-sm">Contemplations on this PC</div>
            <div className="small muted">Deliver your contemplation decks here as notifications.</div>
          </div>
          <Switch checked={deliver} onChange={setDeliver} label="Contemplations on this PC" />
        </div>
      )}
      <ErrorText error={action.error} />
    </Page>
  )
}
