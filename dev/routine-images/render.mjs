// Renders the hangboard step images: a schematic Beastmaker 2000 (our own
// drawing, laid out from the board's hold positions) with the holds a step
// uses highlighted. Hold numbers and names follow the official diagram in the
// Beastmaker × Beacon training manual (Ned Feehally):
// https://www.beaconclimbing.com/wp-content/uploads/Beastmaker-Beacon-Training-Manual.pdf
//   1 45° sloper · 2 35° sloper · 3 20° sloper · 4 deep 2-finger pocket (top centre)
//   5 large flat edge · 6 deep mono · 7 deep back 2-finger pocket · 8 deep 2-finger pocket
//   9 large flat edge (centre) · 10 small edge · 11 shallow mono · 12 shallow 2-finger pocket
//   13 sloping 2-finger pocket · 14 incut edge (centre)
// Output: dev/routine-images/png/<name>.png, uploaded by dev/update-climbing-task.ts.
// Run: node dev/routine-images/render.mjs
//
// Coordinates are in a 2000 × 640 frame matching a front-on view of the
// board (580 mm wide, two rows of holds under the top slopers).

import { Resvg } from '@resvg/resvg-js';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, 'png');

const INK = '#1B1B21';
const LIME = '#D7FF1F';
const WOOD = '#E8CDA2';
const WOOD_TOP = '#F1DDBB';
const HOLE = '#B4875A';

/** Holds by id: slots are rounded rects, monos are circles. */
const slot = (x1, x2, y1, y2) => ({ kind: 'slot', x1, x2, y1, y2 });
const mono = (cx, cy, r) => ({ kind: 'mono', cx, cy, r });
const HOLDS = {
  // Top row (deeper holds)
  'top-edge-L': slot(105, 355, 272, 345),
  'top-mono-L': mono(425, 308, 35),
  'top-offset-L': slot(495, 635, 272, 345),
  'top-2f-L': slot(680, 820, 272, 345),
  'top-center': slot(865, 1135, 272, 345),
  'top-2f-R': slot(1180, 1320, 272, 345),
  'top-offset-R': slot(1365, 1505, 272, 345),
  'top-mono-R': mono(1578, 308, 35),
  'top-edge-R': slot(1645, 1895, 272, 345),
  // Bottom row (shallower holds)
  'bottom-edge-L': slot(110, 360, 420, 492),
  'bottom-mono-L': mono(428, 456, 33),
  'bottom-2f-L': slot(495, 635, 420, 492),
  'bottom-slope2f-L': slot(668, 812, 420, 492),
  'bottom-center': slot(840, 1150, 420, 492),
  'bottom-slope2f-R': slot(1188, 1332, 420, 492),
  'bottom-2f-R': slot(1365, 1505, 420, 492),
  'bottom-mono-R': mono(1572, 456, 33),
  'bottom-edge-R': slot(1640, 1890, 420, 492),
  // Top jug pockets
  'jug-L': slot(775, 950, 150, 215),
  'jug-R': slot(1035, 1210, 150, 215),
};
/** Top slopers: outer shelves, raised blocks, centre. */
const SLOPERS = {
  'sloper-outer-L': { x1: 85, x2: 390 },
  'sloper-mid-L': { x1: 390, x2: 720 },
  'sloper-center': { x1: 720, x2: 1290 },
  'sloper-mid-R': { x1: 1290, x2: 1600 },
  'sloper-outer-R': { x1: 1600, x2: 1915 },
};

const IMAGES = [
  { name: 'bm2000-front2-hold8', holds: ['top-2f-L', 'top-2f-R'], caption: 'Hold 8 · deep 2-finger pockets · front 2 (index + middle)' },
  { name: 'bm2000-open4-hold10', holds: ['bottom-edge-L', 'bottom-edge-R'], caption: 'Hold 10 · small edges · 4 fingers, open hand' },
  { name: 'bm2000-middle2-hold8', holds: ['top-2f-L', 'top-2f-R'], caption: 'Hold 8 · deep 2-finger pockets · middle 2 (middle + ring)' },
  { name: 'bm2000-sloper-hold1', holds: ['sloper-outer-L', 'sloper-outer-R'], caption: 'Hold 1 · 45° slopers · 4 fingers, no thumbs' },
  { name: 'bm2000-open3-hold5', holds: ['top-edge-L', 'top-edge-R'], caption: 'Hold 5 · large flat edges · 3 fingers, open hand' },
  { name: 'bm2000-crimp-hold10', holds: ['bottom-edge-L', 'bottom-edge-R'], caption: 'Hold 10 · small edges · 4 fingers, half crimp' },
];

