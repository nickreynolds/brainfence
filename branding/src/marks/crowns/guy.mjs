// "The guy" from Chainlink Crown (round 1): a visored head with a neck,
// inside broken signal rings. Crown modules sit on top of him.
//
// Crown space: x 300–724, y 160–532. The head's crown is at y 450; a band
// across y 470–532 reads as a hat brim.

import { poly } from '../../fx.mjs';

const NECK = [[462, 830], [562, 830], [584, 910], [440, 910]];
const VISOR = [[270, 614], [754, 600], [754, 670], [270, 684]];
const EYES = [
  [[372, 632], [474, 629], [474, 649], [372, 652]],
  [[550, 627], [652, 624], [652, 644], [550, 647]],
];

export const BAND = [[310, 470], [714, 470], [714, 532], [310, 532]];

// A crown is (ctx, p, opts) => { silhouette, art, behind?, back?, front? }:
//   silhouette  geometry for the RGB-split ghosts (opaque parts only)
//   behind      drawn before the head (the far side of a halo, say)
//   art         drawn on top of the head
//   back/front  extra layers behind or above everything
//   splitDx     RGB-split offset, if the default 14 is too wide
export function crownMark({ id, name, pitch, crown, glitch }) {
  return {
    id,
    name,
    pitch,
    glitch,
    build(ctx, p, opts) {
      const c = crown(ctx, p, opts);
      const head = `<circle cx="512" cy="660" r="210"/>` + poly(NECK);
      const back =
        [380, 450]
          .map((r) => `<circle cx="512" cy="580" r="${r}" fill="none" stroke="${p.bg2}" stroke-width="18" stroke-dasharray="40 26"/>`)
          .join('') + (c.back ?? '');
      const art =
        (c.behind ?? '') +
        `<g fill="${p.ink}">${head}</g>` +
        poly(VISOR, `fill="${p.bg}"`) +
        EYES.map((e) => poly(e, `fill="${p.accent}"`)).join('') +
        c.art;
      // Thin bars turn to mush under a wide split, so crowns can narrow it.
      const split = c.splitDx ? { dx: c.splitDx } : undefined;
      return { back, silhouette: head + poly(VISOR) + c.silhouette, art, front: c.front ?? '', split };
    },
  };
}

// Fixed, slightly irregular crown heights for n posts, tallest in the middle.
export function crownTops(n, low, high) {
  const mid = (n - 1) / 2;
  return Array.from({ length: n }, (_, i) => low - (low - high) * (1 - Math.abs(i - mid) / mid) ** 0.8);
}

export const outline = (p, w = 7) => `stroke="${p.edge}" stroke-width="${w}" stroke-linejoin="miter"`;

// Concertina razor wire: stroked loops with a blade on top of each.
// With no palette, returns just the loop geometry for a silhouette.
export function loop(p, x, y, rx, ry, rot, tone) {
  const e = `<ellipse cx="${x}" cy="${y}" rx="${rx}" ry="${ry}" transform="rotate(${rot} ${x} ${y})" fill="none"`;
  if (!p) return `${e} stroke-width="15"/>`;
  const blade = poly(
    [[x - 11, y - ry], [x, y - ry - 9], [x + 11, y - ry], [x, y - ry + 9]],
    `fill="${p.foil[0]}" stroke="${p.edge}" stroke-width="3"`,
  );
  return `${e} stroke="${p.edge}" stroke-width="15"/>${e} stroke="${tone}" stroke-width="8"/>${blade}`;
}
