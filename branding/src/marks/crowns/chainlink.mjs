// Parametric Chainlink Crown. With default options it draws the same crown as
// round 1's chainlink-crown.mjs; each option is one small change on top.
//
//   peaks    5 (the original) or 7
//   mesh     chain-link spacing; 50 is the original
//   wire     extra wire on top: any of 'edge', 'strung', 'coil', 'tangle'
//   tips     'balls' (original), 'spikes' or 'sparks'

import { poly, lattice, rng, star } from '../../fx.mjs';
import { BAND, outline } from './guy.mjs';

const ORIGINAL = [
  [322, 520], [322, 230], [368, 390], [417, 300], [464, 390], [512, 180],
  [560, 390], [607, 300], [656, 390], [702, 230], [702, 520],
];

function crownPoints(peaks) {
  if (peaks === 5) return ORIGINAL;
  const heights = [236, 300, 262, 180, 262, 300, 236];
  const step = (702 - 322) / (peaks - 1);
  const pts = [[322, 520]];
  for (let i = 0; i < peaks; i++) {
    pts.push([322 + i * step, heights[i]]);
    if (i < peaks - 1) pts.push([322 + (i + 0.5) * step, 400]);
  }
  pts.push([702, 520]);
  return pts;
}

// Points every `spacing` px along a polyline, with the local direction.
function sample(points, spacing, offset = spacing / 2) {
  const out = [];
  let carry = offset;
  for (let i = 0; i < points.length - 1; i++) {
    const [x1, y1] = points[i];
    const [x2, y2] = points[i + 1];
    const len = Math.hypot(x2 - x1, y2 - y1);
    const ux = (x2 - x1) / len;
    const uy = (y2 - y1) / len;
    let d = carry;
    for (; d < len; d += spacing) out.push({ x: x1 + ux * d, y: y1 + uy * d, ux, uy });
    carry = d - len;
  }
  return out;
}

// Flatten a quadratic curve to a polyline so barbs can follow it.
function quad([x0, y0], [cx, cy], [x1, y1], n = 12) {
  return Array.from({ length: n + 1 }, (_, i) => {
    const t = i / n;
    const a = (1 - t) ** 2;
    const b = 2 * (1 - t) * t;
    const c = t * t;
    return [a * x0 + b * cx + c * x1, a * y0 + b * cy + c * y1];
  });
}

// Offset a left-to-right polyline outward (upward side) by `by` px, mitered at
// each vertex so the strand stays parallel to steep edges too.
function offsetUp(points, by) {
  const normal = (a, b) => {
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]);
    return [(b[1] - a[1]) / len, -(b[0] - a[0]) / len];
  };
  return points.map((pt, i) => {
    const n1 = i > 0 ? normal(points[i - 1], pt) : null;
    const n2 = i < points.length - 1 ? normal(pt, points[i + 1]) : null;
    if (!n1 || !n2) {
      const [nx, ny] = n1 ?? n2;
      return [pt[0] + nx * by, pt[1] + ny * by];
    }
    const mx = n1[0] + n2[0];
    const my = n1[1] + n2[1];
    const ml = Math.hypot(mx, my);
    const scale = Math.min(by / Math.max((mx * n1[0] + my * n1[1]) / ml, 0.3), by * 2.2);
    return [pt[0] + (mx / ml) * scale, pt[1] + (my / ml) * scale];
  });
}

const d = (points) => 'M' + points.map(([x, y]) => `${x.toFixed(1)} ${y.toFixed(1)}`).join('L');

// Barbed wire: an outlined strand with an X knot every `spacing` px.
function barbed(p, points, { spacing = 46, size = 13, width = 6 } = {}) {
  let barbs = '';
  for (const s of sample(points, spacing)) {
    for (const k of [1, -1]) {
      // Barb arms at ±45° to the wire.
      const bx = (s.ux - k * s.uy) * Math.SQRT1_2 * size;
      const by = (s.uy + k * s.ux) * Math.SQRT1_2 * size;
      barbs += `M${(s.x - bx).toFixed(1)} ${(s.y - by).toFixed(1)}l${(2 * bx).toFixed(1)} ${(2 * by).toFixed(1)}`;
    }
  }
  const path = d(points);
  return (
    `<path d="${path}${barbs}" fill="none" stroke="${p.edge}" stroke-width="${width + 7}" stroke-linecap="round" stroke-linejoin="round"/>` +
    `<path d="${path}${barbs}" fill="none" stroke="${p.foil[0]}" stroke-width="${width}" stroke-linecap="round" stroke-linejoin="round"/>`
  );
}

