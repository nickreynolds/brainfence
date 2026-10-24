import { useEffect, useState } from 'react'
import { distanceMeters } from '@shared/blocking'
import { Icon } from '../components/Icon'
import { Banner, ErrorText, Field, NumberInput, TextInput, TopBar } from '../components/ui'
import { formatDateTime } from '../lib/format'
import { useEnforcement, useLocationStatus } from '../lib/hooks'

/** Port of HomeLocationScreen. Home is per device, like on Android. */
export function HomeLocationScreen() {
  const location = useLocationStatus()
  const enforcement = useEnforcement()
  const [lat, setLat] = useState('')
  const [lng, setLng] = useState('')
  const [radius, setRadius] = useState(100)
  const [saved, setSaved] = useState(false)
  const [hasHome, setHasHome] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [locating, setLocating] = useState(false)

  useEffect(() => {
    void window.api.settings.getHome().then((h) => {
      if (h) {
        setLat(String(h.latitude))
        setLng(String(h.longitude))
        setRadius(h.radiusMeters)
        setHasHome(true)
      }
    })
  }, [])

  const useCurrent = async () => {
    setLocating(true)
    setError(null)
    const s = await window.api.location.refresh()
    setLocating(false)
    if (s.lastFix && s.access === 'allowed') {
      setLat(s.lastFix.latitude.toFixed(6))
      setLng(s.lastFix.longitude.toFixed(6))
    } else setError(s.error ?? 'Location unavailable')
  }
  const save = async () => {
    const la = Number(lat)
    const lo = Number(lng)
    if (!lat || !lng || !Number.isFinite(la) || !Number.isFinite(lo) || Math.abs(la) > 90 || Math.abs(lo) > 180) return setError('Enter a valid latitude and longitude')
    await window.api.settings.setHome({ latitude: la, longitude: lo, radiusMeters: Math.max(10, radius) })
    setHasHome(true)
    setSaved(true)
    setError(null)
  }
  const clear = async () => {
    await window.api.settings.setHome(null)
    setLat('')
    setLng('')
    setRadius(100)
    setHasHome(false)
    setSaved(false)
  }

  const fix = location?.lastFix
  const presence = enforcement?.homePresence
  const dist = fix && hasHome && Number.isFinite(Number(lat)) ? distanceMeters(fix.latitude, fix.longitude, Number(lat), Number(lng)) : null

  return (
    <>
      <TopBar title="Home location" back={false} />
      <div className="stack lg">
        <div className="small muted">Tasks marked "only block when at home" skip blocking when this PC is outside this radius (e.g. a laptop on vacation). If the location is unknown, they keep blocking.</div>
        {location?.access === 'denied' && (
          <Banner icon="place" onClick={() => void window.api.location.openSettings()}>
            Location access is off for desktop apps. Click to open Windows settings and turn on "Let desktop apps access your location".
          </Banner>
        )}
        {hasHome && presence && (
          <div className={`card ${presence === 'AWAY' ? 'tertiary' : 'primary'}`}>
            <div className="row"><Icon name={presence === 'AWAY' ? 'place' : 'home'} />
              <span>{presence === 'AT_HOME' ? 'At home' : presence === 'AWAY' ? 'Away from home — home-only tasks are not blocking' : 'Location unknown — home-only tasks keep blocking'}</span>
            </div>
            {fix && <div className="small" style={{ marginTop: 4 }}>Last fix {formatDateTime(fix.at)}{dist != null ? ` · ${Math.round(dist)} m from home` : ''}{fix.accuracyM != null ? ` (±${Math.round(fix.accuracyM)} m)` : ''}</div>}
          </div>
        )}
        <div className="grid2">
          <Field label="Latitude"><TextInput value={lat} onChange={(v) => { setLat(v); setSaved(false) }} /></Field>
          <Field label="Longitude"><TextInput value={lng} onChange={(v) => { setLng(v); setSaved(false) }} /></Field>
        </div>
        <div><button className="btn tonal" onClick={() => void useCurrent()} disabled={locating}><Icon name="place" /> {locating ? 'Locating…' : 'Use current location'}</button></div>
        <Field label="Radius (meters)"><NumberInput value={radius} min={10} onChange={(r) => { setRadius(r); setSaved(false) }} /></Field>
        <ErrorText error={error} />
        {saved && <div className="small primary-text">Home location saved</div>}
        <div className="row">
          <button className="btn" onClick={() => void save()}>Save</button>
          {hasHome && <button className="btn outlined" onClick={() => void clear()}>Clear</button>}
        </div>
      </div>
    </>
  )
}
