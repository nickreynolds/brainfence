// Turns marks, palettes and type into finished documents. Every function
// here is palette-agnostic: pass a palette from palettes.mjs.

import { S, createCtx, doc, split, slices, halftone, scanlines, lattice, streak } from './fx.mjs';
import { MASK } from './palettes.mjs';
import { setText, measure } from './type.mjs';

export const VARIANTS = ['icon', 'poster', 'mono'];

// icon:   flat full-bleed tile, for app icons
// poster: icon plus halftone and scanlines, for stickers, splash, social
// mono:   one color (#000) on transparent, for themed and template icons
export function markBody(mark, p, variant, ctx, { background = true, back = true } = {}) {
  const mono = variant === 'mono';
  const poster = variant === 'poster';
  const pal = mono ? MASK : p;
  const parts = mark.build(ctx, pal, { poster, mono });
  let subject = (mono ? '' : split(parts.silhouette, pal, parts.split)) + parts.art;
  if (mark.glitch) subject = slices(subject, ctx, mark.glitch);
  const figure = (back ? parts.back : '') + subject + parts.front;

  if (mono) {
    const id = ctx.uid('mono');
    ctx.def(`<mask id="${id}" maskUnits="userSpaceOnUse" x="0" y="0" width="${S}" height="${S}">${figure}</mask>`);
    return `<rect width="${S}" height="${S}" fill="#000" mask="url(#${id})"/>`;
  }
  let out = background ? `<rect width="${S}" height="${S}" fill="${p.bg}"/>` : '';
  if (poster) out += halftone(ctx, p.bg2, { r: 540, dot: 8 });
  out += figure;
  if (poster) out += scanlines(ctx, p.ink, 0.06);
  return out;
}

export function renderMark(mark, p, variant) {
  const ctx = createCtx('m');
  const body = markBody(mark, p, variant, ctx);
  return doc(body, ctx, { title: `Brainfence ${mark.name}` });
}

const nest = (x, y, size, inner) => `<svg x="${x}" y="${y}" width="${size}" height="${size}" viewBox="0 0 ${S} ${S}">${inner}</svg>`;

// BRAINFENCE in the house type. two: FENCE in the accent color.
// ghost: chromatic-aberration copies behind the letters.
export function wordmarkBody(p, { u = 40, x = 0, y = 0, two = true, ghost = true } = {}) {
  const full = setText('BRAINFENCE', { u });
  let out = `<g transform="translate(${x} ${y})">`;
  if (ghost) out += split(full.body, p, { dx: u * 0.22 });
  if (two) {
    const brain = setText('BRAIN', { u });
    const fence = setText('FENCE', { u });
    const dx = (measure('BRAIN') + 0.8) * u;
    out += `<g stroke="${p.ink}">${brain.body}</g><g stroke="${p.accent}" transform="translate(${dx} 0)">${fence.body}</g>`;
  } else {
    out += `<g stroke="${p.ink}">${full.body}</g>`;
  }
  return { body: out + '</g>', w: full.w, h: full.h };
}

export function renderWordmark(p, opts = {}) {
  const ctx = createCtx('w');
  const u = 40;
  const pad = u;
  const wm = wordmarkBody(p, { u, x: pad, y: pad, ...opts });
  return doc(wm.body, ctx, { w: Math.ceil(wm.w + pad * 2), h: wm.h + pad * 2, title: 'Brainfence' });
}

export function renderLockup(mark, p) {
  const ctx = createCtx('l');
  const w = 2000;
  const h = 560;
  const u = 29;
  const wm = wordmarkBody(p, { u, x: 620, y: (h - 6 * u) / 2 });
  const body = `<rect width="${w}" height="${h}" fill="${p.bg}"/>` + nest(20, 0, h, markBody(mark, p, 'icon', ctx, { background: false })) + wm.body;
  return doc(body, ctx, { w, h, title: 'Brainfence' });
}

export const TAGLINE = 'Protect your head';

// 1500×500: the X/Twitter header size, and a good test of the whole kit.
export function renderBanner(mark, p) {
  const ctx = createCtx('b');
  const w = 1500;
  const h = 500;
  const tag = setText(TAGLINE, { u: 7, weight: 1.1 });
  const body =
    `<rect width="${w}" height="${h}" fill="${p.bg}"/>` +
    lattice(ctx, { x: 0, y: 0, w, h }, 54, p.bg2, 5) +
    `<g opacity="0.45">${halftone(ctx, p.split[1], { cx: 270, cy: 250, r: 300, w, h, cell: 22, dot: 6 })}</g>` +
    nest(20, 0, 500, markBody(mark, p, 'icon', ctx, { background: false })) +
    streak(560, 1440, 382, 10, p.split[0]) +
    streak(720, 1470, 404, 6, p.split[1]) +
    streak(620, 1300, 428, 14, p.accent) +
    wordmarkBody(p, { u: 19, x: 560, y: 150 }).body +
    `<g transform="translate(566 302)" stroke="${p.ink}">${tag.body}</g>` +
    scanlines(ctx, p.ink, 0.05, { w, h });
  return doc(body, ctx, { w, h, title: 'Brainfence' });
}

// 1024×500: the Google Play feature graphic. The banner, recomposed for its
// narrower frame.
export function renderFeatureGraphic(mark, p) {
  const ctx = createCtx('f');
  const w = 1024;
  const h = 500;
  const tag = setText(TAGLINE, { u: 4.6, weight: 1.1 });
  const body =
    `<rect width="${w}" height="${h}" fill="${p.bg}"/>` +
    lattice(ctx, { x: 0, y: 0, w, h }, 54, p.bg2, 5) +
    `<g opacity="0.45">${halftone(ctx, p.split[1], { cx: 250, cy: 250, r: 280, w, h, cell: 22, dot: 6 })}</g>` +
    nest(20, 20, 460, markBody(mark, p, 'icon', ctx, { background: false })) +
    streak(470, 1000, 330, 8, p.split[0]) +
    streak(560, 1010, 346, 5, p.split[1]) +
    streak(500, 900, 364, 10, p.accent) +
    wordmarkBody(p, { u: 10.5, x: 480, y: 165 }).body +
    `<g transform="translate(484 252)" stroke="${p.ink}">${tag.body}</g>` +
    scanlines(ctx, p.ink, 0.05, { w, h });
  return doc(body, ctx, { w, h, title: 'Brainfence' });
}

export function renderSpecimen(p) {
  const ctx = createCtx('t');
  const u = 22;
  const lines = ['ABCDEFGHIJKLM', 'NOPQRSTUVWXYZ', 'NO SIGNAL GETS IN.'];
  const w = 1480;
  const body = lines
    .map((line, i) => `<g transform="translate(40 ${40 + i * 9 * u})" stroke="${i === 2 ? p.accent : p.ink}">${setText(line, { u }).body}</g>`)
    .join('');
  return doc(body, ctx, { w, h: 40 * 2 + 6 * u + 2 * 9 * u, title: 'Fence Stencil' });
}
