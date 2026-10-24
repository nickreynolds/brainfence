import { ReactNode, useEffect, useRef, useState } from 'react'
import { useRouter } from '../lib/router'
import { Icon, IconName } from './Icon'

export function TopBar({ title, actions, back = true }: { title: ReactNode; actions?: ReactNode; back?: boolean }) {
  const router = useRouter()
  return (
    <header className="topbar">
      {back && router.canGoBack && (
        <button className="icon-btn" onClick={router.back} title="Back" aria-label="Back">
          <Icon name="back" />
        </button>
      )}
      <h1>{title}</h1>
      {actions && <div className="actions">{actions}</div>}
    </header>
  )
}

export function IconButton({ icon, title, onClick, disabled, small }: { icon: IconName; title: string; onClick: () => void; disabled?: boolean; small?: boolean }) {
  return (
    <button className={`icon-btn ${small ? 'sm' : ''}`} onClick={onClick} disabled={disabled} title={title} aria-label={title}>
      <Icon name={icon} size={small ? 'sm' : undefined} />
    </button>
  )
}

export function Chip({ selected, onClick, children, disabled, icon }: { selected?: boolean; onClick: () => void; children: ReactNode; disabled?: boolean; icon?: IconName }) {
  return (
    <button className={`chip ${selected ? 'selected' : ''}`} onClick={onClick} disabled={disabled} aria-pressed={selected}>
      {icon && <Icon name={icon} size="sm" />}
      {children}
    </button>
  )
}

export function Switch({ checked, onChange, disabled, label }: { checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; label?: string }) {
  return (
    <button
      className={`switch ${checked ? 'on' : ''}`}
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    >
      <span className="track" />
      <span className="thumb" />
    </button>
  )
}

export function Field({ label, help, children }: { label?: string; help?: ReactNode; children: ReactNode }) {
  return (
    <div className="field">
      {label && <label>{label}</label>}
      {children}
      {help && <div className="help">{help}</div>}
    </div>
  )
}

export function TextInput({
  value, onChange, placeholder, type = 'text', autoFocus, onEnter, invalid, multiline, rows = 3, disabled,
}: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
  autoFocus?: boolean
  onEnter?: () => void
  invalid?: boolean
  multiline?: boolean
  rows?: number
  disabled?: boolean
}) {
  if (multiline) {
    return (
      <textarea className={`input ${invalid ? 'invalid' : ''}`} value={value} rows={rows} placeholder={placeholder}
        autoFocus={autoFocus} disabled={disabled} onChange={(e) => onChange(e.target.value)} />
    )
  }
  return (
    <input
      className={`input ${invalid ? 'invalid' : ''}`}
      type={type}
      value={value}
      placeholder={placeholder}
      autoFocus={autoFocus}
      disabled={disabled}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => e.key === 'Enter' && onEnter?.()}
    />
  )
}

/** A number field that tolerates partial input while typing. */
export function NumberInput({ value, onChange, min = 0, max, step = 1 }: { value: number; onChange: (v: number) => void; min?: number; max?: number; step?: number }) {
  const [text, setText] = useState(String(value))
  useEffect(() => {
    if (Number(text) !== value) setText(String(value))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value])
  return (
    <input
      className="input"
      type="number"
      value={text}
      min={min}
      max={max}
      step={step}
      onChange={(e) => {
        setText(e.target.value)
        const n = Number(e.target.value)
        if (e.target.value !== '' && Number.isFinite(n)) onChange(Math.max(min, max != null ? Math.min(max, n) : n))
      }}
      onBlur={() => setText(String(value))}
    />
  )
}

export function Dialog({ title, children, actions, onClose }: { title: ReactNode; children?: ReactNode; actions: ReactNode; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="dialog-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true">
        <h2>{title}</h2>
        {children}
        <div className="dialog-actions">{actions}</div>
      </div>
    </div>
  )
}

