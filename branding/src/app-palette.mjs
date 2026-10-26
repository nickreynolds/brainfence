// Round 4: the app palette section of the gallery. Each direction in
// schemes.mjs is shown as the same three app screens in light and dark, drawn
// in HTML from that scheme's color roles, then its full role list and contrast
// checks. The screens are stand-ins for the real apps: they use each role the
// way the clients do, so judge the colors, not the layout.

import { ROLES, schemes, PRIMITIVES } from './schemes.mjs';

const kebab = (s) => s.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
const vars = (colors) => ROLES.map((r) => `--c-${kebab(r)}:${colors[r]}`).join(';');

const PATHS = {
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  unlock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 7.6-1.8"/>',
  clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  circle: '<circle cx="12" cy="12" r="8"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  tasks: '<path d="M9 7h11M9 12h11M9 17h11M4.5 7h.5M4.5 12h.5M4.5 17h.5"/>',
  rules: '<path d="M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"/>',
  chart: '<path d="M5 20V11M12 20V4M19 20v-6"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/>',
  pin: '<path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.3"/>',
  alert: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5v5.5M12 16.2v.3"/>',
};
const icon = (name, cls = '') => `<svg class="ic ${cls}" viewBox="0 0 24 24" aria-hidden="true">${PATHS[name]}</svg>`;
const crown = (cls) => `<img class="${cls}" data-mark="chainlink-crown" data-v="icon" alt="">`;

const taskList = () => `
<div class="phone">
  <div class="p-bar">${crown('p-logo')}<b>Today</b><span class="p-avatar">N</span></div>
  <div class="p-banner">${icon('lock')}<div><b>Instagram and YouTube are blocked</b><span>Finish 2 tasks to unlock them</span></div></div>
  <div class="p-list">
    <div class="p-task">
      ${icon('circle', 'muted')}<div><b>Morning routine</b><span>3 of 5 steps</span><i class="p-progress"><i style="width:60%"></i></i></div>
    </div>
    <div class="p-task">
      ${icon('clock', 'tertiary')}<div><b>Meditate</b><span class="tertiary">06:12 left</span></div><em class="p-pill timer">Running</em>
    </div>
    <div class="p-task done">
      ${icon('check', 'unlocked')}<div><b>Inbox zero</b><span>Done at 8:40</span></div>
    </div>
    <div class="p-task">
      ${icon('pin', 'muted')}<div><b>Walk to the park</b><span>GPS · 20 min</span></div>
    </div>
  </div>
  <span class="p-fab">${icon('plus')}</span>
  <nav class="p-nav"><span class="on"><i>${icon('tasks')}</i>Tasks</span><span><i>${icon('rules')}</i>Rules</span><span><i>${icon('chart')}</i>Reports</span></nav>
</div>`;

const blockScreen = () => `
<div class="phone block">
  <div class="b-top">
    ${crown('b-logo')}
    <em class="b-badge">${icon('lock')}Blocked</em>
    <b>Instagram is fenced off</b>
    <span>Rule: Social media · until these are done</span>
  </div>
  <div class="b-body">
    <div class="p-task">${icon('circle', 'muted')}<div><b>Morning routine</b><span>3 of 5 steps</span></div></div>
    <div class="p-task">${icon('clock', 'tertiary')}<div><b>Meditate</b><span class="tertiary">06:12 left</span></div></div>
    <span class="btn filled">Open Brainfence</span>
    <span class="btn text">Close</span>
  </div>
</div>`;

