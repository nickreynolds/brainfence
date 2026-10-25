import { poly } from '../../fx.mjs';
import { BAND, crownMark, outline } from './guy.mjs';

const XS = [322, 417, 512, 607, 702];
const TOPS = [236, 292, 190, 292, 236];
const LEVELS = [258, 296, 334, 372, 410, 448];

export default crownMark({
  id: 'laser-grid',
  name: 'Laser Grid',
  pitch: 'Emitter posts strung with laser tripwires that step down into a crown shape, with a glitch through the beams. The most cyberpunk of the set.',
  glitch: [[0, 0], [318, -22], [346, 0]],
  crown(ctx, p) {
    const colors = [p.split[0], p.split[1], p.accent];
    let beams = '';
    let leds = '';
    LEVELS.forEach((y, k) => {
      const c = colors[k % colors.length];
      XS.slice(1).forEach((x, i) => {
        if (y < Math.max(TOPS[i], TOPS[i + 1]) + 24) return;
        beams +=
          `<line x1="${XS[i]}" y1="${y}" x2="${x}" y2="${y}" stroke="${c}" stroke-width="26" opacity="0.3"/>` +
          `<line x1="${XS[i]}" y1="${y}" x2="${x}" y2="${y}" stroke="${c}" stroke-width="9"/>`;
      });
      XS.forEach((x, i) => {
        if (y > TOPS[i] + 16) leds += `<rect x="${x - 6}" y="${y - 6}" width="12" height="12" fill="${c}"/>`;
      });
    });
    const posts = XS.map((x, i) => `<rect x="${x - 15}" y="${TOPS[i]}" width="30" height="${520 - TOPS[i]}"/>`).join('');
    let dashes = '';
    for (let x = 336; x < 700; x += 44) dashes += `<rect x="${x}" y="496" width="24" height="10" fill="${p.accent}"/>`;
    return {
      // Ghosting the posts too drowns the beams; the band is enough.
      silhouette: poly(BAND),
      art:
        beams +
        `<g fill="${p.foil[2]}" ${outline(p, 5)}>${posts}</g>` +
        XS.map((x, i) => `<rect x="${x - 15}" y="${TOPS[i]}" width="30" height="14" fill="${p.foil[0]}"/>`).join('') +
        leds +
        poly(BAND, `fill="${p.foil[3]}" ${outline(p, 8)}`) +
        dashes,
    };
  },
});
