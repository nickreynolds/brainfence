// Device location through the Windows location service (WinRT Geolocator,
// reached from PowerShell). Powers home presence for home-only blocking and
// GPS task verification. Requires Settings → Privacy & security → Location →
// "Let desktop apps access your location".

import { shell } from 'electron'
import { distanceMeters, type HomePresence } from '@shared/blocking'
import type { HomeLocation, LocationFix, LocationStatus } from '@shared/ipc'
import { log } from './debugLog'
import { runPowerShell } from './powershell'
import { getValue, setValue } from './store'

const GEOLOCATE_SCRIPT = `
$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $null = [Windows.Devices.Geolocation.Geolocator,Windows.Devices.Geolocation,ContentType=WindowsRuntime]
  $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation\`1' })[0]
  $access = $asTask.MakeGenericMethod([Windows.Devices.Geolocation.GeolocationAccessStatus]).Invoke($null, @([Windows.Devices.Geolocation.Geolocator]::RequestAccessAsync()))
  $null = $access.Wait(10000)
  if ($access.Result -ne 'Allowed') { @{ status = [string]$access.Result } | ConvertTo-Json -Compress; exit }
  $g = New-Object Windows.Devices.Geolocation.Geolocator
  $g.DesiredAccuracyInMeters = 50
  $t = $asTask.MakeGenericMethod([Windows.Devices.Geolocation.Geoposition]).Invoke($null, @($g.GetGeopositionAsync([TimeSpan]::FromMinutes(1), [TimeSpan]::FromSeconds(20))))
  $null = $t.Wait(25000)
  $c = $t.Result.Coordinate
  @{ status = 'Allowed'; lat = $c.Point.Position.Latitude; lng = $c.Point.Position.Longitude; acc = $c.Accuracy } | ConvertTo-Json -Compress
} catch {
  @{ status = 'Error'; error = $_.Exception.Message } | ConvertTo-Json -Compress
}
`

let status: LocationStatus = { access: 'unknown', lastFix: getValue<LocationFix | null>('lastLocationFix', null), error: null }
let inFlight: Promise<LocationStatus> | null = null
const listeners = new Set<(s: LocationStatus) => void>()

export const getLocationStatus = (): LocationStatus => status

export function onLocationStatus(listener: (s: LocationStatus) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** Ask Windows for a fresh fix (deduplicated while one is in flight). */
export function refreshLocation(): Promise<LocationStatus> {
  inFlight ??= (async () => {
    try {
      const out = JSON.parse((await runPowerShell(GEOLOCATE_SCRIPT, 60_000)).trim() || '{}')
      if (out.status === 'Allowed' && typeof out.lat === 'number') {
        const fix: LocationFix = { latitude: out.lat, longitude: out.lng, accuracyM: typeof out.acc === 'number' ? out.acc : null, at: new Date().toISOString() }
        setValue('lastLocationFix', fix)
        status = { access: 'allowed', lastFix: fix, error: null }
        log('location', 'Location fix', JSON.stringify({ lat: fix.latitude, lng: fix.longitude, acc: fix.accuracyM }))
      } else if (out.status === 'Denied') {
        status = { ...status, access: 'denied', error: 'Location access is off for desktop apps' }
      } else {
        status = { ...status, access: out.status === 'Error' ? status.access : 'unavailable', error: out.error ?? `Location unavailable (${out.status})` }
      }
    } catch (e: any) {
      status = { ...status, access: 'unavailable', error: e.message }
    }
    listeners.forEach((l) => l(status))
    return status
  })().finally(() => {
    inFlight = null
  })
  return inFlight
}

export function openLocationSettings(): Promise<void> {
  return shell.openExternal('ms-settings:privacy-location')
}

// ── Home ─────────────────────────────────────────────────────────────

const homeListeners = new Set<() => void>()

export function getHome(): HomeLocation | null {
  return getValue<HomeLocation | null>('homeLocation', null)
}

export function setHome(home: HomeLocation | null): void {
  setValue('homeLocation', home)
  homeListeners.forEach((l) => l())
}

export function onHomeChange(listener: () => void): () => void {
  homeListeners.add(listener)
  return () => homeListeners.delete(listener)
}

/** Fixes older than this no longer say where the user is. */
const MAX_FIX_AGE_MS = 6 * 3_600_000

/**
 * Strict by default: anything short of a fresh fix positively outside the
 * home radius keeps home-only tasks blocking.
 */
export function homePresence(): HomePresence {
  const home = getHome()
  if (!home) return 'UNCONFIGURED'
  const fix = status.lastFix
  if (!fix || Date.now() - new Date(fix.at).getTime() > MAX_FIX_AGE_MS) return 'UNKNOWN'
  const d = distanceMeters(fix.latitude, fix.longitude, home.latitude, home.longitude)
  return d <= home.radiusMeters ? 'AT_HOME' : 'AWAY'
}