const rulesWindow = () => `
<div class="desk">
  <div class="d-title"><i></i><i></i><i></i><span>Brainfence</span></div>
  <div class="d-main">
    <aside class="d-side">
      <div class="d-brand">${crown('d-logo')}<b>Brainfence</b></div>
      <span>${icon('tasks')}Tasks</span>
      <span class="on">${icon('rules')}Rules</span>
      <span>${icon('chart')}Reports</span>
      <span>${icon('gear')}Settings</span>
      <small class="d-sync"><i></i>Synced</small>
    </aside>
    <section class="d-content">
      <div class="d-head"><b>Blocking rules</b><span class="btn filled sm">${icon('plus')}New rule</span></div>
      <div class="d-card">
        <div class="d-row">${icon('lock', 'blocked')}<b>Social media</b><em class="d-badge blocked">Blocking now</em><span class="d-switch on"><i></i></span></div>
        <div class="d-chips"><span>Instagram</span><span>YouTube</span><span>reddit.com</span></div>
        <div class="d-pending">${icon('clock')}<span>Loosening this rule takes effect in 23 h 12 m</span><span class="btn text sm">Cancel change</span></div>
      </div>
      <div class="d-card">
        <div class="d-row">${icon('unlock', 'unlocked')}<b>News sites</b><em class="d-badge unlocked">Unlocked</em><span class="d-switch on"><i></i></span></div>
        <div class="d-chips"><span>nytimes.com</span><span>news.ycombinator.com</span></div>
        <div class="d-field"><label>Daily limit</label><span class="d-input err">90 min</span><small class="err-text">${icon('alert')}Limit can only go down for 24 h</small></div>
      </div>
      <div class="d-toast">${icon('alert')}<span>Couldn't save rule.</span><span class="btn text sm">Retry</span></div>
    </section>
  </div>
</div>`;

const SCREENS = [
  ['Task list', taskList, 'phones'],
  ['Block screen', blockScreen, 'phones'],
  ['Desktop rules', rulesWindow, 'desks'],
];

const LEGEND = [
  ['Main action', 'primary'],
  ['FAB, highlight', 'primaryContainer'],
  ['Blocked', 'blocked'],
  ['Unlocked', 'unlocked'],
  ['Pending change', 'pending'],
  ['Timer', 'tertiary'],
  ['Error', 'error'],
];

function schemeArticle(s) {
  const screens = SCREENS.map(
    ([name, draw, kind]) =>
      `<figure class="pairing ${kind}">` +
      ['light', 'dark'].map((m) => `<div class="app" data-mode="${m}" style="${vars(s.colors[m])}">${draw()}</div>`).join('') +
      `<figcaption>${name} · light, dark</figcaption></figure>`,
  ).join('');
  const legend = LEGEND.map(
    ([label, role]) =>
      `<li><span class="dot" style="background:${s.colors.light[role]}"></span><span class="dot" style="background:${s.colors.dark[role]}"></span>${label}</li>`,
  ).join('');
  const roles = ['light', 'dark']
    .map(
      (m) =>
        `<div class="roles"><p class="label">${m}</p><ul>` +
        ROLES.map(
          (r) => `<li><span class="chip" style="background:${s.colors[m][r]}"></span><code>${r}</code><code class="hex">${s.colors[m][r]}</code></li>`,
        ).join('') +
        `</ul></div>`,
    )
    .join('');
  const fails = ['light', 'dark'].reduce((n, m) => n + s.checks[m].filter((c) => !c.pass).length, 0);
  const total = s.checks.light.length * 2;
  const cell = (c) => `<td class="${c.pass ? 'ok' : 'bad'}">${c.pass ? '✓' : '✗'} ${c.ratio.toFixed(2)}</td>`;
  const rows = s.checks.light
    .map((c, i) => `<tr><th scope="row">${c.use}<code>${c.fg} / ${c.bg}</code></th><td>${c.min}</td>${cell(c)}${cell(s.checks.dark[i])}</tr>`)
    .join('');
  return `
<article class="scheme" data-scheme="${s.id}">
  <div class="scheme-head">
    <h3>${s.name}</h3>
    <p>${s.pitch}</p>
    <ul class="legend" aria-label="What each color means (light, dark)">${legend}</ul>
  </div>
  <div class="screens">${screens}</div>
  <div class="cols">
    <div class="pair"><p class="label">Contrast · ${total - fails} of ${total} pass</p>
      <div class="table-wrap"><table class="contrast"><thead><tr><th scope="col">Where</th><th scope="col">Min</th><th scope="col">Light</th><th scope="col">Dark</th></tr></thead><tbody>${rows}</tbody></table></div>
    </div>
    <details class="pair"><summary class="label">All ${ROLES.length} roles</summary><div class="role-cols">${roles}</div></details>
  </div>
</article>`;
}

const primitives = () =>
  `<div class="prims">` +
  PRIMITIVES.map(
    (p) =>
      `<div class="prim"><p class="label">${p.name} <code>${p.seed}</code></p><ol>` +
      p.tones.map(([t, hex]) => `<li style="background:${hex};color:${t < 60 ? '#fff' : '#000'}" title="${hex}">${t}</li>`).join('') +
      `</ol></div>`,
  ).join('') +
  `</div>`;