export function ConfirmDialog({ title, message, confirmLabel, cancelLabel = 'Cancel', danger, onConfirm, onCancel }: {
  title: string; message: ReactNode; confirmLabel: string; cancelLabel?: string; danger?: boolean; onConfirm: () => void; onCancel: () => void
}) {
  return (
    <Dialog
      title={title}
      onClose={onCancel}
      actions={
        <>
          <button className="btn text" onClick={onCancel}>{cancelLabel}</button>
          <button className={`btn text ${danger ? 'danger' : ''}`} onClick={onConfirm}>{confirmLabel}</button>
        </>
      }
    >
      <div className="muted">{message}</div>
    </Dialog>
  )
}

export function Banner({ icon, tone = 'error', children, onClick, action }: { icon: IconName; tone?: 'error' | 'secondary' | 'primary' | 'tertiary'; children: ReactNode; onClick?: () => void; action?: ReactNode }) {
  return (
    <div className={`card ${tone} ${onClick ? 'clickable' : ''}`} onClick={onClick}>
      <div className="row" style={{ gap: 12 }}>
        <Icon name={icon} />
        <div className="grow">{children}</div>
        {action}
      </div>
    </div>
  )
}

export function Loading() {
  return (
    <div className="center-screen">
      <div className="spinner" />
    </div>
  )
}

export function ProgressBar({ value }: { value: number }) {
  return (
    <div className="progress">
      <div style={{ width: `${Math.max(0, Math.min(1, value)) * 100}%` }} />
    </div>
  )
}

/** Circular progress ring (meditation / timers). */
export function Ring({ progress, size = 220, color = 'var(--primary)' }: { progress: number; size?: number; color?: string }) {
  const stroke = 10
  const r = (size - stroke) / 2
  const c = 2 * Math.PI * r
  const p = Math.max(0, Math.min(1, progress))
  return (
    <svg className="ring" width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--surface-container-high)" strokeWidth={stroke} />
      <circle
        cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
        strokeDasharray={c} strokeDashoffset={c * (1 - p)} transform={`rotate(-90 ${size / 2} ${size / 2})`}
        style={{ transition: 'stroke-dashoffset 0.4s linear' }}
      />
    </svg>
  )
}

export function Menu({ items }: { items: { label: string; icon: IconName; onClick: () => void }[] }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false)
    window.addEventListener('mousedown', onDown)
    return () => window.removeEventListener('mousedown', onDown)
  }, [open])
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <IconButton icon="more" title="More options" onClick={() => setOpen((o) => !o)} />
      {open && (
        <div className="menu">
          {items.map((it) => (
            <button key={it.label} onClick={() => { setOpen(false); it.onClick() }}>
              <Icon name={it.icon} />
              {it.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

export function Toast({ message, action, onAction, onDone, ms = 5000 }: { message: string; action?: string; onAction?: () => void; onDone: () => void; ms?: number }) {
  useEffect(() => {
    const t = setTimeout(onDone, ms)
    return () => clearTimeout(t)
  }, [onDone, ms])
  return (
    <div className="toast">
      <span>{message}</span>
      {action && <button className="btn text sm" onClick={() => { onAction?.(); onDone() }}>{action}</button>}
    </div>
  )
}

export function ErrorText({ error }: { error: string | null | undefined }) {
  return error ? <div className="err-text small">{error}</div> : null
}

/** Icon for a Windows app from its exe path (via the main process), or a placeholder. */
export function AppIcon({ path }: { path: string | null | undefined }) {
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    if (path) void window.api.apps.icon(path).then((s) => alive && setSrc(s))
    return () => {
      alive = false
    }
  }, [path])
  return src ? <img className="app-icon" src={src} alt="" /> : <span className="app-icon placeholder" />
}

export function DayChips({ selected, onToggle }: { selected: string[]; onToggle: (day: string) => void }) {
  const days = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']
  const labels = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
  return (
    <div className="chips">
      {days.map((d, i) => (
        <Chip key={d} selected={selected.includes(d)} onClick={() => onToggle(d)}>{labels[i]}</Chip>
      ))}
    </div>
  )
}
