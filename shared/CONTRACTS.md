# Cross-platform contracts

Every client (Android, macOS, Windows) reads and writes the same synced rows, so
the rules below must behave identically everywhere. A client that is more lenient
than the others sets the real level of protection for all of them.

Code can't be shared across Kotlin, Swift and TypeScript, so this folder shares
definitions and test cases instead:

| Path | What | Consumed by |
| --- | --- | --- |
| `schema.json` | Synced tables and columns, with which columns hold JSON or booleans | `codegen.mjs` → each client's PowerSync schema and upload column lists |
| `tokens.json` | Color palette (Material 3 roles, light and dark) | `codegen.mjs` → Compose colors, CSS variables, SwiftUI colors |
| `fixtures/*.json` | Test cases for the rules below | Each client's unit tests load them directly |

Run `npm run codegen` from the repo root after editing `schema.json` or
`tokens.json`, and commit the generated files. `npm run codegen:check` fails if
they are out of date. Generated files (don't edit by hand):

| Client | From `schema.json` | From `tokens.json` |
| --- | --- | --- |
| Android | `data/db/AppSchema.kt` (schema + `JSON_ENCODED_COLUMNS`) | `ui/theme/Color.kt` |
| Windows | `src/shared/schema.generated.ts` | `src/renderer/src/tokens.css` |
| macOS | `Brainfence/Sync/AppSchema.swift`, `BrainfenceCore/…/SchemaColumns.swift` | `Brainfence/Theme/Palette.swift` |

The palette starts as the Material 3 baseline that Android was already
rendering (Compose material3 1.3.1 defaults, plus Android's original light
`primary`). Change it in `tokens.json` to restyle every client at once.

## Platform-tagged app entries

`blocking_rules.blocked_apps` and the meditation `verification_config.companion_apps`
hold entries from every platform in one array:

| Platform | Entry | Identity |
| --- | --- | --- |
| Android | `{"platform": "android", "package": "com.twitter.android"}` | `android:<package>` |
| Android (legacy) | `"com.twitter.android"` (bare string) | `android:<string>` |
| macOS | `{"platform": "macos", "bundle_id": "com.hnc.Discord"}` | `macos:<bundle_id>` |
| Windows | `{"platform": "windows", "exe": "discord.exe"}` | `windows:<exe, lowercased>` |
| Anything else | `{"platform": "<p>", …}` | `<p>:<entry as JSON, keys sorted, no whitespace>` |

Entries may carry extra fields (such as a display `name`); they don't change the identity.

- An editor only manages its own platform's entries. On save it **must** write
  every other entry back unchanged, including platforms it doesn't recognize.
- Sets of entries are compared by identity (`fixtures/blocked-app-keys.json`).

## Rule edits and the time-lock

Editing an existing rule goes through `pending_changes`: a JSON object with any of
`name`, `blocked_apps`, `blocked_domains`, `condition_task_ids`, `condition_logic`,
`is_active` (0/1) and `config_lock_hours`. If the change can only make blocking
stricter it is applied immediately. Otherwise it is stored in `pending_changes`
with `changes_apply_at = now + config_lock_hours`, and any client applies it once
that time has passed.

A change is **stricter** only if every field it sets passes these checks
(`fixtures/rule-restrictiveness.json`):

- `blocked_apps`: the new set contains every current entry (by identity, across all platforms).
- `blocked_domains`: the new set contains every current domain (after normalizing).
- `condition_logic`: not `"all"` → `"any"`.
- `condition_task_ids`, using the logic the rule will have after the change. Unchanged sets are always fine; otherwise:
  - The rule has no conditions now (it always blocks): any change is weaker.
  - `"all"`: the new set must contain every current task.
  - `"any"`: the new set must be a subset of the current one, since each task is a way to unblock. An empty set is stricter, because the rule then always blocks.
- `is_active`: not 1 → 0.
- `config_lock_hours`: not lowered.
- `name`: neutral.

## Domains

`blocked_domains` entries are normalized before use and before comparing
(`fixtures/domains.json`): lowercase; drop the scheme, path, query, fragment,
user info and port; drop a leading `*.` and then `www.`; drop trailing dots.
An entry with no dot left is ignored. A host is blocked if it equals a
blocked domain or is a subdomain of it.

## Timestamps

Clients write ISO-8601 UTC (`2026-04-16T14:03:00.123Z`). Rows replicated from
Postgres arrive in its text form (`2026-04-16 14:03:00.123+00`). Always parse
before comparing; never compare the strings (`' ' < 'T'`, so a string comparison
can apply a time-locked change up to a day early). Text without an offset is UTC
(`fixtures/timestamps.json`).

## Onboarding

The setup guide (first-time user experience) teaches how Brainfence works and
creates a starter set of tasks, a blocking rule and the Five Remembrances deck.
Its state is the synced `onboarding` table: one row per user with
`id = user_id`, so devices that finish offline upsert the same row.

### Where a device goes (`fixtures/onboarding.json` → `route`)

Decided from the `onboarding` row, the number of active tasks, and the platform.
Android and Windows **can locate** (they have a home location). The Mac can't.

| Situation | Route |
| --- | --- |
| The first sync hasn't finished | `wait`: show nothing yet, since the row may still be on its way |
| No row, and the account has tasks | `auto_dismiss`: write a `dismissed` row and show nothing (an existing user) |
| No row, no tasks | `guide`: the full setup guide |
| `dismissed` | `none` |
| `completed`, this platform can locate and `deferred_steps` isn't empty | `device_setup` |
| `completed`, and this platform's `<platform>_setup_at` is null | `device_setup` |
| Anything else | `none` |

"Run setup guide" in settings opens the full guide whatever the row says.

### Starters (`fixtures/onboarding.json` → `plan`)

Each starter can be turned on or off, and its `available_from` / `due_at`
changed. Everything else is fixed. All starters are `status = 'active'`, with
`tags = []`, `blocking_rule_ids = []`, `blocking_days_of_week = []` and
`home_only_blocking = 0`.

| Key | Order | Title | task_type / verification_type | Recurrence | Blocks | Window | verification_config |
| --- | :-: | --- | --- | --- | :-: | --- | --- |
| `morning_routine` | 0 | Morning Routine | `routine` / null | daily | yes | 06:00–09:00 | `{}`; steps Make Bed (checkbox), Take Pills (checkbox), Reverse Plank (timed, 30 s) |
| `morning_walk` | 1 | Morning Walk | `simple` / `gps` | daily | yes | 06:00–11:00 | leave home: `latitude`, `longitude`, `radius_meters` from the home location, `mode: "leave"`, `min_duration_m: 0` |
| `meditation` | 2 | Meditation | `simple` / `meditation` | daily | yes | 06:00–19:00 | `duration_seconds: 600`, `short_sit_seconds: 120`, `companion_apps` from this platform |
| `journaling` | 3 | Journal | `journal` / `journal` | daily | no | 18:00–23:00 | `{}` |
| `groceries` | 4 | Groceries | `shopping` / `gps` | none | no | none | the store's `latitude` / `longitude`, `radius_meters: 100`, `mode: "notify"`, `notify_window` every day 08:00–21:00 |
| `five_remembrances` | | | the `five_remembrances` library deck, unless the account already has one | | | | |

- `sort_order` is the order column, so starters keep their order even when created later.
- **Location starters** (`morning_walk` needs a home, `groceries` a store) are
  *deferred* when the location isn't known, which is always the case on the
  Mac. A deferred starter isn't created; it goes in `deferred_steps` as
  `{"key", "available_from", "due_at"}` (the times only for starters that have
  a window). A device that can locate finishes it in device setup by building
  a plan from those entries.
- **The rule** is named "Distractions" with `condition_logic: "all"`. Its
  conditions are the blocking starters created *now*, in order. It holds this
  platform's chosen apps and the chosen domains (normalized, deduplicated). It is
  created only if it would block something and has at least one condition. A
  rule with no conditions would always block.
- Adding the Five Remembrances also turns contemplation delivery on for this
  device, since the schedule is per device.

### Writing the row

- **Finishing the guide** upserts `status: 'completed'`, `completed_at`,
  `deferred_steps` (replaced) and this platform's `<platform>_setup_at = now`.
  `blocking_rule_id` and `meditation_task_id` take the new ids, keeping the old
  ones when nothing new was made. If either is new, the other platforms'
  `_setup_at` is cleared so they offer device setup again.
- **Skipping the guide** upserts `status: 'dismissed'` without touching the other columns.
- **Device setup** offers whichever of these apply, and then sets
  `<platform>_setup_at = now`. On a device that can locate, `deferred_steps`
  keeps only the starters the user kept but still has no location for
  ("later"). Skipping device setup clears them.
  The options:
  - **This platform's apps for the guide's rule.** Adding apps is a stricter
    change (see the time-lock above), so it applies at once.
  - **This platform's companion apps for the guide's meditation task.** Other
    platforms' entries are kept.
  - **Home location and the deferred starters**, on a device that can locate.
    A deferred blocking starter is added to the guide's rule as a condition
    once it's created. That goes through the normal rule-change path: under
    `"all"` adding a condition is stricter, so it applies at once.
  - **Contemplation delivery on this device**, when the account has an enabled deck.

## Email links

Sign-up asks Supabase to send the confirmation link to
`brainfence://auth-callback`, which every client registers as a URL scheme.
All clients use the PKCE flow. The project's Site URL and Redirect URLs must
include it (`npm run deploy` sets both when `SUPABASE_ACCESS_TOKEN` is in
`.env`). Supabase confirms the email *before* redirecting, so a link that can't
sign the user in has still confirmed them.

A link is classified like this (`fixtures/auth-callback.json`):

| Link carries | Kind | The client |
| --- | --- | --- |
| `error_description` or `error` (query or fragment) | `error` | Shows "That link didn't work: *message*. Sign up again with the same email to get a new one." |
| `code` (query) | `code` | Exchanges it for a session. If that fails (e.g. the link was opened on a different device than the one that signed up), shows "Your email is confirmed. Sign in to continue." |
| `access_token` and `refresh_token` (fragment) | `tokens` | Signs in with them. Shows the "confirmed" notice if that fails. |
| Anything else | `none` | Shows "Your email is confirmed. Sign in to continue." |

Other `brainfence://` URLs (and other schemes) aren't auth callbacks.

## Account deletion

Every client offers **Delete account** next to Sign out (Google Play requires
it for apps with accounts). It asks for confirmation, saying that this
permanently deletes the account and all of its data on every device, and
can't be undone. Then, in this order:

1. Call the `delete_account()` database function (RPC, no arguments). It
   deletes the caller's `auth.users` row; every table's `user_id` cascades.
   This needs a connection. If it fails, nothing else happens and the client
   shows the error.
2. Clear the local database (PowerSync `disconnectAndClear`). Blocking stops
   because no rules are left.
3. Sign out locally only (the server session went with the user).

Other devices still signed in to the account lose their session when it next
refreshes, and keep whatever their own sign-out keeps.

Without the app, people can ask for deletion at
`https://brainfence.online/delete-account` (`site/`), which is the URL given to
Google Play.
