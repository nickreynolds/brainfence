// Brand palettes. Every mark, wordmark and lockup is rendered once per palette,
// so adding an entry here adds a full column of assets on the next build.
//
// Roles:
//   bg / bg2   background and its raised shade (textures, lattices)
//   ink        main figure color (head, letters)
//   foil       four tinfoil tones, lightest first
//   edge       seams between foil facets and outlines around wires
//   accent     hazard color: visor light, hat band stripes, brim
//   split      [left, right] chromatic-aberration ghosts
//   brain      the brain itself
//   spark      lightning and impact stars

export const palettes = [
  {
    id: 'static',
    name: 'Static',
    note: 'Night-city default. Bright foil on a void, magenta and cyan split.',
    light: false,
    bg: '#0B0A10',
    bg2: '#1E1A29',
    ink: '#F4F1FA',
    foil: ['#FFFFFF', '#CDD0DC', '#9095A8', '#575C70'],
    edge: '#0B0A10',
    accent: '#D7FF1F',
    split: ['#FF2D7A', '#18E0FF'],
    brain: '#FF2D7A',
    spark: '#D7FF1F',
  },
  {
    id: 'riot',
    name: 'Riot',
    note: 'Gig-poster red, black and hazard yellow. The loudest option.',
    light: false,
    bg: '#E3221B',
    bg2: '#C01710',
    ink: '#0A0A0A',
    foil: ['#FFFFFF', '#ECE7DD', '#BEB7A9', '#7F786C'],
    edge: '#0A0A0A',
    accent: '#FFE500',
    split: ['#0A0A0A', '#FFE500'],
    brain: '#FFE500',
    spark: '#FFFFFF',
  },
  {
    id: 'xerox',
    name: 'Xerox',
    note: 'Photocopied zine on cream stock, with misregistered orange and blue ink.',
    light: true,
    bg: '#ECE8DF',
    bg2: '#D8D2C4',
    ink: '#111111',
    foil: ['#FFFFFF', '#D6D6D6', '#9C9C9C', '#505050'],
    edge: '#111111',
    accent: '#FF3B14',
    split: ['#FF3B14', '#1438FF'],
    brain: '#FF3B14',
    spark: '#1438FF',
  },
  {
    id: 'ultraviolet',
    name: 'Ultraviolet',
    note: "Built on the app's current purple (#D0BCFF), pushed toward rave.",
    light: false,
    bg: '#160A33',
    bg2: '#2A1660',
    ink: '#EADDFF',
    foil: ['#FFFFFF', '#D0BCFF', '#9A82DB', '#5B4497'],
    edge: '#160A33',
    accent: '#3DFFD0',
    split: ['#FF3DCB', '#3DFFD0'],
    brain: '#FF3DCB',
    spark: '#FFF04D',
  },
];

// Mono assets are drawn inside an SVG mask: white keeps ink, black cuts a
// hole. Mapping every role to one of the two turns any mark into a
// single-color template (Android themed icon, macOS menu bar, Windows tray).
export const MASK = {
  id: 'mask',
  bg: '#000',
  bg2: '#000',
  ink: '#fff',
  foil: ['#fff', '#fff', '#fff', '#fff'],
  edge: '#000',
  accent: '#fff',
  split: ['#fff', '#fff'],
  brain: '#fff',
  spark: '#fff',
};
