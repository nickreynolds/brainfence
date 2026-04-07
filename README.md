# Brainfence — Architecture Specification

## 1. Vision

A local-first, cross-platform task/habit/note app that enforces behavioral commitments by blocking designated apps and websites until tasks are completed. Think **Todoist + Habitify + Cold Turkey**, with objective verification (GPS, timers, duration gates).

---

## 2. Platform & Tech Stack

### 2.1 Client Applications

| Platform    | Language / UI             | Local DB                                    | App Blocking Mechanism                                               |
| ----------- | ------------------------- | ------------------------------------------- | -------------------------------------------------------------------- |
| **Android** | Kotlin + Jetpack Compose  | SQLite (via PowerSync Kotlin SDK)           | AccessibilityService + overlay Activity                              |
| **macOS**   | Swift + SwiftUI           | SQLite (via PowerSync Swift SDK)            | Screen Time APIs (FamilyControls / ManagedSettings / DeviceActivity) |
| **Web**     | React + Next.js (or Vite) | PGlite or IndexedDB (via PowerSync Web SDK) | Browser extension (optional, lower priority)                         |

Each client is fully native to its platform. No cross-platform UI framework — the platform-specific blocking mechanisms, background services, and OS integration all demand native access. The tradeoff is more UI code to maintain, but the app's core value proposition depends on deep OS integration that frameworks like React Native or Flutter can't provide without extensive bridging.

### 2.2 Shared Code Strategy

Rather than sharing code via Kotlin Multiplatform at the UI level, share at the **schema and protocol level**:

- **PowerSync sync rules** — defined once on the server, enforced everywhere
- **Supabase Postgres schema** — single source of truth for data model
- **Recurrence engine** — can be ported as a pure algorithm (Kotlin, Swift, and TypeScript implementations of the same RRULE spec)
- **Blocking condition evaluator** — pure function: given (tasks, completions, rules, current_time, current_location) → should_block?

This keeps each client idiomatic to its platform while ensuring data consistency.

### 2.3 Backend

| Component          | Technology                         | Purpose                                  |
| ------------------ | ---------------------------------- | ---------------------------------------- |
| **Database**       | Supabase (Postgres)                | Primary data store, auth, edge functions |
| **Sync**           | PowerSync (self-hosted)            | Bidirectional local-first sync           |
| **Auth**           | Supabase Auth                      | Email/password, OAuth, magic links       |
| **Storage**        | Supabase Storage                   | Attachments, images for notes            |
| **Edge Functions** | Supabase Edge Functions            | Server-side validation                   |

Supabase is retained from v1 because PowerSync has first-class Supabase integration (their primary demo app is a Supabase todo list), and it provides auth, storage, and edge functions out of the box.

PowerSync is run self-hosted (open-source edition) via Docker. This avoids vendor lock-in on the sync layer, keeps all data within our own infrastructure, and eliminates per-connection pricing as usage scales.

---

## 3. Local-First Sync Architecture

### 3.1 Why PowerSync

After evaluating RxDB, ElectricSQL, Zero, Triplit, and others, PowerSync is the best fit because:

1. **Native SDK coverage** — Kotlin (Android/JVM/Apple), Swift (macOS/iOS), JavaScript (Web). No other sync engine covers all three with production-quality native SDKs.
2. **Bidirectional sync** — Writes queue locally in SQLite and sync to Postgres when online. ElectricSQL only handles the read path.
3. **SQLite on-device** — Real SQL queries locally, not an in-memory store. Supports SQLDelight (Kotlin) and raw SQL.
4. **Supabase integration** — Purpose-built connector for Supabase Postgres.
5. **Sync Rules** — Server-side rules control what data syncs to which client (scoped by user_id).
6. **Self-hostable** — Open-source edition chosen; deployed via Docker for full control and no per-connection pricing.
7. **Active development** — Latest Kotlin SDK release was ~1 week ago; Swift SDK actively being rewritten in native Swift.

