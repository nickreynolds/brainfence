// Builds the 30-second teaser into one self-contained HTML page.
//
//   node branding/teaser/build.mjs                     write branding/out/teaser/index.html
//   node branding/teaser/build.mjs --fragment <file>   also write a bare fragment (for hosts
//                                                      that add their own <head>)
//
// The guy and his crown come from the brand kit (round 1 Chainlink Crown, Static),
// so the teaser always matches the approved mark.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { palettes } from '../src/palettes.mjs';
import { createCtx } from '../src/fx.mjs';
import { GLYPHS } from '../src/type.mjs';
import { crownMark } from '../src/marks/crowns/guy.mjs';
import { chainlink } from '../src/marks/crowns/chainlink.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const p = palettes.find((x) => x.id === 'static');

const ctx = createCtx('tz');
const guy = crownMark({ id: 'guy', name: '', pitch: '', crown: () => ({ silhouette: '', art: '' }) }).build(ctx, p, {});
const crown = chainlink()(ctx, p);
const data = {
  defs: ctx.defs.join(''),
  guy: { sil: guy.silhouette, art: guy.art },
  crown: { sil: crown.silhouette, art: crown.art },
  glyphs: GLYPHS,
};

const read = (f) => readFileSync(join(here, f), 'utf8');
const json = JSON.stringify(data).replace(/</g, '\\u003c');

const FONTS =
  'https://fonts.googleapis.com/css2?family=Chakra+Petch:wght@500;600;700&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans+Condensed:wght@400;500&display=swap';

const CSS = `
/* One dark stage, deliberately single-theme: the film decides its own colors. */
:root { --bg: #07060B; --panel: #14121B; --fg: #F4F1FA; --muted: #9A94A8; --lime: #D7FF1F; --hot: #FF2D7A; color-scheme: dark; }
body { background: var(--bg); color: var(--fg); font: 400 15px/1.5 "IBM Plex Mono", Consolas, monospace; }
.wrap { max-width: 1280px; margin: 0 auto; padding-block: 24px 40px; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); display: grid; gap: 14px; }
.eyebrow { margin: 0; font-size: 12px; letter-spacing: .14em; text-transform: uppercase; color: var(--muted); }
.player { display: grid; gap: 10px; }
.stage { position: relative; width: 100%; max-width: 100%; aspect-ratio: 16 / 9; background: #000; overflow: hidden; }
.stage svg { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.big { position: absolute; left: 50%; bottom: 6%; transform: translateX(-50%); display: grid; justify-items: center; gap: 6px; padding: 22px 34px; background: rgb(7 6 11 / .82); color: var(--fg); border: 2px solid var(--lime); font: 700 22px/1.1 "Chakra Petch", Bahnschrift, sans-serif; letter-spacing: .06em; text-transform: uppercase; cursor: pointer; clip-path: polygon(0 0, 100% 0, 100% 75%, calc(100% - 16px) 100%, 0 100%); }
.big small { font: 400 12px/1 "IBM Plex Mono", Consolas, monospace; letter-spacing: .12em; color: var(--muted); }
.big .tri { width: 0; height: 0; border-left: 26px solid var(--lime); border-top: 16px solid transparent; border-bottom: 16px solid transparent; }
.big:disabled { cursor: progress; border-color: var(--muted); }
.big:disabled .tri { border-left-color: var(--muted); }
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.bar button { padding: 8px 16px; background: var(--panel); color: var(--fg); border: 1px solid #2E2A3A; font: 500 13px/1 "IBM Plex Mono", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; cursor: pointer; min-width: 84px; }
.bar button:hover { border-color: var(--lime); }
button:focus-visible, input:focus-visible { outline: 3px solid var(--lime); outline-offset: 2px; }
#scrub { flex: 1; min-width: 160px; accent-color: var(--lime); }
.time { font-variant-numeric: tabular-nums; color: var(--muted); }
.note { margin: 0; color: var(--muted); max-width: 70ch; }
@media (max-width: 560px) { .big { padding: 14px 20px; font-size: 16px; } }
`;

const BODY = `
<div class="wrap">
  <p class="eyebrow">Brainfence · Teaser · 30s · Pure SVG + React, synthesized sound</p>
  <div id="teaser"></div>
  <p class="note">Space plays and pauses. Drag the bar to scrub. The soundtrack is generated in your browser the first time you press play.</p>
</div>
`;

function page({ fragment }) {
  const head =
    `<title>Brainfence Teaser</title>\n<meta name="claude:viewport-fit-sides" content="cover">\n` +
    `<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n` +
    `<link rel="stylesheet" href="${FONTS}">\n<style>${CSS}</style>\n`;
  const scripts =
    `<script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.production.min.js"></script>\n` +
    `<script src="https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.production.min.js"></script>\n` +
    `<script src="https://unpkg.com/htm@3.1.1/dist/htm.umd.js"></script>\n` +
    `<script>window.BF_DATA = ${json};</script>\n` +
    `<script>${read('timeline.js')}</script>\n<script>${read('music.js')}</script>\n<script>${read('app.js')}</script>\n`;
  const body = BODY + scripts;
  if (fragment) return head + body;
  return (
    `<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n` +
    `<style>body{margin:0}[hidden]{display:none!important}</style>\n${head}</head><body>${body}</body></html>\n`
  );
}

const out = join(here, '..', 'out', 'teaser', 'index.html');
mkdirSync(dirname(out), { recursive: true });
writeFileSync(out, page({ fragment: false }));
const i = process.argv.indexOf('--fragment');
if (i > 0) writeFileSync(process.argv[i + 1], page({ fragment: true }));
console.log(`teaser: wrote ${out}`);
