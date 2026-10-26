# Google Play: sensitive permission declarations

Text for Play Console → Policy → App content, for each sensitive permission or
API. Play asks for these once a bundle that uses them is uploaded. Each
declaration needs a short video (YouTube, unlisted is fine) showing the in-app
disclosure, the user agreeing, and the feature that needs the permission.

The in-app disclosures are in
`android/app/src/main/java/dev/brainfence/ui/setup/Disclosures.kt`. Keep this
file, those strings and `site/privacy/index.html` saying the same thing.

## Accessibility Service API

Brainfence is not an accessibility tool (`isAccessibilityTool` is not set).

**Why the app needs it** (core functionality):

> Brainfence is a digital wellbeing app that blocks the apps and websites a user chooses until they complete tasks they set for themselves. The Accessibility Service is the core of that blocking:
> 1. It receives window state change events, to tell which app is in the foreground. When it's an app in one of the user's active blocking rules, Brainfence shows a full-screen blocking screen over it.
> 2. In supported browsers (Chrome, Firefox, Edge, Brave, Samsung Internet, Opera, Vivaldi, DuckDuckGo and others), it reads the address bar's text by view ID, to tell which website is open, so the websites in the user's rules can be blocked. While websites are blocked, browsers it can't read are covered too.
>
> It reads nothing else on screen and performs no actions (no gestures, typing or clicks). Foreground app names and website addresses are used on the device to decide whether to block, and are never stored or transmitted. There is no other API that lets an app see the foreground app immediately and the current page of a third-party browser, which website blocking requires.

**Prominent disclosure:** shown before the user is sent to Accessibility
settings, from the post-sign-in setup screen and from the home screen's
"accessibility is off" banner. Only **Agree** continues.

**Video:** sign in → the disclosure screen → Agree → Accessibility settings →
turn on Brainfence → back in the app, open a blocked app → blocking screen →
open a blocked website in Chrome → blocking screen.

## Location: ACCESS_BACKGROUND_LOCATION

**Feature that needs it:**

> GPS tasks complete automatically when the user arrives at or leaves a place (for example "go for a walk": leave home), shopping lists remind the user when they're near the store, and rules can be set to block only while the user is home. All of these happen while the app is closed, using geofences and a location foreground service, so they need background location.

**Why foreground-only location isn't enough:** the user isn't looking at the
app when they arrive somewhere or leave home; the task has to complete (and
unblock their apps) or remind them at that moment.

**Prominent disclosure:** shown before the background location request (the
home screen's location banner). Only **Agree** continues.

**Video:** create a GPS task → location banner → foreground permission →
disclosure → Agree → "Allow all the time" → walk out of the geofence (or use
the emulator's location controls) → the task completes with the app closed.

## Foreground service types

| Type | Task |
| --- | --- |
| `specialUse` | Keeps the user's app and website blocking rules enforced, and task timers and reminders running, while rules are active. The user can see it as an ongoing notification. |
| `location` | Tracks arrival and departure for GPS tasks and home-only blocking while the app is in the background. |

Video: the ongoing notification while a rule is active, and a GPS task in
progress.

## Not requested (so no declaration)

- `QUERY_ALL_PACKAGES`: replaced by `<queries>` for launcher apps and browsers.
- `USE_EXACT_ALARM`: the app uses `SCHEDULE_EXACT_ALARM`, which the user grants.
- `PACKAGE_USAGE_STATS` (Usage Access) has no Play form; the privacy policy covers it.
