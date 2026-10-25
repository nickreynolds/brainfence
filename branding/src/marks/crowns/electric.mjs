import { poly, bolt, star } from '../../fx.mjs';
import { BAND, crownMark, outline } from './guy.mjs';

const XS = [330, 421, 512, 603, 694];
const TOPS = [262, 232, 196, 232, 262];
const WIRES = [300, 360, 420];
const SIGN = [[512, 458], [558, 538], [466, 538]];
const ZAP = [[518, 478], [496, 510], [511, 510], [502, 530], [528, 496], [513, 496]];

const wire = (y) => XS.slice(1).reduce((d, x, i) => `${d}Q${(XS[i] + x) / 2} ${y + 16} ${x} ${y}`, `M${XS[0]} ${y}`);

export default crownMark({
  id: 'electric-crown',
  name: 'Electric Crown',
  pitch: 'Electric fence posts, insulators and live wire, arcing as it runs. A warning triangle sits on the band.',
  crown(ctx, p) {
    const posts = XS.map((x, i) => `<rect x="${x - 8}" y="${TOPS[i]}" width="16" height="${520 - TOPS[i]}"/>`).join('');
    const wires = WIRES.map(wire).join('');
    const insulators = XS.flatMap((x) =>
      WIRES.map((y) => `<rect x="${x - 12}" y="${y - 10}" width="24" height="20" rx="5" fill="${p.accent}" stroke="${p.edge}" stroke-width="4"/>`),
    ).join('');
    return {
      splitDx: 9,
      silhouette: posts + `<path d="${wires}" fill="none" stroke-width="12"/>` + poly(BAND),
      art:
        `<g fill="${p.foil[2]}" ${outline(p, 5)}>${posts}</g>` +
        XS.map((x, i) => `<circle cx="${x}" cy="${TOPS[i]}" r="12" fill="${p.foil[0]}" ${outline(p, 5)}/>`).join('') +
        `<path d="${wires}" fill="none" stroke="${p.edge}" stroke-width="12"/>` +
        `<path d="${wires}" fill="none" stroke="${p.foil[1]}" stroke-width="6"/>` +
        insulators +
        poly(BAND, `fill="${p.foil[3]}" ${outline(p, 8)}`) +
        poly(SIGN, `fill="${p.accent}" ${outline(p, 6)}`) +
        poly(ZAP, `fill="${p.edge}"`),
      front:
        bolt([[436, 304], [452, 330], [468, 306], [488, 334]], p.spark, 7) +
        bolt([[618, 366], [636, 392], [652, 368], [672, 396]], p.spark, 7) +
        star(512, 196, 46, 12, 4, p.spark),
    };
  },
});
