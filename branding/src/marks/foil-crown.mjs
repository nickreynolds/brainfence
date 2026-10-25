import { poly, rng, streak, bolt, star } from '../fx.mjs';

// Angular profile, facing right, cut off at the neck.
const HEAD = [
  [392, 880], [388, 760], [330, 700], [300, 600], [306, 500], [700, 470], [722, 540],
  [708, 560], [770, 650], [724, 668], [738, 700], [718, 716], [730, 742], [704, 760],
  [708, 800], [640, 820], [600, 880],
];

// Crown points (peaks and valleys), left to right, sitting on a sloped base.
const TOP = [[282, 250], [372, 384], [436, 176], [512, 356], [580, 186], [634, 368], [716, 256]];
const baseY = (x) => 470 - ((x - 282) * 32) / 434;

const BAND = [[276, 474], [722, 440], [726, 498], [280, 532]];
const VISOR = [[612, 546], [728, 536], [732, 560], [608, 572]];
const VISOR_LIGHT = [[630, 551], [716, 544], [717, 552], [630, 560]];

export default {
  id: 'foil-crown',
  name: 'Foil Crown',
  pitch:
    'A head in profile at full sprint, wearing a crumpled tinfoil crown. Signal hits the points and scatters. Has the most character of the five, so it suits the app icon and stickers.',
  build(ctx, p, { poster }) {
    // Crumpled foil: a three-row mesh (peak, jittered middle, base) between
    // each pair of crown points, each triangle a random foil tone. Tones get
    // darker toward the back of the hat.
    const rand = rng(7);
    const B = TOP.map(([x]) => [x, baseY(x)]);
    const M = TOP.map(([x, y], i) => {
      const edge = i === 0 || i === TOP.length - 1;
      return [edge ? x : x + (rand() - 0.5) * 44, y + (baseY(x) - y) * (0.42 + rand() * 0.22)];
    });
    const tris = [];
    for (let i = 0; i < TOP.length - 1; i++) {
      tris.push(
        [TOP[i], TOP[i + 1], M[i]],
        [TOP[i + 1], M[i + 1], M[i]],
        [M[i], M[i + 1], B[i]],
        [M[i + 1], B[i + 1], B[i]],
      );
    }
    const facets = tris
      .map((t, k) => {
        const tone = p.foil[Math.min(3, Math.floor(rand() * 2.2 + (1 - k / tris.length) * 1.4))];
        return poly(t, `fill="${tone}" stroke="${p.edge}" stroke-width="4" stroke-linejoin="round"`);
      })
      .join('');
    const crown = [...TOP, ...[...B].reverse()];

    // Hazard stripes on the hat band, clipped to the band.
    const bandClip = ctx.uid('band');
    ctx.def(`<clipPath id="${bandClip}">${poly(BAND)}</clipPath>`);
    let stripes = '';
    for (let x = 250; x < 800; x += 76) {
      stripes += poly([[x, 420], [x + 36, 420], [x - 24, 560], [x - 60, 560]], `fill="${p.accent}"`);
    }

    const back = [
      streak(150, 380, 560, 26, p.split[0]),
      streak(96, 360, 612, 12, p.split[1]),
      streak(170, 380, 690, 36, p.accent),
      streak(120, 370, 760, 12, p.ink),
      streak(210, 400, 814, 22, p.split[1]),
    ].join('');

    const silhouette = poly(HEAD) + poly(crown) + poly(BAND);

    let art =
      poly(HEAD, `fill="${p.ink}"`) +
      facets +
      poly(BAND, `fill="${p.bg}"`) +
      `<g clip-path="url(#${bandClip})">${stripes}</g>` +
      poly(VISOR, `fill="${p.bg}"`) +
      poly(VISOR_LIGHT, `fill="${p.accent}"`);
    if (poster) {
      // Neural jack behind the ear.
      art +=
        `<rect x="414" y="628" width="52" height="34" fill="${p.bg}"/>` +
        `<rect x="428" y="640" width="24" height="10" fill="${p.accent}"/>`;
    }

    // Incoming signal strikes the right-hand point and bounces off.
    const front =
      bolt([[900, 70], [812, 150], [846, 168], [742, 244]], p.spark, 18) +
      star(716, 256, 54, 14, 4, p.spark, Math.PI / 8) +
      bolt([[770, 280], [858, 310], [842, 330], [930, 360]], p.spark, 10) +
      star(282, 250, 34, 9, 4, p.spark) +
      (poster ? bolt([[120, 90], [200, 160], [176, 176], [256, 228]], p.spark, 10) : '');

    return { back, silhouette, art, front };
  },
};