### 3.2 Sync Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────────────┐
│ Android App  │     │  macOS App   │     │      Web App         │
│ (Kotlin)     │     │  (Swift)     │     │  (TypeScript)        │
│              │     │              │     │                      │
│ ┌──────────┐ │     │ ┌──────────┐ │     │ ┌──────────────────┐ │
│ │  SQLite  │ │     │ │  SQLite  │ │     │ │ PGlite/IndexedDB │ │
│ └────┬─────┘ │     │ └────┬─────┘ │     │ └───────┬──────────┘ │
└──────┼───────┘     └──────┼───────┘     └─────────┼────────────┘
       │                    │                       │
       └────────────┬───────┴───────────────────────┘
                    │
            ┌───────▼────────┐
            │  PowerSync     │
            │  Sync Service  │
            └───────┬────────┘
                    │  (Logical Replication)
            ┌───────▼────────┐
            │   Supabase     │
            │   Postgres     │
            └────────────────┘
```

### 3.3 Conflict Resolution

PowerSync uses a **last-write-wins** strategy by default, with the server as the authority. For this app, that's generally fine because:

- Tasks and notes are primarily single-user (you're editing your own data)
- The server timestamp determines the winner if two devices edit the same record offline
- Completion records are append-only (no conflicts possible)

For future social/collaborative features, more sophisticated conflict resolution may be needed, but LWW is the right starting point.

---

## 4. Data Model

### 4.1 Core Entities

#### users

Managed by Supabase Auth. PowerSync syncs data scoped to the authenticated user.

#### tasks

```sql
CREATE TABLE tasks (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES auth.users(id),
  title           TEXT NOT NULL,
  description     TEXT,
  task_type       TEXT NOT NULL DEFAULT 'simple',
    -- 'simple' | 'routine' | 'workout' | 'meditation' | 'timed'
  status          TEXT NOT NULL DEFAULT 'active',
    -- 'active' | 'paused' | 'archived'

  -- Recurrence (null = one-off task)
  recurrence_type TEXT,
    -- null (one-off) | 'daily' | 'weekly' | 'monthly' | 'interval'
  recurrence_config JSONB DEFAULT '{}',
    -- daily:    {} (every day) or {"days": ["mon","wed","fri"]}
    -- weekly:   {"day": "thu", "interval": 2} (every other Thursday)
    -- monthly:  {"week": 2, "day": "wed"} (2nd Wednesday)
    -- interval: {"minutes": 480} (every 8 hours)
    --           {"minutes": 60, "active_days": ["mon"-"fri"], "active_start": "09:00", "active_end": "17:00"}
  -- NOTE: designed so RRULE support can be added later as a new recurrence_type
  -- without schema changes

  -- Verification
  verification_type  TEXT DEFAULT 'manual',
    -- 'manual' | 'gps' | 'duration' | 'meditation' | 'time_gate'
  verification_config JSONB DEFAULT '{}',
    -- GPS:        {"lat": 40.7128, "lng": -74.0060, "radius_m": 100,
    --              "mode": "enter",  -- "enter" | "leave"
    --              "min_duration_m": 30}
    --             "enter": task completes when user arrives and stays N minutes
    --             "leave": task completes when user departs a location (e.g. must leave home to go to gym)
    -- Duration:   {"duration_seconds": 300}
    -- Meditation: {"duration_seconds": 600, "allow_pause": false}
    -- Time gate:  {"start_time": "05:00", "end_time": "10:00", "timezone": "America/New_York"}

  -- Organization
  tags            TEXT[] DEFAULT '{}',
  group_id        UUID REFERENCES groups(id),
  sort_order      INTEGER DEFAULT 0,

  -- Blocking
  is_blocking_condition BOOLEAN DEFAULT false,  -- does this task gate app access?
  blocking_rule_ids     UUID[] DEFAULT '{}',    -- which blocking rules does this affect?

  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ DEFAULT now()
);
```

#### routine_steps

For tasks of type 'routine' or 'workout', defines the ordered sub-steps.

```sql
CREATE TABLE routine_steps (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  title           TEXT NOT NULL,
  step_order      INTEGER NOT NULL,
  step_type       TEXT NOT NULL DEFAULT 'checkbox',
    -- 'checkbox' | 'weight_reps' | 'just_reps' | 'timed' | 'meditation'
  config          JSONB DEFAULT '{}',
    -- weight_reps: {"default_sets": 3}
    -- timed:       {"duration_seconds": 60}
    -- meditation:  {"duration_seconds": 300}
  created_at      TIMESTAMPTZ DEFAULT now()
);
```

#### task_completions

Append-only log of completions. For recurring tasks, each completion is a separate record. This is the "previous completions" history.

```sql
CREATE TABLE task_completions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id         UUID NOT NULL REFERENCES tasks(id),
  user_id         UUID NOT NULL REFERENCES auth.users(id),
  completed_at    TIMESTAMPTZ DEFAULT now(),

  -- For recurring tasks: which occurrence this completes
  occurrence_date TIMESTAMPTZ,

  -- Verification proof
  verification_data JSONB DEFAULT '{}',
    -- GPS enter: {"lat": 40.713, "lng": -74.006, "accuracy_m": 8.5, "arrived_at": "...", "left_at": "..."}
    -- GPS leave: {"lat": 40.713, "lng": -74.006, "accuracy_m": 8.5, "departed_at": "..."}
    -- Duration:   {"actual_seconds": 312}
    -- Meditation: {"actual_seconds": 600, "pauses": 0}

  created_at      TIMESTAMPTZ DEFAULT now()
);
```

#### step_completions

Per-step data within a routine/workout completion. This is where weight/reps/time data lives, and what gets used to pre-fill the next occurrence.

```sql
CREATE TABLE step_completions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_completion_id  UUID NOT NULL REFERENCES task_completions(id) ON DELETE CASCADE,
  routine_step_id     UUID NOT NULL REFERENCES routine_steps(id),
  set_number          INTEGER DEFAULT 1,

  data                JSONB NOT NULL DEFAULT '{}',
    -- weight_reps: {"weight_lbs": 100, "reps": 10}
    -- just_reps:   {"reps": 15}
    -- timed:       {"duration_seconds": 60}
    -- checkbox:    {"completed": true}

  completed_at        TIMESTAMPTZ DEFAULT now()
);
```

#### blocking_rules

Defines what gets blocked and under what conditions.

```sql
CREATE TABLE blocking_rules (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES auth.users(id),
  name            TEXT NOT NULL,

  -- What to block (platform-specific identifiers stored as JSON)
  blocked_apps    JSONB DEFAULT '[]',
    -- [{"platform": "android", "package": "com.twitter.android"},
    --  {"platform": "macos", "bundle_id": "com.apple.Safari", "token": "..."}]
  blocked_domains TEXT[] DEFAULT '{}',  -- ["twitter.com", "reddit.com"]

  -- Conditions for the block being ACTIVE
  -- Block is active when ANY of these conditions are unmet
  condition_task_ids UUID[] DEFAULT '{}',  -- tasks that must be completed
  condition_logic    TEXT DEFAULT 'all',   -- 'all' | 'any' (all tasks or any task)

  -- Time-based conditions
  active_schedule    JSONB,  -- when this rule is in effect
    -- {"days": ["mon","tue","wed","thu","fri"], "start": "09:00", "end": "17:00"}

  -- Anti-bypass: config changes are time-locked
  config_lock_hours  INTEGER DEFAULT 24,   -- hours before config changes take effect
  pending_changes    JSONB,                -- queued changes waiting for lock expiry
  changes_apply_at   TIMESTAMPTZ,          -- when pending changes become active

  is_active       BOOLEAN DEFAULT true,
  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ DEFAULT now()
);
```

#### notes

Markdown notes with wikilink support.

```sql
CREATE TABLE notes (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES auth.users(id),
  title           TEXT NOT NULL,
  content         TEXT DEFAULT '',        -- markdown

  -- Organization
  tags            TEXT[] DEFAULT '{}',
  group_id        UUID REFERENCES groups(id),

  -- Wikilinks (extracted from content, denormalized for query)
  outgoing_links  UUID[] DEFAULT '{}',    -- note IDs this note links to

  created_at      TIMESTAMPTZ DEFAULT now(),
  updated_at      TIMESTAMPTZ DEFAULT now()
);
```

#### groups

Organizational containers with visibility scheduling.

```sql
CREATE TABLE groups (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES auth.users(id),
  name            TEXT NOT NULL,
  color           TEXT,
  icon            TEXT,

  -- Visibility schedule (null = always visible)
  visibility_schedule JSONB,
    -- {"days": ["mon","tue","wed","thu","fri"], "start": "09:00", "end": "17:00"}
    -- Used for work-hours vs life-hours filtering

  sort_order      INTEGER DEFAULT 0,
  created_at      TIMESTAMPTZ DEFAULT now()
);
```

### 4.2 Recurrence System

Replace the v1 `freqHours` with a predefined-pattern system that covers all stated use cases:

| `recurrence_type` | `recurrence_config` example                                                                     | Meaning                     |
| ----------------- | ----------------------------------------------------------------------------------------------- | --------------------------- |
| `null`            | —                                                                                               | One-off task                |
| `daily`           | `{}`                                                                                            | Every day                   |
| `daily`           | `{"days": ["mon","wed","fri"]}`                                                                 | Specific days of the week   |
| `weekly`          | `{"day": "thu", "interval": 2}`                                                                 | Every other Thursday        |
| `monthly`         | `{"week": 2, "day": "wed"}`                                                                     | 2nd Wednesday of each month |
| `interval`        | `{"minutes": 480}`                                                                              | Every 8 hours               |
| `interval`        | `{"minutes": 60, "active_days": ["mon"-"fri"], "active_start": "12:00", "active_end": "12:30"}` | Noon during weekdays        |

The `recurrence_type` field is a simple enum. A "next occurrence" calculator takes the type + config + last completion time and returns the next due date. This is a pure function, easily implemented on all three platforms.

**Future RRULE upgrade path**: Add `recurrence_type = 'rrule'` with `recurrence_config = {"rrule": "FREQ=WEEKLY;BYDAY=TU,TH"}`. The existing types continue to work unchanged. No schema migration needed.

### 4.3 Pre-filling from Previous Completions

For recurring routines/workouts, the most recent `task_completion` + its `step_completions` are queried to pre-fill the current occurrence:

```
Query: SELECT sc.* FROM step_completions sc
  JOIN task_completions tc ON sc.task_completion_id = tc.id
  WHERE tc.task_id = :task_id
  ORDER BY tc.completed_at DESC
  LIMIT (number_of_steps × max_sets)
