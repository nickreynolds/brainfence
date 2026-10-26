# Google Play: Data safety and App content answers

Answers for Play Console → Policy → App content. They have to match
`site/privacy/index.html` and what the app does; update all three together.

Under Play's rules, data that's only processed on the device isn't
"collected". That covers the foreground app and browser addresses the
Accessibility Service sees, usage stats beyond a task's total, and live
location beyond a GPS task's proof.

## Data collection and security

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | Yes |
| Is all of the user data collected by your app encrypted in transit? | Yes |
| Do you provide a way for users to request that their data is deleted? | Yes. In the app, and at https://brainfence.online/delete-account/ |

## Data types

Nothing is **shared** (sent to third parties other than service providers).
Nothing is processed ephemerally. Every purpose is **App functionality**, plus
**Account management** for the email address.

| Category → type | Collected | Required? | What it is |
| --- | :-: | --- | --- |
| Personal info → Email address | ✅ | Required | Sign-in and account emails |
| Location → Precise location | ✅ | Optional | A GPS task's completion proof, and places chosen for tasks |
| Location → Approximate location | ✅ | Optional | Same data (precise location implies it) |
| App activity → Installed apps | ✅ | Optional | The apps the user picks to block, or as companion apps |
| App activity → Other user-generated content | ✅ | Required | Tasks, routines, shopping lists, journal entries, notes, contemplations, reports |
| App activity → App interactions | ✅ | Required | Task completions, including time spent in a companion app |
| Web browsing → Web browsing history | ❌ | | Addresses are only checked on the device. The blocked domains the user types are user-generated content. |
| App info and performance (crash logs, diagnostics) | ❌ | | Debug logs stay on the device |
| Device or other IDs | ❌ | | |
| Everything else (financial, health, messages, photos, audio, files, calendar, contacts) | ❌ | | |

## Other App content forms

| Form | Answer |
| --- | --- |
| Privacy policy | https://brainfence.online/privacy/ |
| App access | Some functionality is restricted: needs an account. Give a demo account (create one for reviewers, run the setup guide, and leave one active rule blocking a common app such as Chrome or YouTube). |
| Ads | No ads |
| Content rating | Fill in the questionnaire: Utility/Productivity, no violence, sexual content, gambling, user-to-user communication or location sharing with other users. Expect "Everyone". |
| Target audience | 18 and over (or 13+). Not for children, so the Families policy doesn't apply. |
| News app | No |
| Government app | No |
| Financial features | None |
| Health apps | **Decide:** Brainfence has meditation timers, which Play may count as "mental wellness". If unsure, tick the mental health/wellness category; an app that's only a productivity tool can answer that it has no health features. |
| Advertising ID | Not used (Brainfence doesn't declare `AD_ID`) |
| Sensitive permissions | See `permissions.md` |
