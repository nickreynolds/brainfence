import { poly, streak } from '../fx.mjs';

const CONE_L = [[512, 150], [300, 470], [476, 470]];
const CONE_M = [[512, 150], [430, 330], [476, 470]];
const CONE_R = [[512, 150], [476, 470], [724, 470]];
const BRIM = [[252, 452], [772, 452], [772, 502], [252, 502]];
const VISOR = [[290, 652], [734, 652], [734, 708], [290, 708]];

export default {
  id: 'glitch-cone',
  name: 'Glitch Cone',
  pitch:
    'Cone hat, visor and head, sliced and shifted. Built to hold up at 16 pixels, so it covers the tray icon, taskbar and favicon.',
  // Horizontal bands of the subject, each shifted sideways.
  glitch: [[0, 0], [318, -52], [372, 0], [548, 44], [600, 0], [728, -30], [760, 0]],
  build(ctx, p) {
    const head = `<circle cx="512" cy="680" r="210"/>`;

    const back = [
      streak(100, 330, 345, 40, p.split[0]),
      streak(160, 340, 574, 34, p.split[1]),
      streak(130, 330, 744, 22, p.accent),
    ].join('');

    const silhouette = poly([[512, 150], [300, 470], [724, 470]]) + poly(BRIM) + head;

    const seam = `stroke="${p.edge}" stroke-width="5" stroke-linejoin="round"`;
    const art =
      `<g fill="${p.ink}">${head}</g>` +
      poly(VISOR, `fill="${p.bg}"`) +
      poly([[330, 674], [694, 674], [694, 686], [330, 686]], `fill="${p.accent}"`) +
      poly(CONE_R, `fill="${p.foil[2]}" ${seam}`) +
      poly(CONE_L, `fill="${p.foil[0]}" ${seam}`) +
      poly(CONE_M, `fill="${p.foil[1]}" ${seam}`) +
      poly(BRIM, `fill="${p.accent}"`);

    return { back, silhouette, art, front: '' };
  },
};
