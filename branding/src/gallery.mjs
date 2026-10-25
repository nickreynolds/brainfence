import { readFileSync } from 'node:fs';

// The exploration gallery: one self-contained HTML page with every generated
// SVG embedded. Palette and lockup choices switch client-side.

const FONTS =
  'https://fonts.googleapis.com/css2?family=Chakra+Petch:ital,wght@0,600;0,700;1,700&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans+Condensed:wght@400;500&display=swap';

const CSS = `
/* Layout: one column of concept rows (poster left, platform previews right), then lockups, type and source. The page takes on the selected palette. */
:root {
  --bg: #0B0A10; --panel: #1E1A29; --fg: #F4F1FA; --accent: #D7FF1F; --hot: #FF2D7A; --cold: #18E0FF;
  --muted: color-mix(in srgb, var(--fg) 62%, var(--bg));
  --line: color-mix(in srgb, var(--fg) 16%, var(--bg));
  --os-dark: #202020; --os-light: #F3F3F3;
  --display: "Chakra Petch", "Bahnschrift", "Arial Narrow", sans-serif;
  --body: "IBM Plex Sans Condensed", "Arial Narrow", "Segoe UI", sans-serif;
  --mono: "IBM Plex Mono", ui-monospace, Consolas, monospace;
  color-scheme: dark;
}
body { background: var(--bg); color: var(--fg); font: 400 17px/1.5 var(--body); transition: background .25s, color .25s; }
.wrap { max-width: 1180px; margin: 0 auto; padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }
header.top .wrap { padding-block: 48px 32px; display: grid; gap: 20px; }
.eyebrow, figcaption, .label, button, pre { font-family: var(--mono); }
.eyebrow { margin: 0; font-size: 13px; letter-spacing: .12em; text-transform: uppercase; color: var(--muted); }
.wm { width: 100%; max-width: 980px; display: block; }
.lede { margin: 0; max-width: 62ch; font-size: 20px; }
.pals { display: flex; flex-wrap: wrap; gap: 10px; }
.pal { display: flex; align-items: center; gap: 10px; padding: 8px 14px 8px 8px; background: var(--panel); color: var(--fg); border: 2px solid transparent; font-size: 14px; letter-spacing: .06em; text-transform: uppercase; cursor: pointer; clip-path: polygon(0 0, 100% 0, 100% 70%, calc(100% - 10px) 100%, 0 100%); }
.pal[aria-checked="true"] { border-color: var(--accent); }
.pal:focus-visible, .use:focus-visible { outline: 3px solid var(--cold); outline-offset: 2px; }
.sw { display: flex; }
.sw i { width: 14px; height: 22px; display: block; }
.pal-note { margin: 0; color: var(--muted); font-size: 15px; }
.rounds { display: flex; flex-wrap: wrap; gap: 0; border-bottom: 2px solid var(--line); }
.round { padding: 10px 18px; background: transparent; color: var(--muted); border: 0; border-bottom: 4px solid transparent; margin-bottom: -2px; font-size: 14px; letter-spacing: .08em; text-transform: uppercase; cursor: pointer; }
.round b { display: block; font: italic 700 20px/1.1 var(--display); color: var(--fg); }
.round[aria-selected="true"] { border-bottom-color: var(--accent); color: var(--fg); }
.round:focus-visible { outline: 3px solid var(--cold); outline-offset: 2px; }
.round-note { margin: 0; max-width: 65ch; font-size: 19px; }
main.wrap { display: grid; gap: 72px; padding-block: 24px 96px; }
section { display: grid; gap: 24px; }
h2 { margin: 0; font: italic 700 clamp(30px, 5vw, 48px)/1 var(--display); text-transform: uppercase; letter-spacing: .02em; display: flex; align-items: center; gap: 16px; text-wrap: balance; }
h2::after { content: ""; flex: 1; height: 10px; background: repeating-linear-gradient(-60deg, var(--accent) 0 10px, transparent 10px 20px); }
h3 { margin: 0; font: 700 28px/1.1 var(--display); text-transform: uppercase; letter-spacing: .03em; }
.intro { margin: 0; max-width: 65ch; color: var(--muted); }
.marks { display: grid; gap: 40px; }
.mark { display: grid; grid-template-columns: minmax(0, 400px) minmax(0, 1fr); gap: 32px; align-items: start; padding-bottom: 40px; border-bottom: 1px solid var(--line); }
.mark:last-child { border-bottom: 0; padding-bottom: 0; }
.poster { width: 100%; aspect-ratio: 1; max-width: 100%; display: block; }
.info { display: grid; gap: 18px; min-width: 0; }
.info p { margin: 0; max-width: 60ch; }
.previews { display: flex; flex-wrap: wrap; gap: 22px 26px; align-items: flex-end; padding: 22px; background: var(--panel); }
figure { margin: 0; display: grid; gap: 8px; justify-items: center; }
figcaption { font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
.mac, .droid, .themed { width: 104px; height: 104px; overflow: hidden; }
.mac { border-radius: 22.37%; box-shadow: 0 6px 18px rgb(0 0 0 / .35); }
.droid, .themed { border-radius: 50%; }
.mac img, .droid img, .task img { width: 100%; height: 100%; display: block; }
.themed { background: color-mix(in srgb, var(--accent) 22%, var(--os-light)); display: grid; place-items: center; }
.glyph { display: block; background: currentColor; -webkit-mask: var(--m) center / contain no-repeat; mask: var(--m) center / contain no-repeat; }
.themed .glyph { width: 100%; height: 100%; color: #1B1B1F; }
.task { width: 32px; height: 32px; border-radius: 6px; overflow: hidden; }
.bar { display: flex; gap: 10px; padding: 8px 10px; align-items: center; }
.bar.dark { background: var(--os-dark); color: #FFFFFF; }
.bar.light { background: var(--os-light); color: #1B1B1F; }
.bar .glyph { width: 16px; height: 16px; }
.bar .glyph.lg { width: 24px; height: 24px; }
.trays { display: grid; gap: 4px; }
.use { justify-self: start; padding: 10px 16px; background: transparent; color: var(--fg); border: 2px solid var(--fg); font-size: 13px; letter-spacing: .08em; text-transform: uppercase; cursor: pointer; }
.use[aria-pressed="true"] { background: var(--accent); border-color: var(--accent); color: var(--bg); }
.wide { width: 100%; display: block; max-width: 100%; }
.frame { border: 1px solid var(--line); }
.label { margin: 0; font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
.pair { display: grid; gap: 10px; }
.cols { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.solo { padding: 24px; background: var(--panel); }
pre { margin: 0; padding: 20px; background: var(--panel); overflow-x: auto; font-size: 14px; line-height: 1.7; }
pre b { color: var(--accent); font-weight: 500; }
.glitch img, .glitch .wm { animation: glitch .32s steps(4) 1; }
@keyframes glitch {
  0% { transform: translate(-8px, 0); clip-path: inset(10% 0 55% 0); }
  25% { transform: translate(10px, 0); clip-path: inset(50% 0 20% 0); }
  50% { transform: translate(-4px, 0); clip-path: inset(0 0 70% 0); }
  75% { transform: translate(6px, 0); clip-path: inset(70% 0 0 0); }
  100% { transform: none; clip-path: inset(0); }
}
@media (prefers-reduced-motion: reduce) { .glitch img, .glitch .wm { animation: none; } body { transition: none; } }
@media (max-width: 760px) {
  .mark { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .cols { grid-template-columns: minmax(0, 1fr); }
  .lede { font-size: 18px; }
}
`;

