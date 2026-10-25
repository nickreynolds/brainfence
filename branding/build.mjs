// Builds the brand kit: every mark × palette × variant as SVG, plus wordmarks,
// lockups, banners and the gallery page. Run with `npm run brand`.
//
//   node branding/build.mjs                       write branding/out/
//   node branding/build.mjs --fragment <file>     also write the gallery as a
//                                                 bare HTML fragment (for hosts
//                                                 that add their own <head>)

import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { palettes } from './src/palettes.mjs';
import { VARIANTS, renderMark, renderWordmark, renderLockup, renderBanner, renderSpecimen, TAGLINE } from './src/compose.mjs';
import { gallery } from './src/gallery.mjs';
import { rounds } from './src/rounds.mjs';

// Each mark is rendered in every palette of every round that shows it.
const marks = new Map();
for (const r of rounds) {
  for (const m of r.marks) {
    if (!marks.has(m.id)) marks.set(m.id, { mark: m, palettes: new Set() });
    for (const id of r.palettes) marks.get(m.id).palettes.add(id);
  }
}

const root = dirname(fileURLToPath(import.meta.url));
const out = join(root, 'out');
rmSync(out, { recursive: true, force: true });

let count = 0;
const write = (rel, text) => {
  const file = join(out, rel);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, text);
  count++;
  return text;
};

const data = {
  tagline: TAGLINE,
  palettes,
  rounds: rounds.map((r) => ({ ...r, marks: r.marks.map((m) => m.id) })),
  marks: [],
  type: {},
};

for (const { mark, palettes: used } of marks.values()) {
  const entry = { id: mark.id, name: mark.name, pitch: mark.pitch, svg: {} };
  entry.mono = write(`marks/${mark.id}/${mark.id}-mono.svg`, renderMark(mark, palettes[0], 'mono'));
  for (const p of palettes.filter((p) => used.has(p.id))) {
    const s = (entry.svg[p.id] = {});
    for (const v of VARIANTS.filter((v) => v !== 'mono')) {
      s[v] = write(`marks/${mark.id}/${mark.id}-${p.id}-${v}.svg`, renderMark(mark, p, v));
    }
    s.lockup = write(`lockups/${mark.id}/lockup-${p.id}.svg`, renderLockup(mark, p));
    s.banner = write(`lockups/${mark.id}/banner-${p.id}.svg`, renderBanner(mark, p));
  }
  data.marks.push(entry);
}

for (const p of palettes) {
  data.type[p.id] = {
    wordmark: write(`type/wordmark-${p.id}.svg`, renderWordmark(p)),
    plain: write(`type/wordmark-${p.id}-plain.svg`, renderWordmark(p, { two: false, ghost: false })),
    specimen: write(`type/specimen-${p.id}.svg`, renderSpecimen(p)),
  };
}

write('index.html', gallery(data, { fragment: false }));

const i = process.argv.indexOf('--fragment');
if (i > 0) writeFileSync(process.argv[i + 1], gallery(data, { fragment: true }));

console.log(`brand: wrote ${count} files to ${out}`);
