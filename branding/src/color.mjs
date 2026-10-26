// Color math for the app palette: tonal ramps and WCAG contrast.
//
// A ramp keeps a seed color's OKLCH hue and chroma and varies its tone, where
// tone is CIELAB L* (0 black, 100 white), the same scale Material 3 uses. Two
// colors' tones fix their contrast ratio regardless of hue, so a scheme built
// from tones (primary 40 on surface 98, and so on) has predictable contrast.
// Where the seed's chroma doesn't fit in sRGB at some tone, the ramp reduces
// chroma just enough to fit.

const srgbToLinear = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const linearToSrgb = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055);

export function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => srgbToLinear(v / 255));
}

export function rgbToHex(rgb) {
  return (
    '#' +
    rgb
      .map((c) => Math.round(Math.min(1, Math.max(0, linearToSrgb(Math.min(1, Math.max(0, c))))) * 255))
      .map((v) => v.toString(16).padStart(2, '0'))
      .join('')
      .toUpperCase()
  );
}

function linearToOklab([r, g, b]) {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}

function oklabToLinear([L, a, b]) {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
}

export function oklch(hex) {
  const [L, a, b] = linearToOklab(hexToRgb(hex));
  return { L, C: Math.hypot(a, b), h: (Math.atan2(b, a) * 180) / Math.PI };
}

const luminance = ([r, g, b]) => 0.2126 * r + 0.7152 * g + 0.0722 * b;
const lstar = (y) => (y <= 216 / 24389 ? (y * 24389) / 27 : 116 * Math.cbrt(y) - 16);

/** CIELAB L* of a color, 0–100. */
export const tone = (hex) => lstar(luminance(hexToRgb(hex)));

/** WCAG 2 contrast ratio between two colors, 1–21. */
export function contrast(a, b) {
  const [x, y] = [luminance(hexToRgb(a)), luminance(hexToRgb(b))].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

const inGamut = (rgb) => rgb.every((c) => c >= -1e-5 && c <= 1 + 1e-5);
const rad = (deg) => (deg * Math.PI) / 180;

// The OKLab color with hue h and chroma C whose L* is t (C held fixed; the
// result may be out of gamut).
function atTone(h, C, t) {
  let lo = 0, hi = 1;
  let rgb = [0, 0, 0];
  for (let i = 0; i < 32; i++) {
    const L = (lo + hi) / 2;
    rgb = oklabToLinear([L, C * Math.cos(rad(h)), C * Math.sin(rad(h))]);
    if (lstar(luminance(rgb)) < t) lo = L;
    else hi = L;
  }
  return rgb;
}

/**
 * A tonal ramp: ramp(t) is the seed's hue at tone t, as #RRGGBB.
 * chroma overrides the seed's OKLCH chroma (neutrals use a small fixed one).
 */
export function ramp(seed, { chroma, hue } = {}) {
  const s = oklch(seed);
  const h = hue ?? s.h;
  const C = chroma ?? s.C;
  const cache = new Map();
  const at = (t) => {
    if (t <= 0) return '#000000';
    if (t >= 100) return '#FFFFFF';
    if (cache.has(t)) return cache.get(t);
    let rgb = atTone(h, C, t);
    if (!inGamut(rgb)) {
      let lo = 0, hi = C;
      for (let i = 0; i < 24; i++) {
        const c = (lo + hi) / 2;
        if (inGamut(atTone(h, c, t))) lo = c;
        else hi = c;
      }
      rgb = atTone(h, lo, t);
    }
    const hex = rgbToHex(rgb);
    cache.set(t, hex);
    return hex;
  };
  at.seed = seed;
  return at;
}
