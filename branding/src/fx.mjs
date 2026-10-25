// Shared SVG primitives. Marks use plain fills and strokes plus clipPath and
// mask, nothing else, so they convert cleanly to Android vector drawables and
// rasterise the same everywhere. Textures (pattern, gradient) are only used in
// poster variants.

export const S = 1024;

const r1 = (n) => Math.round(n * 10) / 10;
export const pts = (a) => a.map(([x, y]) => `${r1(x)},${r1(y)}`).join(' ');
export const poly = (a, attrs = '') => `<polygon points="${pts(a)}" ${attrs}/>`;

// Deterministic randomness so builds are reproducible (mulberry32).
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Collects <defs> and hands out ids that are unique within one document.
export function createCtx(prefix = 'bf') {
  let n = 0;
  const defs = [];
  return { uid: (k) => `${prefix}${k}${++n}`, defs, def: (s) => defs.push(s) };
}

export function doc(body, ctx, { w = S, h = S, title = 'Brainfence' } = {}) {
  const defs = ctx.defs.length ? `<defs>${ctx.defs.join('')}</defs>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}"><title>${title}</title>${defs}${body}</svg>\n`;
}

// Chromatic aberration: two flat-colored copies of the silhouette, nudged
// apart. Silhouette elements carry geometry and stroke-width only; filled
// shapes take the group fill, lines set fill="none" and take the group stroke.
export function split(silhouette, p, { dx = 14, dy = 0 } = {}) {
  const [a, b] = p.split;
  return (
    `<g fill="${a}" stroke="${a}" stroke-width="0" transform="translate(${-dx} ${dy})">${silhouette}</g>` +
    `<g fill="${b}" stroke="${b}" stroke-width="0" transform="translate(${dx} ${-dy})">${silhouette}</g>`
  );
}

// Glitch: cut the content into horizontal bands and shift each one.
// cuts = [[y, dx], ...] sorted by y; each band runs to the next cut.
export function slices(content, ctx, cuts, { w = S, h = S } = {}) {
  return cuts
    .map(([y, dx], i) => {
      const y2 = i + 1 < cuts.length ? cuts[i + 1][0] : h;
      const id = ctx.uid('s');
      ctx.def(`<clipPath id="${id}"><rect x="-400" y="${y}" width="${w + 800}" height="${y2 - y}"/></clipPath>`);
      return `<g clip-path="url(#${id})"><g transform="translate(${dx} 0)">${content}</g></g>`;
    })
    .join('');
}

// Speed streak: full height at x1, tapering to a point at x0.
export const streak = (x0, x1, y, h, color) =>
  poly(
    [
      [x1, y - h / 2],
      [x1, y + h / 2],
      [x0, y + h / 6],
      [x0, y - h / 6],
    ],
    `fill="${color}"`,
  );

export const bolt = (points, color, width = 18) =>
  `<polyline points="${pts(points)}" fill="none" stroke="${color}" stroke-width="${width}" stroke-linejoin="miter" stroke-miterlimit="10" stroke-linecap="square"/>`;

export function star(cx, cy, R, r, n, color, rot = 0) {
  const a = [];
  for (let i = 0; i < n * 2; i++) {
    const t = rot + (Math.PI * i) / n;
    const rr = i % 2 ? r : R;
    a.push([cx + Math.sin(t) * rr, cy - Math.cos(t) * rr]);
  }
  return poly(a, `fill="${color}"`);
}

// Chain-link fence: two families of 45° lines, optionally clipped to a shape.
export function lattice(ctx, { x = 0, y = 0, w = S, h = S }, gap, color, width, clip) {
  let d = '';
  for (let o = -h; o < w + h; o += gap) d += `M${x + o} ${y}l${h} ${h}M${x + o + h} ${y}l${-h} ${h}`;
  const path = `<path d="${d}" fill="none" stroke="${color}" stroke-width="${width}"/>`;
  if (!clip) return path;
  const id = ctx.uid('lc');
  ctx.def(`<clipPath id="${id}">${clip}</clipPath>`);
  return `<g clip-path="url(#${id})">${path}</g>`;
}

// Halftone dots fading out from a center point. Poster-only texture.
export function halftone(ctx, color, { cx = S / 2, cy = S / 2, r = 480, cell = 26, dot = 7, w = S, h = S } = {}) {
  const pid = ctx.uid('ht');
  const gid = ctx.uid('hg');
  const mid = ctx.uid('hm');
  ctx.def(
    `<pattern id="${pid}" width="${cell}" height="${cell}" patternUnits="userSpaceOnUse" patternTransform="rotate(30)"><circle cx="${cell / 2}" cy="${cell / 2}" r="${dot}" fill="${color}"/></pattern>` +
      `<radialGradient id="${gid}" cx="${cx}" cy="${cy}" r="${r}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></radialGradient>` +
      `<mask id="${mid}" maskUnits="userSpaceOnUse" x="0" y="0" width="${w}" height="${h}"><rect width="${w}" height="${h}" fill="url(#${gid})"/></mask>`,
  );
  return `<rect width="${w}" height="${h}" fill="url(#${pid})" mask="url(#${mid})"/>`;
}

// CRT scanlines over the whole frame. Poster-only texture.
export function scanlines(ctx, color, opacity = 0.07, { w = S, h = S, pitch = 8 } = {}) {
  const id = ctx.uid('sl');
  ctx.def(
    `<pattern id="${id}" width="${pitch}" height="${pitch}" patternUnits="userSpaceOnUse"><rect width="${pitch}" height="${pitch / 3}" fill="${color}"/></pattern>`,
  );
  return `<rect width="${w}" height="${h}" fill="url(#${id})" opacity="${opacity}"/>`;
}
