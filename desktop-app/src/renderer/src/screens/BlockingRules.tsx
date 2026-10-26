import { useEffect, useMemo, useState } from 'react'
import type { AppRef, BlockingRule, Task } from '@shared/models'
import {
  RULE_TABLES, cancelPendingChange, createRule, deleteRule, draftToChanges, getAllRules, getRule, normalizeDomain, scheduleRuleChange, windowsAppRef,
} from '@shared/repos/blocking'
import { parseJsonObject } from '@shared/json'
import { AppPicker, PickedApp } from '../components/AppPicker'
import { Icon } from '../components/Icon'
import { Banner, Chip, ConfirmDialog, ErrorText, Field, IconButton, Loading, Switch, TextInput, TopBar } from '../components/ui'
import { formatCountdown, plural } from '../lib/format'
import { cleanError, db, useEnforcement, useNow, useQuery } from '../lib/hooks'
import { useRouter } from '../lib/router'
import { prettyExe } from '../lib/apps'
import { useTasks } from './TaskList'

const TLDS = new Set(['com', 'org', 'net', 'co', 'app', 'tv', 'fm', 'xyz', 'io', 'me', 'de', 'uk'])
const GENERIC = new Set(['android', 'app', 'apps', 'mobile', 'lite', 'frontpage', 'watch', 'client', 'free', 'phone', 'ios', 'mac', 'desktop'])

/**
 * A readable name for another platform's app id — Android resolves labels
 * through its package manager, which isn't available here:
 * com.google.android.youtube → Youtube, com.reddit.frontpage → Reddit.
 */
function refLabel(ref: AppRef): string {
  if (ref.name) return String(ref.name)
  if (ref.platform === 'windows') return prettyExe(String(ref.exe ?? ''))
  const parts = String(ref.package ?? ref.bundle_id ?? '').split('.').filter(Boolean)
  const meaningful = parts.filter((p, i) => !(i === 0 && TLDS.has(p) && parts.length > 1) && !GENERIC.has(p.toLowerCase()))
  const pick = meaningful[meaningful.length - 1] ?? parts[parts.length - 1] ?? ''
  return pick.charAt(0).toUpperCase() + pick.slice(1)
}

/** "This PC: Discord · Android: Twitter, Reddit · Sites: x.com" */
function ruleSummary(rule: BlockingRule): string {
  const byPlatform = new Map<string, string[]>()
  for (const ref of rule.blockedAppRefs) byPlatform.set(ref.platform, [...(byPlatform.get(ref.platform) ?? []), refLabel(ref)])
  const parts: string[] = []
  const win = byPlatform.get('windows')
  if (win) parts.push(`This PC: ${win.join(', ')}`)
  for (const [platform, names] of byPlatform) if (platform !== 'windows') parts.push(`${PLATFORM_LABEL[platform] ?? platform}: ${names.join(', ')}`)
  if (rule.blockedDomains.length) parts.push(`Websites: ${rule.blockedDomains.join(', ')}`)
  return parts.join(' · ') || 'Nothing blocked yet'
}

const PLATFORM_LABEL: Record<string, string> = { android: 'Android', macos: 'macOS', windows: 'Windows' }

/** Port of BlockingRuleListScreen. */
export function BlockingRulesScreen() {
  const { navigate } = useRouter()
  const now = useNow(60_000)
  const enforcement = useEnforcement()
  const { data: rules, loading } = useQuery(() => getAllRules(db), RULE_TABLES, [], [] as BlockingRule[])
  const [target, setTarget] = useState<BlockingRule | null>(null)

  const confirmRemove = async (rule: BlockingRule) => {
    setTarget(null)
    if (rule.isActive) await scheduleRuleChange(db, rule.id, { is_active: 0 })
    else await deleteRule(db, rule.id)
    await window.api.blocking.reevaluate()
  }

  return (
    <>
      <TopBar title="Blocking rules" back={false} actions={<button className="btn create" onClick={() => navigate('blocking/new')}><Icon name="add" /> New rule</button>} />
      {loading ? <Loading /> : rules.length === 0 ? (
        <div className="empty stack sm">
          <div className="title-sm">No blocking rules yet</div>
          <div className="small">Click + to create a rule that blocks apps and websites until you complete your tasks.</div>
        </div>
      ) : (
        <div className="stack">
          {rules.map((rule) => {
            const blocking = enforcement?.state.blockingRuleIds.includes(rule.id)
            return (
              <div key={rule.id} className="card clickable" onClick={() => navigate(`blocking/${rule.id}`)}>
                <div className="row top">
                  <Icon name="block" style={{ color: blocking ? 'var(--blocked)' : rule.isActive ? 'var(--unlocked)' : 'var(--outline)' }} />
                  <div className="grow stack sm">
                    <div className="row wrap">
                      <span className="title-md">{rule.name}</span>
                      {!rule.isActive && <span className="small muted">· Inactive</span>}
                      {blocking && <span className="small blocked-text">· Blocking now</span>}
                    </div>
                    <div className="small muted">{ruleSummary(rule)}</div>
                    <div className="small muted">{plural(rule.conditionTaskIds.length, 'condition task')} · {rule.conditionLogic === 'any' ? 'any' : 'all'} must be done</div>
                    {rule.pendingChanges && <div className="small pending-text">Changes pending — apply {formatCountdown(rule.changesApplyAt, now)}</div>}
                  </div>
                  <IconButton
                    icon="delete"
                    title={rule.isActive ? 'Deactivate rule' : 'Delete rule'}
                    onClick={() => setTarget(rule)}
                  />
                </div>
              </div>
            )
          })}
        </div>
      )}
      {target && (
        <ConfirmDialog
          title={target.isActive ? 'Deactivate rule?' : 'Delete rule?'}
          message={target.isActive
            ? `"${target.name}" will stop blocking once its ${target.configLockHours}-hour lock expires. After that you can delete it.`
            : `"${target.name}" will be permanently deleted.`}
          confirmLabel={target.isActive ? 'Deactivate' : 'Delete'}
          danger
          onCancel={() => setTarget(null)}
          onConfirm={() => void confirmRemove(target)}
        />
      )}
    </>
  )
}

