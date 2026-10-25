import { poly } from '../../fx.mjs';
import { BAND, crownMark, crownTops, outline } from './guy.mjs';

const W = 58;
const XS = Array.from({ length: 5 }, (_, i) => 316 + i * 83.5);
const TOPS = crownTops(5, 290, 186);
const RAIL = [[300, 392], [724, 392], [724, 420], [300, 420]];

function slat(x, t, i) {
  if (i === 3) {
    // Snapped off.
    return [[x, 520], [x, t + 60], [x + 14, t + 34], [x + 26, t + 70], [x + 40, t + 40], [x + W, t + 64], [x + W, 520]];
  }
  return [[x, 520], [x, t + 30], [x + W / 2, t], [x + W, t + 30], [x + W, 520]];
}

export default crownMark({
  id: 'picket-crown',
  name: 'Picket Crown',
  pitch: 'A white picket fence gone feral: one slat leaning, one snapped, a spray tag on the rail. Suburban order, vandalised.',
  crown(ctx, p) {
    const slats = XS.map((x, i) => {
      const lean = i === 1 ? ` transform="rotate(-8 ${x + W / 2} 470)"` : '';
      return `<g${lean}>${poly(slat(x, TOPS[i], i), `fill="${p.foil[i % 2]}" ${outline(p)}`)}<circle cx="${x + W / 2}" cy="406" r="5" fill="${p.edge}"/></g>`;
    });
    const tag =
      `<path d="M612 482l40 36M652 482l-40 36M646 522v22M620 520v12" fill="none" stroke="${p.accent}" stroke-width="9" stroke-linecap="round"/>`;
    return {
      silhouette: XS.map((x, i) => poly(slat(x, TOPS[i], i))).join('') + poly(RAIL) + poly(BAND),
      art:
        poly(RAIL, `fill="${p.foil[2]}" ${outline(p, 6)}`) +
        slats.join('') +
        poly(BAND, `fill="${p.foil[2]}" ${outline(p, 8)}`) +
        tag,
    };
  },
});
