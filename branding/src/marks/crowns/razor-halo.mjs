import { star } from '../../fx.mjs';
import { crownMark, loop } from './guy.mjs';

const CX = 512;
const CY = 360;
const RX = 236;
const RY = 72;
const N = 16;

export default crownMark({
  id: 'razor-halo',
  name: 'Razor Halo',
  pitch: 'A floating ring of concertina razor wire in place of a crown. A punk saint. The far side of the coil passes behind the head.',
  crown(ctx, p) {
    const loops = Array.from({ length: N }, (_, i) => {
      const a = (2 * Math.PI * i) / N;
      return { x: CX + RX * Math.cos(a), y: CY + RY * Math.sin(a), front: Math.sin(a) > 0.01, rot: Math.cos(a) * 24 };
    }).sort((a, b) => a.y - b.y);
    const draw = (l) => loop(p, l.x, l.y, 44, 54, l.rot, l.front ? p.foil[0] : p.foil[3]);
    return {
      silhouette: loops.map((l) => loop(null, l.x, l.y, 44, 54, l.rot)).join(''),
      behind: loops.filter((l) => !l.front).map(draw).join(''),
      art: loops.filter((l) => l.front).map(draw).join(''),
      front: star(286, 330, 34, 9, 4, p.spark) + star(742, 394, 26, 7, 4, p.spark, Math.PI / 4),
    };
  },
});
