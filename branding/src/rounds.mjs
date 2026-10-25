// Exploration rounds, oldest first. A round lists the marks and palettes it
// shows. Earlier rounds stay as they were reviewed: to revise a mark, add a
// new one in a new round instead of editing the old file.

import foilCrown from './marks/foil-crown.mjs';
import faradayDome from './marks/faraday-dome.mjs';
import chainlinkCrown from './marks/chainlink-crown.mjs';
import glitchCone from './marks/glitch-cone.mjs';
import fenceMono from './marks/fence-mono.mjs';
import picket from './marks/crowns/picket.mjs';
import palisade from './marks/crowns/palisade.mjs';
import razorHalo from './marks/crowns/razor-halo.mjs';
import electric from './marks/crowns/electric.mjs';
import spearRailing from './marks/crowns/spear-railing.mjs';
import laserGrid from './marks/crowns/laser-grid.mjs';
import yard from './marks/crowns/yard.mjs';
import chainlinkVariants from './marks/crowns/chainlink-variants.mjs';

export const rounds = [
  {
    id: 'r1',
    title: 'Round 1',
    subtitle: 'Five directions',
    note: 'The first pass: five different takes on a protected head, in four palettes.',
    palettes: ['static', 'riot', 'xerox', 'ultraviolet'],
    marks: [foilCrown, faradayDome, chainlinkCrown, glitchCone, fenceMono],
  },
  {
    id: 'r2',
    title: 'Round 2',
    subtitle: 'Fence crowns',
    note: 'Chainlink Crown won round 1. This round keeps the same guy and tries seven other fences as his crown, in Static and Ultraviolet. Chainlink Crown comes first for comparison.',
    palettes: ['static', 'ultraviolet'],
    marks: [chainlinkCrown, picket, palisade, razorHalo, electric, spearRailing, laserGrid, yard],
  },
  {
    id: 'r3',
    title: 'Round 3',
    subtitle: 'Chainlink, tuned',
    note: 'Chainlink Crown in Static is still the favourite. These keep everything about it and change one or two details, mostly how much wire is on top. The original comes first.',
    palettes: ['static'],
    marks: [chainlinkCrown, ...chainlinkVariants],
  },
];
