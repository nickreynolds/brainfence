import { poly } from '../../fx.mjs';
import { BAND, crownMark, crownTops, outline } from './guy.mjs';

const XS = Array.from({ length: 7 }, (_, i) => 336 + i * 58.67);
const TOPS = crownTops(7, 272, 188);
const RAILS = [[[314, 330], [710, 330], [710, 350], [314, 350]], [[314, 410], [710, 410], [710, 428], [314, 428]]];

const spear = (x, t) => [[x, t - 40], [x + 18, t], [x, t + 14], [x - 18, t]];

export default crownMark({
  id: 'spear-railing',
  name: 'Spear Railing',
  pitch: 'Victorian wrought-iron railings with spearhead finials and rings between the rails. Gothic and a little regal.',
  crown(ctx, p) {
    const bars = XS.map((x, i) => `<rect x="${x - 8}" y="${TOPS[i] + 6}" width="16" height="${514 - TOPS[i]}"/>`).join('');
    const rings = XS.slice(1).map((x, i) => `<circle cx="${(XS[i] + x) / 2}" cy="380" r="13"/>`).join('');
    return {
      splitDx: 7,
      silhouette: bars + XS.map((x, i) => poly(spear(x, TOPS[i]))).join('') + RAILS.map((r) => poly(r)).join('') + poly(BAND),
      art:
        `<g fill="${p.foil[2]}" ${outline(p, 5)}>${bars}</g>` +
        XS.map((x, i) => `<rect x="${x - 14}" y="${TOPS[i] + 24}" width="28" height="11" fill="${p.foil[0]}" ${outline(p, 4)}/>`).join('') +
        XS.map((x, i) => poly(spear(x, TOPS[i]), `fill="${p.accent}" ${outline(p, 5)}`)).join('') +
        RAILS.map((r) => poly(r, `fill="${p.foil[1]}" ${outline(p, 5)}`)).join('') +
        `<g fill="none" stroke="${p.foil[1]}" stroke-width="6">${rings}</g>` +
        poly(BAND, `fill="${p.foil[3]}" ${outline(p, 8)}`) +
        XS.filter((_, i) => i % 2 === 0).map((x) => `<circle cx="${x}" cy="501" r="7" fill="${p.foil[0]}"/>`).join(''),
    };
  },
});
