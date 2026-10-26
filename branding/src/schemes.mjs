// Round 4 app palettes: candidate replacements for shared/tokens.json, built
// from the Static brand colors. Each direction picks a ramp (branding/src/
// color.mjs) per color family and gets every Material 3 role from the usual
// Material tones. A few roles are then pinned to exact brand colors, so the dark
// theme sits on the icon's void and uses the icon's own neon.
//
// Beyond Material's roles, each scheme defines three app families so every
// client draws blocking state the same way:
//   blocked    an app or site is blocked right now (Android uses error today, macOS orange)
//   unlocked   nothing is blocking, or a task that gates a rule is done (macOS green)
//   pending    a loosening change waiting out its time lock (macOS yellow)

import { contrast, ramp } from './color.mjs';

const STATIC = { void: '#0B0A10', ink: '#F4F1FA', foil: '#9095A8', lime: '#D7FF1F', magenta: '#FF2D7A', cyan: '#18E0FF' };

const lime = ramp(STATIC.lime);
const magenta = ramp(STATIC.magenta);
const cyan = ramp(STATIC.cyan);
// Error is a true red when magenta means blocked, and shifts toward orange so it
// stays apart from magenta when magenta is the primary color.
const red = ramp('#E5322D');
const ember = ramp('#F0501E');
const amber = ramp('#FFB000');
const foil = (chroma) => ramp(STATIC.foil, { chroma });
const voidTint = (chroma) => ramp(STATIC.void, { chroma });

// Material 3 tones for an accent family, light then dark.
const FAMILY = {
  light: { base: 40, on: 100, container: 90, onContainer: 10 },
  dark: { base: 80, on: 20, container: 30, onContainer: 90 },
};

// Neutral tones. Dark surfaces start at tone 3 (the icon's void) instead of
// Material's 6.
const NEUTRAL = {
  light: {
    background: 98, onBackground: 10, surface: 98, onSurface: 10, surfaceDim: 87, surfaceBright: 98,
    surfaceContainerLowest: 100, surfaceContainerLow: 96, surfaceContainer: 94, surfaceContainerHigh: 92, surfaceContainerHighest: 90,
    inverseSurface: 20, inverseOnSurface: 95,
  },
  dark: {
    background: 3, onBackground: 90, surface: 3, onSurface: 90, surfaceDim: 3, surfaceBright: 22,
    surfaceContainerLowest: 1, surfaceContainerLow: 8, surfaceContainer: 11, surfaceContainerHigh: 16, surfaceContainerHighest: 21,
    inverseSurface: 90, inverseOnSurface: 20,
  },
};
const VARIANT = {
  light: { surfaceVariant: 90, onSurfaceVariant: 30, outline: 50, outlineVariant: 80 },
  dark: { surfaceVariant: 30, onSurfaceVariant: 80, outline: 60, outlineVariant: 30 },
};

const FAMILIES = ['primary', 'secondary', 'tertiary', 'error', 'blocked', 'unlocked', 'pending'];
const cap = (s) => s[0].toUpperCase() + s.slice(1);

/** Role names in output order: shared/tokens.json's, then the app families. */
export const ROLES = [
  ...['primary', 'secondary', 'tertiary', 'error'].flatMap((f) => [f, `on${cap(f)}`, `${f}Container`, `on${cap(f)}Container`]),
  'inversePrimary',
  ...Object.keys(NEUTRAL.light),
  ...Object.keys(VARIANT.light),
  ...['blocked', 'unlocked', 'pending'].flatMap((f) => [f, `on${cap(f)}`, `${f}Container`, `on${cap(f)}Container`]),
];

function resolve({ ramps, pins = {} }) {
  const out = {};
  for (const mode of ['light', 'dark']) {
    const r = {};
    for (const f of FAMILIES) {
      const t = FAMILY[mode];
      r[f] = ramps[f](t.base);
      r[`on${cap(f)}`] = ramps[f](t.on);
      r[`${f}Container`] = ramps[f](t.container);
      r[`on${cap(f)}Container`] = ramps[f](t.onContainer);
    }
    for (const [role, t] of Object.entries(NEUTRAL[mode])) r[role] = ramps.neutral(t);
    for (const [role, t] of Object.entries(VARIANT[mode])) r[role] = ramps.neutralVariant(t);
    Object.assign(r, pins[mode]);
    out[mode] = r;
  }
  out.light.inversePrimary = out.dark.primary;
  out.dark.inversePrimary = out.light.primary;
  for (const mode of ['light', 'dark']) out[mode] = Object.fromEntries(ROLES.map((k) => [k, out[mode][k]]));
  return out;
}

