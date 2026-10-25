import { bolt, star } from '../fx.mjs';

// Dome geometry: half-ellipses on a base ring at y=680, apex at y=240.
const CX = 512;
const BASE = 680;
const RX = 300;
const RY = 440;

const BRAIN = [
  [392, 560, 100], [456, 470, 110], [568, 470, 110], [632, 560, 100],
  [440, 620, 90], [584, 620, 90], [512, 560, 130],
];
const GYRI = [
  'M512 380C496 450 528 520 508 600S516 660 512 700',
  'M380 540c20-40 60-40 70 0', 'M420 640c10-30 50-40 60-10', 'M440 460c20-10 40 0 40 30',
  'M644 540c-20-40-60-40-70 0', 'M604 640c-10-30-50-40-60-10', 'M584 460c-20-10-40 0-40 30',
];

const meridian = (k) => `M${CX - RX * k} ${BASE}A${RX * k} ${RY} 0 0 1 ${CX + RX * k} ${BASE}`;
const latitude = (s) => {
  const y = BASE - RY * s;
  const hw = RX * Math.sqrt(1 - s * s);
  return `M${CX - hw} ${y}A${hw} ${hw * 0.15} 0 0 0 ${CX + hw} ${y}`;
};
const CAGE = [meridian(1), meridian(0.6), meridian(0.25), `M${CX} ${BASE - RY}V${BASE}`, latitude(0.5), latitude(0.82)].join('');
const RING_BACK = `M182 ${BASE}A330 64 0 0 1 842 ${BASE}`;
const RING_FRONT = `M182 ${BASE}A330 64 0 0 0 842 ${BASE}`;
const GROUND = `M512 ${BASE + 64}V800M452 800H572M472 830H552M492 860H532`;

export default {
  id: 'faraday-dome',
  name: 'Faraday Dome',
  pitch:
    'The brain under a grounded wire cage. Lightning hits the bars and never gets in. The quickest of the five to read as protection.',
  build(ctx, p, { poster }) {
    const brain = BRAIN.map(([x, y, r]) => `<circle cx="${x}" cy="${y}" r="${r}"/>`).join('');
    const wire = (d, color, w) =>
      `<path d="${d}" fill="none" stroke="${color}" stroke-width="${w}" stroke-linecap="round"/>`;

    // Noise in the background: broken signal rings.
    const back = [360, 440]
      .map((r) => `<circle cx="512" cy="520" r="${r}" fill="none" stroke="${p.bg2}" stroke-width="16" stroke-dasharray="54 30"/>`)
      .join('');

    const silhouette =
      brain +
      `<path d="${CAGE}${RING_FRONT}${RING_BACK}" fill="none" stroke-width="30" stroke-linecap="round"/>`;

    const art =
      wire(RING_BACK, p.foil[3], 30) +
      `<g fill="${p.brain}">${brain}</g>` +
      `<path d="${GYRI.join('')}" fill="none" stroke="${p.bg}" stroke-width="16" stroke-linecap="round"/>` +
      wire(CAGE, p.edge, 38) +
      wire(CAGE, p.foil[1], 22) +
      wire(RING_FRONT, p.edge, 44) +
      wire(RING_FRONT, p.foil[0], 30) +
      wire(GROUND, p.foil[1], 18);

    const front =
      bolt([[80, 140], [190, 250], [156, 272], [262, 424]], p.spark, 18) +
      bolt([[948, 120], [840, 240], [874, 260], [760, 424]], p.spark, 18) +
      star(270, 434, 56, 16, 4, p.spark) +
      star(754, 432, 56, 16, 4, p.spark, Math.PI / 4) +
      (poster ? star(512, 240, 34, 10, 4, p.spark) : '');

    return { back, silhouette, art, front };
  },
};