const BODY = `
<header class="top"><div class="wrap">
  <p class="eyebrow">Brand exploration · Round 1 · October 2026</p>
  <img class="wm" data-type="wordmark" alt="BRAINFENCE wordmark">
  <p class="lede">Brainfence protects your head from a loud, fast world. Every asset on this page comes out of one generator, so a new palette or concept shows up everywhere at once.</p>
  <div class="rounds" id="rounds" role="tablist" aria-label="Exploration round"></div>
  <div class="pals" id="pals" role="radiogroup" aria-label="Palette"></div>
  <p class="pal-note" id="pal-note"></p>
</div></header>
<main class="wrap">
  <section>
    <h2>Icon concepts</h2>
    <p class="round-note" id="round-note"></p>
    <p class="intro">Each concept comes in three variants. The poster adds halftone and scanlines, the icon is a flat tile for app stores and docks, and mono is a single-color template for Android themed icons, the macOS menu bar and the Windows tray.</p>
    <div class="marks" id="marks"></div>
  </section>
  <section>
    <h2>Lockups</h2>
    <p class="intro">Showing <strong id="hero-name"></strong>. Click Use in lockups on any concept to swap it in. The banner is 1500×500, the X header size.</p>
    <div class="pair"><p class="label">Banner</p><img class="wide frame" data-hero="banner" alt="Banner with mark, wordmark and tagline"></div>
    <div class="pair"><p class="label">Horizontal lockup</p><img class="wide frame" data-hero="lockup" alt="Mark beside wordmark"></div>
    <div class="cols">
      <div class="pair"><p class="label">Wordmark, split</p><div class="solo"><img class="wide" data-type="wordmark" alt="Two-tone wordmark with RGB split"></div></div>
      <div class="pair"><p class="label">Wordmark, plain</p><div class="solo"><img class="wide" data-type="plain" alt="Single-color wordmark"></div></div>
    </div>
  </section>
  <section>
    <h2>Fence Stencil</h2>
    <p class="intro">The house type is a full A–Z on a 4×6 grid, with chamfered corners and a 12° forward slant. Taglines, headers and merch can all be set in it straight from code.</p>
    <div class="solo"><img class="wide" data-type="specimen" alt="Fence Stencil alphabet specimen"></div>
  </section>
  <section>
    <h2>Source</h2>
    <pre><b>npm run brand</b>                  rebuild everything into branding/out/
branding/src/palettes.mjs      add a palette: every asset gains a version in it
branding/src/rounds.mjs        which concepts and palettes each round shows
branding/src/marks/*.mjs       round 1 concepts (kept as reviewed)
branding/src/marks/crowns/     round 2: guy.mjs plus one file per fence crown
branding/src/type.mjs          Fence Stencil glyphs and text setting
branding/src/fx.mjs            RGB split, glitch slices, streaks, halftone, fence lattice
branding/src/compose.mjs       icon, poster and mono variants, lockups, banner
branding/out/marks/&lt;id&gt;/       &lt;id&gt;-&lt;palette&gt;-icon.svg, -poster.svg, &lt;id&gt;-mono.svg</pre>
  </section>
</main>
`;

// Runs in the browser; kept as a plain file so it reads and lints as JS.
const SCRIPT = readFileSync(new URL('./gallery.client.js', import.meta.url), 'utf8');

export function gallery(data, { fragment }) {
  const json = JSON.stringify(data).replace(/</g, '\\u003c');
  const head =
    `<title>Brainfence Brand Lab</title>\n` +
    `<meta name="claude:viewport-fit-sides" content="cover">\n` +
    `<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>\n` +
    `<link rel="stylesheet" href="${FONTS}">\n<style>${CSS}</style>\n`;
  const body = `${BODY}<script type="application/json" id="brand-data">${json}</script>\n<script>${SCRIPT}</script>\n`;
  if (fragment) return head + body;
  return (
    `<!doctype html>\n<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n` +
    `<style>body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>\n${head}</head><body>${body}</body></html>\n`
  );
}
