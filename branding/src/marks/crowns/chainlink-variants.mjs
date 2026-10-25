// Round 3: close variations on Chainlink Crown, each one or two small changes.

import { crownMark } from './guy.mjs';
import { chainlink } from './chainlink.mjs';

const v = (id, name, pitch, opts) => crownMark({ id: `chainlink-${id}`, name, pitch, crown: chainlink(opts) });

export default [
  v('edge', 'Barbed Edge', 'Same crown with a strand of barbed wire run along the whole top edge, peak to valley.', { wire: ['edge'] }),
  v('strung', 'Strung Wire', 'Two barbed strands strung from point to point and sagging between them, like wire run between fence posts.', { wire: ['strung'] }),
  v('coil', 'Coil Edge', 'Razor coil threaded along the top edge in place of a single strand. Denser and meaner.', { wire: ['coil'] }),
  v('tangle', 'Tangled', 'Three loose barbed strands wrapped across the mesh, as if the crown was hastily bound.', { wire: ['tangle'] }),
  v('spikes', 'Wire Spikes', 'Each point extends into a wire spike with a barb knot and a small accent tip. Taller, more antenna than crown.', { tips: 'spikes', wire: ['edge'] }),
  v('sparks', 'Live Points', 'Barbed edge, with the accent balls swapped for sparks, so the crown looks electrified.', { tips: 'sparks', wire: ['edge'] }),
  v('seven', 'Seven Points', 'Seven points instead of five, a finer mesh and a barbed edge. Busier, closer to a real crown.', { peaks: 7, mesh: 40, wire: ['edge'] }),
  v('maximal', 'Fully Wired', 'Barbed edge, strung wire and spikes together. As much wire as the crown can carry.', { tips: 'spikes', wire: ['edge', 'strung'] }),
];