function coil(p, points) {
  return sample(points, 30, 0)
    .map((s, i) => {
      const rot = (Math.atan2(s.uy, s.ux) * 180) / Math.PI + (i % 2 ? 18 : -18);
      const e = `<ellipse cx="${s.x.toFixed(1)}" cy="${s.y.toFixed(1)}" rx="16" ry="24" transform="rotate(${rot.toFixed(1)} ${s.x.toFixed(1)} ${s.y.toFixed(1)})" fill="none"`;
      return `${e} stroke="${p.edge}" stroke-width="12"/>${e} stroke="${p.foil[0]}" stroke-width="5"/>`;
    })
    .join('');
}

export function chainlink({ peaks = 5, mesh = 50, wire = [], tips = 'balls', seed = 3 } = {}) {
  return (ctx, p) => {
    const CROWN = crownPoints(peaks);
    const top = CROWN.slice(1, -1);
    const PEAKS = top.filter((_, i) => i % 2 === 0);

    const leftHalf = ctx.uid('lh');
    ctx.def(`<clipPath id="${leftHalf}"><rect x="0" y="0" width="512" height="1024"/></clipPath>`);

    let barbs = '';
    for (let x = 340; x <= 690; x += 70) barbs += `M${x - 13} 487L${x + 13} 515M${x + 13} 487L${x - 13} 515`;

    let extra = '';
    if (wire.includes('edge')) extra += barbed(p, offsetUp(top, 18));
    if (wire.includes('coil')) extra += coil(p, top);
    if (wire.includes('strung')) {
      // Two strands sagging from point to point, like wire run between posts.
      for (const drop of [0, 46]) {
        const strand = [];
        for (let i = 0; i < PEAKS.length - 1; i++) {
          const a = [PEAKS[i][0], PEAKS[i][1] + drop + 10];
          const b = [PEAKS[i + 1][0], PEAKS[i + 1][1] + drop + 10];
          strand.push(...quad(a, [(a[0] + b[0]) / 2, Math.max(a[1], b[1]) + 56], b).slice(i ? 1 : 0));
        }
        extra += barbed(p, strand, { spacing: 52, size: 11, width: 5 });
      }
    }
    if (wire.includes('tangle')) {
      const rand = rng(seed);
      for (let k = 0; k < 3; k++) {
        const y0 = 250 + rand() * 170;
        const y1 = 250 + rand() * 170;
        const curve = quad([296, y0], [512, 200 + rand() * 260], [728, y1], 16);
        extra += barbed(p, k % 2 ? curve.reverse() : curve, { spacing: 50, size: 11, width: 5 });
      }
    }

    let tipArt = '';
    if (tips === 'balls') {
      tipArt = PEAKS.map(([x, y]) => `<circle cx="${x}" cy="${y}" r="20" fill="${p.accent}" stroke="${p.edge}" stroke-width="7"/>`).join('');
    } else if (tips === 'spikes') {
      tipArt = PEAKS.map(([x, y]) =>
        `<path d="M${x} ${y}V${y - 58}" stroke="${p.edge}" stroke-width="14" stroke-linecap="round"/>` +
        `<path d="M${x} ${y}V${y - 58}" stroke="${p.foil[0]}" stroke-width="6" stroke-linecap="round"/>` +
        `<path d="M${x - 12} ${y - 42}l24 24M${x + 12} ${y - 42}l-24 24" stroke="${p.edge}" stroke-width="12" stroke-linecap="round"/>` +
        `<path d="M${x - 12} ${y - 42}l24 24M${x + 12} ${y - 42}l-24 24" stroke="${p.foil[0]}" stroke-width="5" stroke-linecap="round"/>` +
        `<circle cx="${x}" cy="${y - 62}" r="10" fill="${p.accent}" stroke="${p.edge}" stroke-width="5"/>`,
      ).join('');
    } else if (tips === 'sparks') {
      tipArt = PEAKS.map(([x, y], i) => star(x, y, i % 2 ? 30 : 42, 9, 4, p.spark, i % 2 ? Math.PI / 4 : 0)).join('');
    }

    return {
      silhouette: poly(CROWN) + poly(BAND),
      art:
        poly(CROWN, `fill="${p.foil[1]}"`) +
        `<g clip-path="url(#${leftHalf})">${poly(CROWN, `fill="${p.foil[0]}"`)}</g>` +
        lattice(ctx, { x: 300, y: 160, w: 424, h: 380 }, mesh, p.edge, mesh < 50 ? 7 : 9, poly(CROWN)) +
        poly(CROWN, `fill="none" ${outline(p, 8)}`) +
        poly(BAND, `fill="${p.foil[2]}" stroke="${p.edge}" stroke-width="8"`) +
        `<path d="M296 501H728${barbs}" fill="none" stroke="${p.edge}" stroke-width="8"/>` +
        extra +
        tipArt,
    };
  };
}