interface EditorState {
  name: string
  windowsApps: PickedApp[]
  otherApps: AppRef[]
  domains: string[]
  conditionTaskIds: string[]
  conditionLogic: string
  isActive: boolean
}

/** Port of BlockingRuleEditorScreen. Windows apps are edited here; other platforms' entries are kept. */
export function RuleEditorScreen({ ruleId }: { ruleId: string | null }) {
  const { back } = useRouter()
  const now = useNow(60_000)
  const { data: allTasks } = useTasks()
  const { data: rule, reload } = useQuery(() => (ruleId ? getRule(db, ruleId) : Promise.resolve(null)), RULE_TABLES, [ruleId], null as BlockingRule | null)
  const [s, setS] = useState<EditorState | null>(ruleId ? null : { name: '', windowsApps: [], otherApps: [], domains: [], conditionTaskIds: [], conditionLogic: 'all', isActive: true })
  const [domainDraft, setDomainDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  // Shopping lists never complete, so as a condition they'd block forever.
  const tasks = useMemo(() => allTasks.filter((t) => t.taskType !== 'shopping'), [allTasks])

  useEffect(() => {
    if (!rule || s) return
    setS({
      name: rule.name,
      windowsApps: rule.blockedAppRefs.filter((a) => a.platform === 'windows').map((a) => ({ exe: String(a.exe).toLowerCase(), name: refLabel(a), path: (a.path as string) ?? null })),
      otherApps: rule.blockedAppRefs.filter((a) => a.platform !== 'windows'),
      domains: rule.blockedDomains,
      conditionTaskIds: rule.conditionTaskIds,
      conditionLogic: rule.conditionLogic,
      isActive: rule.isActive,
    })
  }, [rule, s])

  if (!s) return (<><TopBar title="Edit Rule" /><Loading /></>)
  const update = (p: Partial<EditorState>) => {
    setS({ ...s, ...p })
    setError(null)
  }

  const addDomain = () => {
    const d = normalizeDomain(domainDraft)
    if (d && d.includes('.') && !s.domains.includes(d)) update({ domains: [...s.domains, d] })
    setDomainDraft('')
  }

  const save = async () => {
    if (!s.name.trim()) return setError('Name is required')
    setSaving(true)
    try {
      const draft = {
        name: s.name.trim(),
        blockedApps: [...s.otherApps, ...s.windowsApps.map((a) => ({ ...windowsAppRef(a.exe, a.name), ...(a.path ? { path: a.path } : {}) }))],
        blockedDomains: s.domains,
        conditionTaskIds: s.conditionTaskIds,
        conditionLogic: s.conditionLogic,
        isActive: s.isActive,
      }
      if (!ruleId) {
        const userId = await db.userId()
        if (!userId) throw new Error('Not authenticated')
        await createRule(db, userId, draft)
      } else {
        const result = await scheduleRuleChange(db, ruleId, draftToChanges(draft))
        if (result.applied === 'later') {
          await window.api.blocking.reevaluate()
          setNotice(`These changes loosen the rule, so they take effect ${formatCountdown(result.at)}.`)
          setSaving(false)
          return
        }
      }
      await window.api.blocking.reevaluate()
      back()
    } catch (e) {
      setError(cleanError(e))
    }
    setSaving(false)
  }

  const pending = rule?.pendingChanges ? parseJsonObject(rule.pendingChanges) : null

  return (
    <>
      <TopBar title={ruleId ? 'Edit Rule' : 'New Rule'} />
      <div className="stack lg">
        {rule?.pendingChanges && (
          <div className="card pending row">
            <Icon name="lock" />
            <div className="grow">
              <div className="title-sm">Changes pending</div>
              <div className="small">Apply {formatCountdown(rule.changesApplyAt, now)}{pending && Number(pending.is_active) === 0 ? ' · deactivates the rule' : ''}</div>
            </div>
            <button className="btn text sm" style={{ color: 'inherit' }} onClick={() => void cancelPendingChange(db, rule.id).then(reload)}>Cancel</button>
          </div>
        )}
        {ruleId && (
          <div className="small muted row"><Icon name="lock" size="sm" /> Changes that loosen an existing rule are time-locked and take {rule?.configLockHours ?? 24} hours to apply. Tightening applies immediately.</div>
        )}
        {notice && <Banner icon="lock" tone="pending" action={<button className="btn text sm" onClick={back}>Done</button>}>{notice}</Banner>}

        <Field label="Rule name"><TextInput value={s.name} onChange={(name) => update({ name })} autoFocus={!ruleId} /></Field>
        {ruleId && (
          <div className="row">
            <div className="grow">
              <div>Active</div>
              <div className="small muted">Turning a rule off is time-locked like any other loosening.</div>
            </div>
            <Switch checked={s.isActive} onChange={(isActive) => update({ isActive })} label="Active" />
          </div>
        )}

        <div className="stack sm">
          <div className="title-sm">Apps to block on this PC</div>
          <AppPicker
            selected={s.windowsApps}
            onToggle={(app) => update({ windowsApps: s.windowsApps.some((a) => a.exe === app.exe) ? s.windowsApps.filter((a) => a.exe !== app.exe) : [...s.windowsApps, app] })}
          />
          {s.otherApps.length > 0 && (
            <div className="small muted">
              Also blocked on {[...new Set(s.otherApps.map((a) => PLATFORM_LABEL[a.platform] ?? a.platform))].join(' and ')}: {s.otherApps.map(refLabel).join(', ')}
            </div>
          )}
        </div>

        <div className="stack sm">
          <div className="title-sm">Websites to block</div>
          <div className="small muted">Blocked in every browser on this PC (via the Windows hosts file). Subdomains like www. and m. are included.</div>
          {s.domains.length > 0 && (
            <div className="chips">
              {s.domains.map((d) => (
                <Chip key={d} selected onClick={() => update({ domains: s.domains.filter((x) => x !== d) })}><Icon name="globe" size="sm" />{d}<Icon name="close" size="sm" /></Chip>
              ))}
            </div>
          )}
          <div className="row">
            <div className="grow"><TextInput value={domainDraft} onChange={setDomainDraft} placeholder="Add domain (e.g. twitter.com)" onEnter={addDomain} /></div>
            <button className="btn tonal sm" onClick={addDomain} disabled={!domainDraft.trim()}>Add</button>
          </div>
        </div>

        <div className="stack sm">
          <div className="title-sm">Condition tasks</div>
          <div className="small muted">Tasks that must be completed to unblock. Blocking activates when tasks are past their due time.</div>
          <div className="chips">
            <Chip selected={s.conditionLogic === 'all'} onClick={() => update({ conditionLogic: 'all' })}>All tasks</Chip>
            <Chip selected={s.conditionLogic === 'any'} onClick={() => update({ conditionLogic: 'any' })}>Any task</Chip>
          </div>
          <ConditionTaskList tasks={tasks} selected={s.conditionTaskIds} onToggle={(id) => update({ conditionTaskIds: s.conditionTaskIds.includes(id) ? s.conditionTaskIds.filter((x) => x !== id) : [...s.conditionTaskIds, id] })} />
        </div>

        <ErrorText error={error} />
        <div className="row between">
          <button className="btn outlined" onClick={back}>Cancel</button>
          <button className="btn" disabled={saving} onClick={() => void save()}>{saving ? 'Saving…' : 'Save'}</button>
        </div>
      </div>
    </>
  )
}

function ConditionTaskList({ tasks, selected, onToggle }: { tasks: Task[]; selected: string[]; onToggle: (id: string) => void }) {
  const missing = selected.filter((id) => !tasks.some((t) => t.id === id))
  return (
    <div className="card outlined" style={{ padding: 4 }}>
      {tasks.length === 0 && <div className="empty small">No tasks yet</div>}
      {tasks.map((t) => (
        <label key={t.id} className="list-item clickable" style={{ minHeight: 48, padding: '6px 10px' }}>
          <input type="checkbox" checked={selected.includes(t.id)} onChange={() => onToggle(t.id)} />
          <div className="grow">
            <div>{t.title}</div>
            <div className="small muted">{t.taskType} · {t.verificationType ?? 'manual'}{t.dueAt ? ` · due ${t.dueAt}` : ''}</div>
          </div>
        </label>
      ))}
      {missing.map((id) => (
        <label key={id} className="list-item clickable" style={{ minHeight: 48, padding: '6px 10px' }}>
          <input type="checkbox" checked onChange={() => onToggle(id)} />
          <div className="grow">
            <div className="err-text">Unknown task ({id.slice(0, 8)})</div>
            <div className="small muted">Missing tasks count as incomplete, so this keeps the rule blocking.</div>
          </div>
        </label>
      ))}
    </div>
  )
}
