# Brainfence for Windows

Electron + React client with feature parity with the Android app. It syncs the same Supabase/PowerSync data, so tasks, rules, completions, reports and contemplations are shared with the phone.

## Running

```bash
cd desktop-app
npm install
npm run dev        # hot-reloading dev build
npm test           # unit + SQL tests for the shared logic
npm run typecheck
npm run dist       # NSIS installer in dist/
```

Supabase URL, publishable key and PowerSync URL are read at build time from the repo-root `.env` (the same file the Android build uses). The secret key is never bundled. You don't need native build tools: SQLite comes from Node's built-in `node:sqlite`, and Win32 calls go through `koffi`'s prebuilt binary.

## How it works

| Concern | Android | Windows |
| --- | --- | --- |
| Local DB / sync | PowerSync Kotlin SDK | `@powersync/node` (node:sqlite) in the main process |
| Auth session | EncryptedSharedPreferences | Electron `safeStorage` (DPAPI) |
| Background service | Foreground service + boot receiver | Main process in the tray; starts with Windows (`--hidden`) |
| App blocking | AccessibilityService + overlay activity | Foreground-window polling (400 ms) → minimize + full-screen overlay |
| Website blocking | — | Managed section of the Windows hosts file |
| GPS tasks / home presence | Fused location + geofences | Windows location service polled every 2–10 min |
| Companion meditation apps | Accessibility foreground events | Foreground-window tracking (exe names) |
| Notifications | NotificationManager | Windows toasts (contemplation "affirm" via `brainfence://`) |

Code layout:

- `src/shared/`: platform-independent ports of the Android domain and data layers (recurrence engine, time gates, blocking evaluator, contemplation scheduling, reports, task-editor save logic, repositories). Repositories use the same SQL as Android and run in both processes through a small `Db` interface.
- `src/main/`: PowerSync + Supabase connector, auth, background service, enforcement (`blocking/`), timers, notifications, contemplations, location/GPS, tray and IPC.
- `src/renderer/`: the UI. It has every Android screen (tasks with Active/Done/Upcoming, per-type task screens, routine/workout runner, 3-step task editor, blocking rules with time-lock, contemplations, reports and metrics, journal, home location, debug logs), plus a Windows Settings screen and the blocking overlay.
- `test/`: ports of the Android unit tests, plus SQL tests against in-memory SQLite.

## Cross-platform data

Rules and meditation tasks hold app entries for every platform, e.g. `{"platform":"windows","exe":"discord.exe","name":"Discord"}` next to `{"platform":"android","package":"com.discord"}`. Each client reads only its own entries and keeps the others when it saves. (The Android editors were updated to do the same.) The time-lock treats removing *any* platform's app as a loosening.

## Windows setup notes

- **Website blocking** needs a one-time UAC prompt. It grants your account modify rights on `C:\Windows\System32\drivers\etc\hosts`, and after that Brainfence rewrites only its own marked section. Each blocked domain also covers its `www.`, `m.` and `mobile.` subdomains. Browsers with their own DNS-over-HTTPS may need it switched to "use system DNS". Entries stay in place after quitting until Brainfence next runs.
- **Location** requires *Settings → Privacy & security → Location → Let desktop apps access your location*. Without it, GPS tasks can still be verified by the phone, and home-only tasks keep blocking (strict by default).
- **Quitting** stops enforcement. It takes a confirmation from the tray or the Settings screen; closing the window only hides it.

## Differences from Android

- Deleting an *active* rule is a time-locked deactivation. Android deletes it instantly, which bypasses the lock.
- When the task type is unchanged, the task editor keeps `verification_config` keys it doesn't manage (e.g. `execution_mode`, `bell_interval_seconds`).
- GPS "leave" completion requires a fix that is clearly outside the area, taking accuracy into account, because desktop fixes are often Wi-Fi/IP based. GPS tasks don't complete before `available_from`.
- There's no map picker. Use "Use current location" or "Look up on a map".