```

This runs against local SQLite, so it's instant even offline.

---

## 5. App Blocking Architecture

### 5.1 Core Blocking Logic (Cross-Platform)

The blocking evaluator is a pure function, implemented identically on each platform:

```
function shouldBlock(rule, taskCompletions, currentTime, currentLocation):
  if not rule.is_active: return false
  if rule.active_schedule and not isWithinSchedule(rule.active_schedule, currentTime):
    return false

  for each task_id in rule.condition_task_ids:
    completion = findCurrentOccurrenceCompletion(task_id, currentTime)
    if completion is null:
      if rule.condition_logic == 'all': return true  // missing one → block
      continue
    if rule.condition_logic == 'any': return false   // found one → unblock

  return rule.condition_logic == 'all' ? false : true
```

### 5.2 Android Implementation

#### AccessibilityService

- Listens for `TYPE_WINDOW_STATE_CHANGED` events to detect foreground app changes
- When a blocked app is detected → launches a full-screen blocking Activity
- The blocking Activity shows: which tasks are incomplete, quick-complete buttons, and a "go to app" button for tasks that need in-app completion

#### Foreground Service

- Persistent notification ("brainfence is active")
- Keeps the process alive
- Runs the blocking evaluator on a timer (every 1 minute for time-based checks)

#### Boot Receiver

- `RECEIVE_BOOT_COMPLETED` permission
- Restarts the foreground service on device boot

#### Installation Requirements (Sideloaded)

Android 13+ restricts accessibility service access for apps installed from "user-acquired APKs" (downloaded via browser/file manager). This is an OS-level restriction, not a Play Store policy. Three tiers of restriction to be aware of:

1. **Android 13+ "Restricted Settings"**: Apps installed from non-session installers can't enable accessibility. **Workaround**: Install via `adb install brainfence.apk` — this bypasses the restriction entirely.
2. **Android 17 Advanced Protection Mode**: Blocks all non-`isAccessibilityTool` apps from accessibility. **Not a concern**: APM is opt-in and the device owner simply doesn't enable it.
3. **Play Store policy**: Requires justification for accessibility use. **Not applicable**: app is sideloaded.

```bash
# Recommended install method
adb install brainfence.apk
# Then: Settings → Accessibility → brainfence → Enable
```

The app's first-launch setup wizard should guide the user through enabling the accessibility service, with clear instructions and a deep-link to the relevant settings screen.

#### Anti-Bypass (High Friction)

- **Time-lock on config changes**: Modifying blocked apps or disabling rules queues the change with a 24-hour delay. The user sees: "This change will take effect in 23h 47m."
- **Multi-step disable**: To disable blocking entirely requires: opening settings → confirming intent → waiting through a 5-minute cooldown → confirming again.
- **No Device Admin**: Since we're going high-friction rather than maximum resistance, we skip device admin to avoid the UX overhead of that setup. The user _can_ uninstall the app, but it requires deliberate, multi-step effort.

### 5.3 macOS Implementation

#### Screen Time APIs

- **FamilyControls**: Request `.individual` authorization at first launch. User grants permission to manage their own screen time.
- **ManagedSettings**: Apply `shield.applications` and `shield.webDomainCategories` using opaque `ApplicationToken`s from the family activity picker.
- **DeviceActivity**: Schedule monitoring intervals and register events.
- **ShieldConfiguration**: Custom shield overlay showing which tasks need completion.

#### Always Running

- Register as a **Login Item** (LaunchAgent plist) so the app starts on login
- Use `NSApplication` lifecycle to resist being quit (confirm dialog with time-lock)

#### Anti-Bypass

- Config changes time-locked (same as Android)
- Screen Time permissions can technically be revoked in System Settings, but the friction of navigating there and understanding what to disable is substantial

### 5.4 Website Blocking

**Android**: Not needed. App-level blocking via the AccessibilityService covers the primary use case on mobile. If website blocking is desired later, a local VPN (`VpnService`) approach can be added without architectural changes.

**macOS**: Screen Time API handles web domain blocking natively via `shield.webDomainCategories`. This comes "for free" alongside app blocking — the same `ManagedSettingsStore` that shields apps can also shield web domains. Worth including from the start on macOS since it's no extra work.

**Web**: Not directly possible from a web app. A companion browser extension could be added later if needed.

---

## 6. Verification Systems

### 6.1 Manual Verification

User taps "complete." Simplest case. Susceptible to faking, but appropriate for many tasks.

### 6.2 GPS/Geolocation Verification

- Define a geofence: center point (lat/lng) + radius
- Optionally require minimum duration at location
- **Android**: `FusedLocationProviderClient` with geofencing API
- **macOS**: `CLLocationManager` with `CLCircularRegion` monitoring
- Two modes controlled by `verification_config.mode`:
  - **`enter`** (default): Task completes when user arrives at the geofenced location and optionally stays for N minutes (e.g. "go to the gym")
  - **`leave`**: Task completes when user departs the geofenced area (e.g. "leave home before 9am", "get out of the office by 6pm")
- Proof stored in `verification_data`: coordinates, accuracy, timestamps

### 6.3 Duration / Timer Verification

- Task defines a required duration (e.g., 30 minutes of reading)
- Timer runs in the foreground service
- Must complete full duration — no early exit
- Pausing (if allowed) stops the timer; total accumulated time must reach the target

### 6.4 Meditation Timer

- Special case of duration verification
- Full-screen meditation UI with:
  - Timer countdown
  - Optional interval bells
  - Ambient sound/silence toggle
  - Session progress indicator
- **Cannot be exited early** — leaving the screen pauses or resets the timer (configurable)
- App must remain in foreground for the full duration
- On Android: detect if the user switches apps via the accessibility service; pause the meditation
- Completion only logged when the full duration elapses
- Stored proof: `{"actual_seconds": 600, "pauses": 0, "completed": true}`

### 6.5 Time-of-Day Gates

- Task is only completable during a defined window
- E.g., "Morning routine" available 5:00 AM – 10:00 AM
- UI grays out the task outside the window
- Prevents "completing tomorrow's tasks tonight to avoid blocking"

---

## 7. Notes System

### 7.1 MVP: Quick Capture + Markdown

Start with a simple markdown editor:

- Plain text input with markdown rendering toggle/split view
- Title + body
- Tags and group assignment
- Full-text search across notes

### 7.2 Wikilinks

Support `[[Note Title]]` syntax in markdown content:

- On save, parse content for `[[...]]` patterns
- Resolve to existing note IDs (fuzzy match on title)
- Store resolved links in `outgoing_links` array for fast backlink queries
- Backlinks query: `SELECT * FROM notes WHERE :this_note_id = ANY(outgoing_links)`
- Unresolved links shown as "create new note?" affordance

### 7.3 Future: Obsidian-Level Features

- Graph view of linked notes
- Daily notes
- Templates
- Transclusion (`![[Embedded Note]]`)

---

## 8. Development Bootstrap

### 8.1 Purpose

To iterate on task completion logic and blocking behavior without building a task creation UI, a `bootstrap.json` file should be created and loaded into the database at development time. This populates a test user's account with tasks covering all completion types and varying levels of complexity, so that the blocking evaluator and completion flows can be tested end-to-end immediately.

### 8.2 Bootstrap File Location

```
brainfence/
  dev/
    bootstrap.json      ← seed data for test user
    bootstrap.ts        ← script that reads bootstrap.json and upserts to Supabase
