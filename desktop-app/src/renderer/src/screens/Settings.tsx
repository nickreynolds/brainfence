import { useEffect, useState } from 'react'
import type { AppSettings } from '@shared/ipc'
import { Icon } from '../components/Icon'
import { ConfirmDialog, Switch, TopBar } from '../components/ui'
import { cleanError, useAuth, useEnforcement, useLocationStatus, useSyncStatus } from '../lib/hooks'
import { formatDateTime } from '../lib/format'
import { useRouter } from '../lib/router'

/**
 * Windows setup and account — the counterpart of Android's accessibility
 * setup screen plus the sign-out menu item.
 */
export function SettingsScreen() {
  const auth = useAuth()
  const sync = useSyncStatus()
  const enforcement = useEnforcement()
  const location = useLocationStatus()
  const [app, setApp] = useState<AppSettings | null>(null)
  const [settingUp, setSettingUp] = useState(false)
  const [confirmSignOut, setConfirmSignOut] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const { navigate } = useRouter()

  useEffect(() => {
    void window.api.settings.getApp().then(setApp)
  }, [])

  const website = enforcement?.website
  const setupWebsite = async () => {
    setSettingUp(true)
    await window.api.blocking.setupWebsiteBlocking()
    setSettingUp(false)
  }

  return (
    <>
      <TopBar title="Settings" back={false} />
      <div className="stack lg">
        <div className="stack sm">
          <div className="title-md">Blocking on this PC</div>
          <div className="card stack sm">
            <div className="row"><Icon name="shield" style={{ color: 'var(--primary)' }} /><span className="title-sm grow">App blocking</span><span className="small primary-text">Active</span></div>
            <div className="small muted">When a blocked app comes to the front, Brainfence minimizes it and shows what you need to do first. Closing the window keeps Brainfence running in the tray.</div>
          </div>
          <div className="card stack sm">
            <div className="row">
              <Icon name="globe" style={{ color: website?.writable ? 'var(--primary)' : 'var(--error)' }} />
              <span className="title-sm grow">Website blocking</span>
              <span className={`small ${website?.writable ? 'primary-text' : 'err-text'}`}>{website?.writable ? 'Ready' : 'Needs setup'}</span>
            </div>
            <div className="small muted">Blocked domains are redirected in the Windows hosts file, which covers every browser. Allowing this once asks Windows for administrator permission to let your account edit that file.</div>
            {website?.writable && website.activeDomains.length > 0 && <div className="small">Blocking now: {website.activeDomains.join(', ')}</div>}
            {website?.error && <div className="small err-text">{website.error}</div>}
            {!website?.writable && <div><button className="btn tonal sm" disabled={settingUp} onClick={() => void setupWebsite()}>{settingUp ? 'Waiting for Windows…' : 'Allow website blocking'}</button></div>}
          </div>
          <div className="card row">
            <div className="grow">
              <div className="title-sm">Start with Windows</div>
              <div className="small muted">Run in the background from sign-in so blocking is always on.</div>
            </div>
            {app && <Switch checked={app.launchAtLogin} onChange={(v) => { const next = { ...app, launchAtLogin: v }; setApp(next); void window.api.settings.setApp(next) }} label="Start with Windows" />}
          </div>
          <div className="card stack sm">
            <div className="row">
              <Icon name="place" style={{ color: location?.access === 'allowed' ? 'var(--primary)' : 'var(--outline)' }} />
              <span className="title-sm grow">Location</span>
              <span className="small muted">{location?.access === 'allowed' ? 'Allowed' : location?.access === 'denied' ? 'Off for desktop apps' : location?.access === 'unavailable' ? 'Unavailable' : 'Not checked yet'}</span>
            </div>
            <div className="small muted">Used for GPS tasks, shopping reminders and home-only blocking. Optional — your phone can verify GPS tasks instead.</div>
            {location?.lastFix && <div className="small muted">Last fix {formatDateTime(location.lastFix.at)}</div>}
            <div className="row">
              <button className="btn tonal sm" onClick={() => void window.api.location.refresh()}>Check now</button>
              <button className="btn text sm" onClick={() => void window.api.location.openSettings()}>Windows location settings</button>
            </div>
          </div>
        </div>

        <div className="stack sm">
          <div className="title-md">Setup guide</div>
          <div className="card row">
            <div className="grow">
              <div className="title-sm">Run setup guide</div>
              <div className="small muted">Walks through how Brainfence works and adds the starter tasks and blocking rule again. Your existing tasks are kept.</div>
            </div>
            <button className="btn tonal sm" onClick={() => navigate('guide')}>Start</button>
          </div>
        </div>

        <div className="stack sm">
          <div className="title-md">Account & sync</div>
          <div className="card stack sm">
            {auth.status === 'signedIn' && <div>Signed in as <b>{auth.email ?? auth.userId}</b></div>}
            <div className="small muted">
              {sync?.connected ? 'Connected to sync' : sync?.connecting ? 'Connecting…' : 'Offline — changes are saved locally and sync when back online'}
              {sync?.lastSyncedAt && ` · last synced ${formatDateTime(sync.lastSyncedAt)}`}
            </div>
            {sync?.error && <div className="small err-text">{sync.error}</div>}
            <div className="row">
              <button className="btn outlined sm" onClick={() => setConfirmSignOut(true)}><Icon name="logout" size="sm" /> Sign out</button>
              <button className="btn text sm danger" onClick={() => { setDeleteError(null); setConfirmDelete(true) }}><Icon name="delete" size="sm" /> Delete account…</button>
              <button className="btn text sm" onClick={() => void window.api.window.quit()}>Quit Brainfence…</button>
            </div>
          </div>
        </div>
      </div>
      {confirmSignOut && (
        <ConfirmDialog
          title="Sign out?"
          message="This PC's copy of your tasks is removed and blocking stops until you sign in again. Anything not yet synced is lost."
          confirmLabel="Sign out"
          danger
          onCancel={() => setConfirmSignOut(false)}
          onConfirm={() => { setConfirmSignOut(false); void window.api.auth.signOut() }}
        />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title="Delete your account?"
          message={
            <>
              This permanently deletes your account and everything in it, including your tasks, blocking rules and history, from Brainfence's servers and this PC. Blocking stops. You can't undo this.
              {deleteError && <div className="err-text" style={{ marginTop: 12 }}>Couldn't delete your account: {deleteError}</div>}
            </>
          }
          confirmLabel={deleting ? 'Deleting…' : 'Delete account'}
          danger
          onCancel={() => { if (!deleting) setConfirmDelete(false) }}
          onConfirm={() => {
            if (deleting) return
            setDeleting(true)
            setDeleteError(null)
            // On success the app signs out, which replaces this screen.
            window.api.auth.deleteAccount()
              .catch((e) => setDeleteError(cleanError(e)))
              .finally(() => setDeleting(false))
          }}
        />
      )}
    </>
  )
}