// Pairs the apps actually draw, with the WCAG minimum each needs: 4.5 for text,
// 3 for icons and component edges.
export const CHECKS = [
  ['onSurface', 'surface', 4.5, 'Body text'],
  ['onSurfaceVariant', 'surface', 4.5, 'Secondary text'],
  ['onSurfaceVariant', 'surfaceContainerHighest', 4.5, 'Secondary text on a raised card'],
  ['primary', 'surface', 4.5, 'Text buttons and links'],
  ['onPrimary', 'primary', 4.5, 'Filled button label'],
  ['onPrimaryContainer', 'primaryContainer', 4.5, 'FAB and selected card'],
  ['onSecondaryContainer', 'secondaryContainer', 4.5, 'Selected nav item, tonal button'],
  ['tertiary', 'surface', 3, 'Running timer'],
  ['onTertiaryContainer', 'tertiaryContainer', 4.5, 'Timer card'],
  ['error', 'surface', 4.5, 'Error text'],
  ['onErrorContainer', 'errorContainer', 4.5, 'Error banner'],
  ['blocked', 'surface', 3, 'Lock icon'],
  ['onBlocked', 'blocked', 4.5, 'Blocked badge'],
  ['onBlockedContainer', 'blockedContainer', 4.5, 'Block screen and banner'],
  ['unlocked', 'surface', 3, 'Unlocked icon, done check'],
  ['onUnlocked', 'unlocked', 4.5, 'Unlocked badge'],
  ['onUnlockedContainer', 'unlockedContainer', 4.5, 'Unlocked banner'],
  ['pending', 'surface', 3, 'Time-lock icon'],
  ['onPendingContainer', 'pendingContainer', 4.5, 'Pending change notice'],
  ['outline', 'surface', 3, 'Text field and outlined button edge'],
];

function check(scheme) {
  const res = {};
  for (const mode of ['light', 'dark']) {
    const c = scheme[mode];
    res[mode] = CHECKS.map(([fg, bg, min, use]) => {
      const ratio = contrast(c[fg], c[bg]);
      return { fg, bg, min, use, ratio: Math.round(ratio * 100) / 100, pass: ratio >= min };
    });
  }
  return res;
}

const directions = [
  {
    id: 'foil',
    name: 'Foil',
    pitch:
      'Closest to the icon. Surfaces and buttons are foil greys, black on light and white on dark, and neon only shows up where it means something: lime for the main action and anything unlocked, magenta when something is blocked, cyan for timers and time locks.',
    ramps: {
      primary: foil(0.012), secondary: foil(0.03), tertiary: cyan, error: red,
      blocked: magenta, unlocked: lime, pending: cyan,
      neutral: foil(0.008), neutralVariant: foil(0.024),
    },
    pins: {
      light: {
        primary: '#1B1B21', onPrimary: '#FFFFFF',
        primaryContainer: STATIC.lime, onPrimaryContainer: STATIC.void,
      },
      dark: {
        primary: STATIC.ink, onPrimary: STATIC.void,
        primaryContainer: STATIC.lime, onPrimaryContainer: STATIC.void,
        blocked: STATIC.magenta, onBlocked: STATIC.void,
        unlocked: STATIC.lime, onUnlocked: STATIC.void,
        tertiary: STATIC.cyan, onTertiary: STATIC.void, pending: STATIC.cyan, onPending: STATIC.void,
        background: STATIC.void, surface: STATIC.void, surfaceDim: STATIC.void,
      },
    },
  },
  {
    id: 'hazard',
    name: 'Hazard',
    pitch:
      'Lime leads. Every primary action is the icon’s lime, and lime is a light color, so light mode can’t use it for text or button fills. There it darkens to an acid olive, and the bright lime only appears as container fills. Magenta still means blocked, cyan means unlocked, and amber marks a pending change.',
    ramps: {
      primary: lime, secondary: foil(0.03), tertiary: cyan, error: red,
      blocked: magenta, unlocked: cyan, pending: amber,
      neutral: voidTint(0.006), neutralVariant: foil(0.02),
    },
    pins: {
      light: { primaryContainer: STATIC.lime, onPrimaryContainer: STATIC.void },
      dark: {
        primary: STATIC.lime, onPrimary: STATIC.void,
        blocked: STATIC.magenta, onBlocked: STATIC.void,
        tertiary: STATIC.cyan, onTertiary: STATIC.void, unlocked: STATIC.cyan, onUnlocked: STATIC.void,
        background: STATIC.void, surface: STATIC.void, surfaceDim: STATIC.void,
      },
    },
  },
  {
    id: 'signal',
    name: 'Signal',
    pitch:
      'Magenta leads, the brain inside the crown. It holds up best in light mode, where a deep raspberry has plenty of contrast. Blocking is the app’s job, so a block screen is magenta too, not an error. Errors shift to orange-red so they stay distinct, lime means unlocked and cyan means pending.',
    ramps: {
      primary: magenta, secondary: voidTint(0.045), tertiary: cyan, error: ember,
      blocked: magenta, unlocked: lime, pending: cyan,
      neutral: voidTint(0.012), neutralVariant: voidTint(0.03),
    },
    pins: {
      dark: {
        primary: STATIC.magenta, onPrimary: STATIC.void,
        blocked: STATIC.magenta, onBlocked: STATIC.void,
        tertiary: STATIC.cyan, onTertiary: STATIC.void, pending: STATIC.cyan, onPending: STATIC.void,
        unlocked: STATIC.lime, onUnlocked: STATIC.void,
        background: STATIC.void, surface: STATIC.void, surfaceDim: STATIC.void,
      },
    },
  },
];

export const schemes = directions.map((d) => {
  const colors = resolve(d);
  return { id: d.id, name: d.name, pitch: d.pitch, colors, checks: check(colors) };
});

/** The brand colors and their ramps, for the primitives strip. */
export const PRIMITIVES = [
  ['Lime', STATIC.lime, lime],
  ['Magenta', STATIC.magenta, magenta],
  ['Cyan', STATIC.cyan, cyan],
  ['Foil', STATIC.foil, foil(0.03)],
  ['Void', STATIC.void, voidTint(0.012)],
].map(([name, seed, r]) => ({ name, seed, tones: [10, 20, 30, 40, 50, 60, 70, 80, 90, 95].map((t) => [t, r(t)]) }));