```

### 8.3 Bootstrap JSON Structure

```json
{
  "user_email": "test@brainfence.dev",
  "groups": [...],
  "tasks": [
    {
      "title": "Morning Walk",
      "task_type": "simple",
      "verification_type": "gps",
      "verification_config": {
        "lat": 37.7749, "lng": -122.4194, "radius_m": 200,
        "mode": "leave", "min_duration_m": 0
      },
      "recurrence_type": "daily",
      "is_blocking_condition": true
    },
    {
      "title": "Morning Meditation",
      "task_type": "meditation",
      "verification_type": "meditation",
      "verification_config": { "duration_seconds": 600, "allow_pause": false },
      "recurrence_type": "daily",
      "is_blocking_condition": true
    },
    {
      "title": "Read 30 Minutes",
      "task_type": "timed",
      "verification_type": "duration",
      "verification_config": { "duration_seconds": 1800 },
      "recurrence_type": "daily",
      "is_blocking_condition": false
    },
    {
      "title": "Morning Routine",
      "task_type": "routine",
      "verification_type": "manual",
      "recurrence_type": "daily",
      "is_blocking_condition": true,
      "steps": [
        { "title": "Brush teeth", "step_type": "checkbox" },
        { "title": "Cold shower", "step_type": "timed", "config": { "duration_seconds": 120 } },
        { "title": "Journaling", "step_type": "timed", "config": { "duration_seconds": 300 } }
      ]
    },
    {
      "title": "Gym — Push Day",
      "task_type": "workout",
      "verification_type": "gps",
      "verification_config": {
        "lat": 37.7849, "lng": -122.4094, "radius_m": 100,
        "mode": "enter", "min_duration_m": 30
      },
      "recurrence_type": "daily",
      "recurrence_config": { "days": ["mon", "wed", "fri"] },
      "is_blocking_condition": false,
      "steps": [
        { "title": "Bench Press", "step_type": "weight_reps", "config": { "default_sets": 3 } },
        { "title": "Overhead Press", "step_type": "weight_reps", "config": { "default_sets": 3 } },
        { "title": "Tricep Dips", "step_type": "just_reps" }
      ]
    },
    {
      "title": "Take Vitamins",
      "task_type": "simple",
      "verification_type": "time_gate",
      "verification_config": { "start_time": "07:00", "end_time": "10:00", "timezone": "America/Los_Angeles" },
      "recurrence_type": "daily",
      "is_blocking_condition": false
    },
    {
      "title": "Weekly Review",
      "task_type": "simple",
      "verification_type": "manual",
      "recurrence_type": "weekly",
      "recurrence_config": { "day": "sun" },
      "is_blocking_condition": false
    }
  ],
  "blocking_rules": [
    {
      "name": "Morning Block",
      "blocked_apps": [
        { "platform": "android", "package": "com.twitter.android" },
        { "platform": "android", "package": "com.instagram.android" }
      ],
      "blocked_domains": ["twitter.com", "x.com", "instagram.com", "reddit.com"],
      "condition_task_ids": ["<morning_walk_id>", "<morning_meditation_id>", "<morning_routine_id>"],
      "condition_logic": "all",
      "active_schedule": { "days": ["mon","tue","wed","thu","fri","sat","sun"], "start": "06:00", "end": "11:00" }
    }
  ]
}
```

The bootstrap script should be idempotent (upsert by title) and should print a summary of what was created/updated. Task IDs for blocking rules are resolved by title after insertion.

---

## 9. Implementation Phases

### Phase 1: Core Data Model + Sync Infrastructure (Weeks 1–4)

**Goal**: Data model working end-to-end with local-first sync.

- [ ] Set up Supabase project (Postgres schema, auth, RLS policies)
- [ ] Set up PowerSync project (sync rules scoped to user_id)
- [ ] Android app scaffold: Kotlin + Compose + PowerSync SDK + Supabase auth
- [ ] macOS app scaffold: Swift + SwiftUI + PowerSync Swift SDK + Supabase auth
- [ ] Implement core tables: tasks, routine_steps, task_completions, step_completions, groups
- [ ] Implement recurrence engine (RRULE parsing + custom_freq_minutes)
- [ ] Verify offline create/edit/complete → sync → appears on other device
- [ ] Basic CRUD UI for tasks (list, create, edit, complete)
- [ ] Bootstrap script: `dev/bootstrap.json` + loader for rapid iteration

### Phase 2: Task/Habit System + App Blocking (Weeks 5–10)

**Goal**: The "killer feature" — tasks gate app access.

- [ ] Blocking rules data model + CRUD UI
- [ ] Android: AccessibilityService + foreground service + boot receiver
- [ ] Android: Blocking overlay activity with task completion flow
- [ ] macOS: FamilyControls authorization + ManagedSettings shielding (apps + web domains)
- [ ] macOS: Custom ShieldConfiguration showing task requirements
- [ ] macOS: Login item / always-running setup
- [ ] Anti-bypass: Time-lock on config changes (both platforms)
- [ ] Anti-bypass: Multi-step disable flow
- [ ] Verification: GPS geofencing — enter mode (arrive at location)
- [ ] Verification: GPS geofencing — leave mode (depart from location)
- [ ] Verification: Duration timer
- [ ] Verification: Meditation timer (full-screen, no early exit)
- [ ] Verification: Time-of-day gates
- [ ] Routine/workout UI: ordered steps, weight/reps input, pre-fill from last completion
- [ ] Recurring tasks: next occurrence calculation, completion history view

### Phase 3: Notes + Wikilinks (Weeks 11–14)

**Goal**: Markdown notes with local wikilinks and organization.

- [ ] Notes table + CRUD
- [ ] Markdown editor (basic: plain text input + rendered preview)
- [ ] Wikilink parsing: `[[Note Title]]` extraction and resolution
- [ ] Backlinks query and UI
- [ ] Tags and groups with visibility scheduling (work hours / life hours)
- [ ] Full-text search across notes and tasks
- [ ] Web app for notes (admin/testing — lower priority)

---

## 10. Open Questions & Recommendations

### Multi-Device Sync for Blocking Rules

**Recommendation**: Yes, sync blocking rules across devices. If your phone blocks Twitter, your laptop should too. PowerSync handles this automatically — the blocking_rules table syncs to all devices. Each device evaluates rules locally against its platform-specific app identifiers.

### Website Blocking Scope

**Decision**: App blocking only on Android (via AccessibilityService). On macOS, web domain blocking comes for free via the same Screen Time API that handles app blocking — include it there. Android website blocking (VPN-based) can be added later if needed without architectural changes.

### Notes Complexity for MVP

**Recommendation**: Start with "quick capture" (Apple Notes level) + markdown + wikilinks. The wikilink system is what makes this app's notes unique — prioritize that over rich editing features. Obsidian-level features (graph view, transclusion, plugins) can come later.

### Google Play Store (Future)

**Recommendation**: If you ever want Play Store distribution, the accessibility service approach may not be approved. Consider the Android Device Policy Controller approach or an enterprise MDM profile as an alternative path. For now, sideloading is the pragmatic choice.