export const paletteSection = () => `
  <section id="app-palette" hidden>
    <h2>App palette</h2>
    <p class="round-note">Foil was picked, and all three apps now use it from shared/tokens.json. They used to use Material’s stock purple, which isn’t in the icon anywhere. These are the three ways to carry the Static palette into the apps, each with a light and a dark theme. Every color comes from a brand color’s tonal ramp, and the dark themes use the icon’s exact void and neon.</p>
    <p class="intro">Light mode is the hard part. Lime and cyan are light colors, so on white they can only be fills, never text. The ramps below show what each brand color becomes at every tone. Each direction also adds three colors for blocking state, so every client shows blocked, unlocked and pending the same way.</p>
    ${primitives()}
    <div class="pals" id="schemes" role="radiogroup" aria-label="Direction">${schemes
      .map((s) => `<button class="pal" type="button" role="radio" data-scheme-btn="${s.id}"><span class="sw">${['primary', 'primaryContainer', 'blocked', 'unlocked', 'pending']
        .map((r) => `<i style="background:${s.colors.dark[r]}"></i>`)
        .join('')}</span>${s.name}</button>`)
      .join('')}</div>
${schemes.map(schemeArticle).join('')}
  </section>`;

export const PALETTE_CSS = `
/* Round 4 app palette. Mock screens read only --c-* role variables, set inline per scheme and mode. */
.prims { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 12px 24px; }
.prim { display: grid; gap: 6px; }
.prim ol { list-style: none; margin: 0; padding: 0; display: grid; grid-template-columns: repeat(10, 1fr); }
.prim li { height: 34px; display: grid; place-items: end start; padding: 3px 4px; font: 500 10px/1 var(--mono); }
.prim code, .scheme code { font-family: var(--mono); font-size: 12px; }
.scheme { display: grid; gap: 28px; }
.scheme[hidden] { display: none; }
.scheme-head { display: grid; gap: 12px; }
.scheme-head p { margin: 0; max-width: 70ch; }
.legend { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 8px 20px; font: 400 13px/1 var(--mono); letter-spacing: .04em; text-transform: uppercase; color: var(--muted); }
.legend li { display: flex; align-items: center; gap: 4px; }
.legend li .dot + .dot { margin-right: 4px; }
.dot { width: 14px; height: 14px; display: inline-block; outline: 1px solid var(--line); }
.screens { display: flex; flex-wrap: wrap; gap: 32px; }
.pairing { display: grid; grid-template-columns: repeat(2, auto); gap: 12px; justify-items: stretch; justify-content: start; }
.pairing figcaption { grid-column: 1 / -1; justify-self: start; }
.pairing.desks { flex: 1 1 100%; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.table-wrap { overflow-x: auto; }
.contrast { width: 100%; border-collapse: collapse; font-size: 15px; }
.contrast th, .contrast td { text-align: left; padding: 6px 10px 6px 0; border-bottom: 1px solid var(--line); vertical-align: top; }
.contrast thead th { font: 500 12px/1.2 var(--mono); letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
.contrast tbody th { font-weight: 400; }
.contrast tbody th code { display: block; color: var(--muted); font-size: 11px; }
.contrast td { font-family: var(--mono); font-size: 13px; white-space: nowrap; }
.contrast .bad { color: var(--hot); font-weight: 500; }
details.pair summary { cursor: pointer; }
.role-cols { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 10px; }
.roles ul { list-style: none; margin: 6px 0 0; padding: 0; display: grid; gap: 3px; }
.roles li { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; gap: 8px; align-items: center; }
.roles .chip { width: 18px; height: 18px; outline: 1px solid var(--line); }
.roles code { overflow: hidden; text-overflow: ellipsis; }
.roles .hex { color: var(--muted); }

.app { --sys: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; font: 400 13px/1.35 var(--sys); color: var(--c-on-surface); text-transform: none; letter-spacing: 0; }
.app b { font-weight: 600; }
.app .ic { width: 20px; height: 20px; flex: none; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.app .muted { color: var(--c-on-surface-variant); }
.app .tertiary { color: var(--c-tertiary); }
.app .unlocked { color: var(--c-unlocked); }
.app .blocked { color: var(--c-blocked); }
.app .btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: 40px; padding: 0 22px; border-radius: 20px; font-weight: 600; font-size: 14px; }
.app .btn.sm { height: 30px; padding: 0 14px; font-size: 12px; }
.app .btn.sm .ic { width: 16px; height: 16px; }
.app .btn.filled { background: var(--c-primary); color: var(--c-on-primary); }
.app .btn.text { color: var(--c-primary); padding: 0 10px; }

.phone { position: relative; width: 250px; height: 520px; overflow: hidden; border-radius: 26px; background: var(--c-surface); border: 1px solid var(--line); display: flex; flex-direction: column; }
.p-bar { display: flex; align-items: center; gap: 10px; padding: 18px 16px 10px; font-size: 20px; }
.p-logo { width: 28px; height: 28px; border-radius: 7px; }
.p-avatar { margin-left: auto; width: 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; font-size: 13px; font-weight: 600; background: var(--c-tertiary-container); color: var(--c-on-tertiary-container); }
.p-banner { margin: 4px 12px 10px; padding: 12px; border-radius: 14px; display: flex; gap: 10px; background: var(--c-blocked-container); color: var(--c-on-blocked-container); }
.p-banner b, .p-task b { display: block; }
.p-banner span { font-size: 12px; }
.p-list { display: grid; gap: 8px; padding: 0 12px; }
.p-task { display: flex; gap: 10px; align-items: flex-start; padding: 11px 12px; border-radius: 12px; background: var(--c-surface-container-low); }
.p-task > div { flex: 1; min-width: 0; }
.p-task span { font-size: 12px; color: var(--c-on-surface-variant); }
.p-task span.tertiary { color: var(--c-tertiary); font-weight: 600; }
.p-task.done b { text-decoration: line-through; color: var(--c-on-surface-variant); font-weight: 400; }
.p-progress { display: block; height: 4px; margin-top: 6px; border-radius: 2px; background: var(--c-surface-container-highest); }
.p-progress i { display: block; height: 100%; border-radius: 2px; background: var(--c-primary); }
.p-pill { font-style: normal; font-size: 11px; font-weight: 600; padding: 3px 8px; border-radius: 8px; }
.p-pill.timer { background: var(--c-tertiary-container); color: var(--c-on-tertiary-container); }
.p-fab { position: absolute; right: 14px; bottom: 74px; width: 52px; height: 52px; border-radius: 16px; display: grid; place-items: center; background: var(--c-primary-container); color: var(--c-on-primary-container); box-shadow: 0 3px 8px rgb(0 0 0 / .25); }
.p-fab .ic { width: 24px; height: 24px; }
.p-nav { margin-top: auto; display: flex; justify-content: space-around; padding: 10px 6px 14px; background: var(--c-surface-container); color: var(--c-on-surface-variant); font-size: 11px; font-weight: 500; }
.p-nav span { display: grid; justify-items: center; gap: 3px; }
.p-nav i { display: grid; place-items: center; width: 52px; height: 28px; border-radius: 14px; }
.p-nav .on { color: var(--c-on-surface); }
.p-nav .on i { background: var(--c-secondary-container); color: var(--c-on-secondary-container); }

.block .b-top { display: grid; justify-items: center; gap: 8px; padding: 40px 18px 22px; text-align: center; background: var(--c-blocked-container); color: var(--c-on-blocked-container); }
.b-logo { width: 72px; height: 72px; border-radius: 18px; margin-bottom: 4px; }
.b-badge { display: inline-flex; align-items: center; gap: 5px; font-style: normal; font-weight: 700; font-size: 11px; letter-spacing: .08em; text-transform: uppercase; padding: 4px 10px 4px 8px; border-radius: 10px; background: var(--c-blocked); color: var(--c-on-blocked); }
.b-badge .ic { width: 14px; height: 14px; stroke-width: 2.5; }
.b-top > b { font-size: 19px; }
.b-top > span { font-size: 12px; }
.b-body { display: grid; gap: 8px; padding: 16px 12px; }
.b-body .btn { margin-top: 2px; }

.desk { min-width: 0; border-radius: 10px; overflow: hidden; border: 1px solid var(--line); background: var(--c-surface); display: flex; flex-direction: column; height: 100%; }
.d-title { display: flex; align-items: center; gap: 7px; padding: 8px 12px; background: var(--c-surface-container-high); color: var(--c-on-surface-variant); font-size: 12px; }
.d-title i { width: 11px; height: 11px; border-radius: 50%; background: var(--c-outline-variant); }
.d-title span { margin-left: 6px; }
.d-main { display: flex; min-height: 0; flex: 1; }
.d-side { width: 150px; flex: none; display: flex; flex-direction: column; gap: 2px; padding: 12px 8px; background: var(--c-surface-container-low); color: var(--c-on-surface-variant); }
.d-brand { display: flex; align-items: center; gap: 8px; padding: 0 6px 12px; color: var(--c-on-surface); }
.d-logo { width: 24px; height: 24px; border-radius: 6px; }
.d-side > span { display: flex; align-items: center; gap: 9px; padding: 7px 8px; border-radius: 8px; }
.d-side > span .ic { width: 17px; height: 17px; }
.d-side > span.on { background: var(--c-secondary-container); color: var(--c-on-secondary-container); font-weight: 600; }
.d-sync { margin-top: auto; display: flex; align-items: center; gap: 6px; padding: 8px; font-size: 11px; }
.d-sync i { width: 8px; height: 8px; border-radius: 50%; background: var(--c-unlocked); }
.d-content { flex: 1; min-width: 0; display: grid; gap: 10px; align-content: start; padding: 14px; position: relative; }
.d-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; font-size: 17px; }
.d-card { display: grid; gap: 9px; padding: 12px; border-radius: 12px; background: var(--c-surface-container); }
.d-row { display: flex; align-items: center; gap: 8px; }
.d-row b { font-size: 14px; }
.d-badge { font-style: normal; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 8px; }
.d-badge.blocked { background: var(--c-blocked); color: var(--c-on-blocked); }
.d-badge.unlocked { background: var(--c-unlocked); color: var(--c-on-unlocked); }
.d-switch { margin-left: auto; width: 36px; height: 20px; border-radius: 10px; background: var(--c-surface-container-highest); border: 2px solid var(--c-outline); position: relative; flex: none; }
.d-switch.on { background: var(--c-primary); border-color: var(--c-primary); }
.d-switch i { position: absolute; top: 2px; left: 2px; width: 12px; height: 12px; border-radius: 50%; background: var(--c-outline); }
.d-switch.on i { left: auto; right: 2px; background: var(--c-on-primary); }
.d-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.d-chips span { font-size: 11px; padding: 3px 9px; border-radius: 7px; border: 1px solid var(--c-outline-variant); color: var(--c-on-surface-variant); }
.d-pending { display: flex; align-items: center; gap: 8px; padding: 6px 4px 6px 10px; border-radius: 9px; background: var(--c-pending-container); color: var(--c-on-pending-container); font-size: 12px; }
.d-pending .ic { width: 16px; height: 16px; }
.d-pending > span:first-of-type { flex: 1; }
.d-pending .btn.text { color: var(--c-on-pending-container); text-decoration: underline; }
.d-field { display: grid; gap: 4px; font-size: 12px; }
.d-field label { color: var(--c-on-surface-variant); }
.d-input { width: 120px; padding: 6px 10px; border-radius: 6px; border: 1px solid var(--c-outline); background: var(--c-surface); }
.d-input.err { border: 2px solid var(--c-error); }
.app .err-text { display: flex; align-items: center; gap: 4px; color: var(--c-error); font-size: 11px; }
.err-text .ic { width: 14px; height: 14px; }
.d-toast { display: flex; align-items: center; gap: 8px; padding: 6px 6px 6px 12px; border-radius: 9px; background: var(--c-error-container); color: var(--c-on-error-container); font-size: 12px; }
.d-toast .ic { width: 16px; height: 16px; }
.d-toast > span:first-of-type { flex: 1; }
.d-toast .btn.text { color: var(--c-on-error-container); font-weight: 700; }
@media (max-width: 760px) {
  .pairing, .pairing.desks { grid-template-columns: minmax(0, 1fr); }
  .phone { width: min(250px, 100%); }
  .role-cols { grid-template-columns: minmax(0, 1fr); }
  .d-side { width: 112px; }
}
`;
