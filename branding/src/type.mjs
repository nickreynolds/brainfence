// "Fence Stencil": the house display type. Each glyph is a centerline on a
// 4×6 grid (M and W are 5 wide, I is 1), stroked one unit thick with square
// caps so the outer edges land exactly on the grid. Corners are chamfered
// rather than rounded, and the whole line is slanted forward for speed.
//
// The output is stroked paths, which is fine for SVG and the web. For a
// font file or a vector drawable, outline the strokes first.

export const GLYPHS = {
  A: { w: 4, d: 'M0.5 5.5V1.8L1.8 0.5H2.2L3.5 1.8V5.5M0.5 3.6H3.5' },
  B: { w: 4, d: 'M0.5 5.5V0.5H2.7L3.5 1.3V2.2L2.7 3H0.5M2.7 3L3.5 3.8V4.7L2.7 5.5H0.5' },
  C: { w: 4, d: 'M3.5 0.5H1.3L0.5 1.3V4.7L1.3 5.5H3.5' },
  D: { w: 4, d: 'M0.5 0.5H2.7L3.5 1.3V4.7L2.7 5.5H0.5Z' },
  E: { w: 4, d: 'M3.5 0.5H0.5V5.5H3.5M0.5 3H2.8' },
  F: { w: 4, d: 'M3.5 0.5H0.5V5.5M0.5 3H2.8' },
  G: { w: 4, d: 'M3.5 0.5H1.3L0.5 1.3V4.7L1.3 5.5H3.5V3H2.2' },
  H: { w: 4, d: 'M0.5 0.5V5.5M3.5 0.5V5.5M0.5 3H3.5' },
  I: { w: 1, d: 'M0.5 0.5V5.5' },
  J: { w: 4, d: 'M3.5 0.5V4.7L2.7 5.5H0.5' },
  K: { w: 4, d: 'M0.5 0.5V5.5M0.5 3.2H1.6L3.5 0.5M1.6 3.2L3.5 5.5' },
  L: { w: 4, d: 'M0.5 0.5V5.5H3.5' },
  M: { w: 5, d: 'M0.5 5.5V0.5L2.5 3L4.5 0.5V5.5' },
  N: { w: 4, d: 'M0.5 5.5V0.5L3.5 5.5V0.5' },
  O: { w: 4, d: 'M1.3 0.5H2.7L3.5 1.3V4.7L2.7 5.5H1.3L0.5 4.7V1.3Z' },
  P: { w: 4, d: 'M0.5 5.5V0.5H2.7L3.5 1.3V2.2L2.7 3H0.5' },
  Q: { w: 4, d: 'M1.3 0.5H2.7L3.5 1.3V4.7L2.7 5.5H1.3L0.5 4.7V1.3ZM2.4 4.4L3.5 5.5' },
  R: { w: 4, d: 'M0.5 5.5V0.5H2.7L3.5 1.3V2.2L2.7 3H0.5M2.2 3L3.5 4.3V5.5' },
  S: { w: 4, d: 'M3.5 0.5H1.3L0.5 1.3V2.2L1.3 3H2.7L3.5 3.8V4.7L2.7 5.5H0.5' },
  T: { w: 4, d: 'M0.5 0.5H3.5M2 0.5V5.5' },
  U: { w: 4, d: 'M0.5 0.5V4.7L1.3 5.5H2.7L3.5 4.7V0.5' },
  V: { w: 4, d: 'M0.5 0.5V3.5L2 5.5L3.5 3.5V0.5' },
  W: { w: 5, d: 'M0.5 0.5V5.5L2.5 3.5L4.5 5.5V0.5' },
  X: { w: 4, d: 'M0.5 0.5L3.5 5.5M3.5 0.5L0.5 5.5' },
  Y: { w: 4, d: 'M0.5 0.5L2 3L3.5 0.5M2 3V5.5' },
  Z: { w: 4, d: 'M0.5 0.5H3.5L0.5 5.5H3.5' },
  0: { w: 4, d: 'M1.3 0.5H2.7L3.5 1.3V4.7L2.7 5.5H1.3L0.5 4.7V1.3ZM3.1 1.4L0.9 4.6' },
  1: { w: 3, d: 'M0.5 1.3L1.3 0.5H1.5V5.5M0.5 5.5H2.5' },
  2: { w: 4, d: 'M0.5 0.5H2.7L3.5 1.3V2.2L2.7 3H1.3L0.5 3.8V5.5H3.5' },
  3: { w: 4, d: 'M0.5 0.5H2.7L3.5 1.3V2.2L2.7 3H1.4M2.7 3L3.5 3.8V4.7L2.7 5.5H0.5' },
  4: { w: 4, d: 'M2.8 5.5V0.5L0.5 3.6H3.5' },
  5: { w: 4, d: 'M3.5 0.5H0.5V3H2.7L3.5 3.8V4.7L2.7 5.5H0.5' },
  6: { w: 4, d: 'M3.5 0.5H1.3L0.5 1.3V4.7L1.3 5.5H2.7L3.5 4.7V3.8L2.7 3H0.5' },
  7: { w: 4, d: 'M0.5 0.5H3.5V1.3L1.5 5.5' },
  8: { w: 4, d: 'M1.3 0.5H2.7L3.5 1.3V2.2L2.7 3H1.3L0.5 2.2V1.3ZM1.3 3H2.7L3.5 3.8V4.7L2.7 5.5H1.3L0.5 4.7V3.8Z' },
  9: { w: 4, d: 'M0.5 5.5H2.7L3.5 4.7V1.3L2.7 0.5H1.3L0.5 1.3V2.2L1.3 3H3.5' },
  '?': { w: 4, d: 'M0.5 0.5H2.7L3.5 1.3V2.2L2.7 3H2V4M2 5.5V5.5' },
  '!': { w: 1, d: 'M0.5 0.5V4M0.5 5.5V5.5' },
  ':': { w: 1, d: 'M0.5 2V2M0.5 5V5' },
  ',': { w: 1, d: 'M0.5 5.2V6.2' },
  "'": { w: 1, d: 'M0.5 0.5V1.8' },
  '-': { w: 3, d: 'M0.5 3H2.5' },
  '.': { w: 1, d: 'M0.5 5.5V5.5' },
  '/': { w: 3, d: 'M0.5 5.5L2.5 0.5' },
  ' ': { w: 2, d: '' },
};

const CAP = 6;

// Advance width of a run of text in grid units, without trailing tracking.
export function measure(text, tracking = 0.8) {
  let x = 0;
  for (const ch of text.toUpperCase()) x += (GLYPHS[ch] ?? GLYPHS[' ']).w + tracking;
  return x - tracking;
}

// Returns stroke-only markup (stroke color comes from the parent group, so the
// result works inside split()) plus its size in px. u is px per grid unit.
export function setText(text, { u = 40, tracking = 0.8, weight = 1, skew = 12 } = {}) {
  let x = 0;
  const paths = [];
  for (const ch of text.toUpperCase()) {
    const g = GLYPHS[ch] ?? GLYPHS[' '];
    if (g.d) paths.push(`<path transform="translate(${x} 0)" d="${g.d}"/>`);
    x += g.w + tracking;
  }
  const units = x - tracking;
  const off = CAP * Math.tan((skew * Math.PI) / 180);
  const body =
    `<g transform="scale(${u}) translate(${off.toFixed(4)} 0) skewX(${-skew})" fill="none" stroke-width="${weight}" ` +
    `stroke-linecap="square" stroke-linejoin="miter" stroke-miterlimit="1.5">${paths.join('')}</g>`;
  return { body, w: (units + off) * u, h: CAP * u };
}
