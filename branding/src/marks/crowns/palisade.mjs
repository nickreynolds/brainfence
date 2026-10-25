import { poly } from '../../fx.mjs';
import { BAND, crownMark, crownTops, outline } from './guy.mjs';

const W = 44;
const XS = Array.from({ length: 7 }, (_, i) => 316 + i * 58);
const TOPS = crownTops(7, 258, 180);
const RAIL = [[300, 330], [724, 330], [724, 352], [300, 352]];
const PLATE = [[462, 480], [562, 480], [562, 524], [462, 524]];

// Steel palisade pale with a triple-point top.
const pale = (x, t) => [
  [x, 520], [x, t + 30], [x + 3, t + 2], [x + 14, t + 24], [x + 22, t - 8],
  [x + 30, t + 24], [x + 41, t + 2], [x + W, t + 30], [x + W, 520],
];

export default crownMark({
  id: 'palisade-crown',
  name: 'Palisade Crown',
  pitch: 'Industrial security fencing with trident tops on every pale and a hazard plate on the band. The spikiest silhouette here.',
  crown(ctx, p) {
    const plateClip = ctx.uid('plate');
    ctx.def(`<clipPath id="${plateClip}">${poly(PLATE)}</clipPath>`);
    let stripes = '';
    for (let x = 440; x < 600; x += 28) stripes += poly([[x, 470], [x + 12, 470], [x - 12, 540], [x - 24, 540]], `fill="${p.edge}"`);

    const pales = XS.map((x, i) => {
      const t = TOPS[i];
      return (
        poly(pale(x, t), `fill="${p.foil[1]}" ${outline(p)}`) +
        poly([[x + 8, t + 40], [x + 22, t + 22], [x + 22, 516], [x + 8, 516]], `fill="${p.foil[0]}"`)
      );
    });
    return {
      silhouette: XS.map((x, i) => poly(pale(x, TOPS[i]))).join('') + poly(BAND),
      art:
        poly(RAIL, `fill="${p.foil[3]}" ${outline(p, 6)}`) +
        pales.join('') +
        poly(BAND, `fill="${p.foil[2]}" ${outline(p, 8)}`) +
        [336, 400, 624, 688].map((x) => `<circle cx="${x}" cy="501" r="7" fill="${p.edge}"/>`).join('') +
        poly(PLATE, `fill="${p.accent}"`) +
        `<g clip-path="url(#${plateClip})">${stripes}</g>` +
        poly(PLATE, `fill="none" ${outline(p, 6)}`),
    };
  },
});
