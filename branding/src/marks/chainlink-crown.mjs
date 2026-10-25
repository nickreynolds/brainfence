import { poly, lattice } from '../fx.mjs';

const CROWN = [
  [322, 520], [322, 230], [368, 390], [417, 300], [464, 390], [512, 180],
  [560, 390], [607, 300], [656, 390], [702, 230], [702, 520],
];
const PEAKS = [[322, 230], [417, 300], [512, 180], [607, 300], [702, 230]];
const BAND = [[310, 470], [714, 470], [714, 532], [310, 532]];
const NECK = [[462, 830], [562, 830], [584, 910], [440, 910]];
const VISOR = [[270, 614], [754, 600], [754, 670], [270, 684]];
const EYES = [
  [[372, 632], [474, 629], [474, 649], [372, 652]],
  [[550, 627], [652, 624], [652, 644], [550, 647]],
];

export default {
  id: 'chainlink-crown',
  name: 'Chainlink Crown',
  pitch:
    'A visored head wearing a crown of chain-link fence and barbed wire. The most literal reading of the name.',
  build(ctx, p) {
    const head = `<circle cx="512" cy="660" r="210"/>` + poly(NECK);

    const back = [380, 450]
      .map((r) => `<circle cx="512" cy="580" r="${r}" fill="none" stroke="${p.bg2}" stroke-width="18" stroke-dasharray="40 26"/>`)
      .join('');

    // Two-tone foil, lit from the left.
    const leftHalf = ctx.uid('lh');
    ctx.def(`<clipPath id="${leftHalf}"><rect x="0" y="0" width="512" height="1024"/></clipPath>`);

    let barbs = '';
    for (let x = 340; x <= 690; x += 70) barbs += `M${x - 13} 487L${x + 13} 515M${x + 13} 487L${x - 13} 515`;

    const silhouette = head + poly(CROWN) + poly(BAND) + poly(VISOR);

    const art =
      `<g fill="${p.ink}">${head}</g>` +
      poly(VISOR, `fill="${p.bg}"`) +
      EYES.map((e) => poly(e, `fill="${p.accent}"`)).join('') +
      poly(CROWN, `fill="${p.foil[1]}"`) +
      `<g clip-path="url(#${leftHalf})">${poly(CROWN, `fill="${p.foil[0]}"`)}</g>` +
      lattice(ctx, { x: 300, y: 160, w: 424, h: 380 }, 50, p.edge, 9, poly(CROWN)) +
      poly(CROWN, `fill="none" stroke="${p.edge}" stroke-width="8" stroke-linejoin="miter"`) +
      poly(BAND, `fill="${p.foil[2]}" stroke="${p.edge}" stroke-width="8"`) +
      `<path d="M296 501H728${barbs}" fill="none" stroke="${p.edge}" stroke-width="8"/>` +
      PEAKS.map(([x, y]) => `<circle cx="${x}" cy="${y}" r="20" fill="${p.accent}" stroke="${p.edge}" stroke-width="7"/>`).join('');

    return { back, silhouette, art, front: '' };
  },
};