function holdShape(h, fill, stroke = 'none', strokeWidth = 0) {
  if (h.kind === 'mono') return `<circle cx="${h.cx}" cy="${h.cy}" r="${h.r}" fill="${fill}" stroke="${stroke}" stroke-width="${strokeWidth}"/>`;
  const r = (h.y2 - h.y1) / 2;
  return `<rect x="${h.x1}" y="${h.y1}" width="${h.x2 - h.x1}" height="${h.y2 - h.y1}" rx="${r}" fill="${fill}" stroke="${stroke}" stroke-width="${strokeWidth}"/>`;
}

function centerOf(id) {
  const h = HOLDS[id];
  if (h) return h.kind === 'mono' ? { x: h.cx, y: h.cy, top: h.cy - h.r } : { x: (h.x1 + h.x2) / 2, y: (h.y1 + h.y2) / 2, top: h.y1 };
  const s = SLOPERS[id];
  return { x: (s.x1 + s.x2) / 2, y: 160, top: 70 };
}

function marker(id) {
  const c = centerOf(id);
  const label = id.endsWith('-L') ? 'L' : id.endsWith('-R') ? 'R' : '';
  if (!label) return '';
  // Markers sit above the hold: in the gap over each row, or above the board for slopers.
  const y = SLOPERS[id] ? 40 : c.top - 34;
  return `<g><circle cx="${c.x}" cy="${y}" r="30" fill="${INK}"/>` +
    `<text x="${c.x}" y="${y + 12}" font-family="Helvetica, Arial, sans-serif" font-size="36" font-weight="700" fill="${LIME}" text-anchor="middle">${label}</text></g>`;
}

function render({ holds, caption }) {
  const on = new Set(holds);
  const slopers = Object.entries(SLOPERS).map(([id, s]) => {
    const active = on.has(id);
    return `<rect x="${s.x1 + 4}" y="74" width="${s.x2 - s.x1 - 8}" height="${id.includes('mid') || id.includes('center') ? 168 : 176}" rx="18"
      fill="${active ? LIME : WOOD_TOP}" stroke="${active ? INK : '#D9BC8E'}" stroke-width="${active ? 8 : 3}"/>`;
  }).join('\n');
  const holdEls = Object.entries(HOLDS).map(([id, h]) =>
    on.has(id) ? holdShape(h, LIME, INK, 8) : holdShape(h, HOLE)).join('\n');
  const markers = holds.map(marker).join('\n');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="2000" height="760" viewBox="0 0 2000 760">
  <rect width="2000" height="760" rx="40" fill="#F7F4EE"/>
  <rect x="85" y="70" width="1830" height="480" rx="44" fill="${WOOD}" stroke="#C9A877" stroke-width="4"/>
  <line x1="95" y1="390" x2="1905" y2="390" stroke="#D2B285" stroke-width="4"/>
  ${slopers}
  ${holdEls}
  ${markers}
  <text x="1000" y="640" font-family="Helvetica, Arial, sans-serif" font-size="46" font-weight="600" fill="${INK}" text-anchor="middle">${caption}</text>
  <text x="1000" y="700" font-family="Helvetica, Arial, sans-serif" font-size="32" fill="#5B5862" text-anchor="middle">Beastmaker 2000 (schematic) · L = left hand, R = right hand</text>
</svg>`;
}

mkdirSync(out, { recursive: true });
for (const image of IMAGES) {
  const svg = render(image);
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: 1200 }, font: { loadSystemFonts: true } }).render().asPng();
  writeFileSync(join(out, `${image.name}.png`), png);
  console.log(`wrote png/${image.name}.png`);
}
