import { lattice, streak } from '../fx.mjs';
import { setText } from '../type.mjs';

export default {
  id: 'fence-mono',
  name: 'Fence Mono',
  pitch:
    'B and F in the house stencil type over a fence grid. A text mark for small spaces, social avatars and merch.',
  glitch: [[0, 0], [452, -38], [506, 0], [612, 30], [646, 0]],
  build(ctx, p) {
    const t = setText('BF', { u: 64, weight: 1.05 });
    const x = (1024 - t.w) / 2;
    const y = (1024 - t.h) / 2 - 20;
    const letters = `<g transform="translate(${x} ${y})">${t.body}</g>`;

    const back =
      lattice(ctx, { x: 0, y: 0, w: 1024, h: 1024 }, 64, p.bg2, 6) +
      streak(60, 300, 400, 30, p.split[0]) +
      streak(100, 300, 620, 18, p.split[1]);

    const art = `<g stroke="${p.ink}">${letters}</g>`;
    const front = streak(150, 880, 772, 26, p.accent);

    return { back, silhouette: letters, art, front };
  },
};
