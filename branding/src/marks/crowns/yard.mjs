import { poly, lattice } from '../../fx.mjs';
import { BAND, crownMark, outline, loop } from './guy.mjs';

const POSTS = [[306, 262], [694, 262]];
const RAIL = [[318, 290], [706, 290], [706, 306], [318, 306]];

export default crownMark({
  id: 'yard-crown',
  name: 'Yard Crown',
  pitch: 'A straight run of chain-link topped with razor coil, like a prison yard. Chainlink Crown made harder and flatter.',
  crown(ctx, p) {
    const posts = POSTS.map(([x, y]) => `<rect x="${x}" y="${y}" width="24" height="${532 - y}"/>`).join('');
    const caps = POSTS.map(([x, y]) => `<rect x="${x - 6}" y="${y - 12}" width="36" height="14" fill="${p.foil[1]}" ${outline(p, 5)}/>`).join('');
    const coilAt = (pal) => Array.from({ length: 10 }, (_, i) => loop(pal, 334 + i * 39.6, 246, 34, 40, i % 2 ? 14 : -14, p.foil[0])).join('');
    const coil = coilAt(p);
    let barbs = '';
    for (let x = 340; x <= 690; x += 70) barbs += `M${x - 13} 487L${x + 13} 515M${x + 13} 487L${x - 13} 515`;
    return {
      // The mesh itself is left out so the ghosts don't fill its holes.
      silhouette: posts + poly(RAIL) + poly(BAND) + coilAt(null),
      art:
        lattice(ctx, { x: 318, y: 306, w: 388, h: 170 }, 40, p.foil[2], 6, `<rect x="318" y="306" width="388" height="170"/>`) +
        `<g fill="${p.foil[2]}" ${outline(p, 6)}>${posts}</g>` +
        caps +
        poly(RAIL, `fill="${p.foil[1]}" ${outline(p, 5)}`) +
        coil +
        poly(BAND, `fill="${p.foil[2]}" ${outline(p, 8)}`) +
        `<path d="M296 501H728${barbs}" fill="none" stroke="${p.edge}" stroke-width="8"/>`,
    };
  },
});
