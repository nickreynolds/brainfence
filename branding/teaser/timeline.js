// Shared clock for picture and sound. Everything is placed on a 172 BPM bar
// grid so cuts land on the music. Runs in the browser; defines BF_TIMELINE.
window.BF_TIMELINE = (() => {
  const BPM = 172;
  const BEAT = 60 / BPM;
  const BAR = BEAT * 4;
  const at = (bar) => bar * BAR;

  const T = {
    length: 30,
    chaos: at(1), // drums in: act 1, notifications
    scroll: at(3), // act 2, the endless feed
    time: at(6), // act 3, the morning is gone
    build: at(8), // crown starts to descend
    drop: at(9), // crown lands
    step1: at(9.75),
    step2: at(12.25),
    step3: at(14.75),
    end: at(17.5),
  };

  // Deterministic so scrubbing always shows the same frame.
  let seed = 42;
  const rand = () => (seed = (seed * 16807) % 2147483647) / 2147483647;

  const LABELS = [
    '38 new posts', 'Autoplay in 3…', 'You were mentioned', 'Flash sale −70%',
    '12 unread messages', 'Your streak ends soon', 'Recommended for you', 'Live now',
    'Someone viewed you', 'Limited time only', 'New reply in thread', 'Next episode in 5s',
    'Trending near you', 'Your cart misses you', '3 friends online', 'You have a new match',
  ];
  const KINDS = ['feed', 'video', 'shop', 'chat', 'news', 'game'];

  // Notifications fly at the head in act 1, then come back harder in act 3.
  // Each spawn is quantised to an eighth note; the ping plays on spawn.
  const notes = [];
  for (const [from, to, gapStart, gapEnd] of [[T.chaos + BEAT, T.scroll - 0.4, 0.75, 0.55], [T.time, T.build - 0.5, 0.5, 0.3]]) {
    let t = from;
    while (t < to) {
      const k = (t - from) / (to - from);
      const i = notes.length;
      notes.push({
        i,
        t,
        dur: 1.1 - k * 0.35,
        ang: rand() * Math.PI * 2,
        rot: (rand() - 0.5) * 16,
        label: LABELS[i % LABELS.length],
        kind: KINDS[Math.floor(rand() * KINDS.length)],
        tone: Math.floor(rand() * 3),
      });
      const gap = (gapStart + (gapEnd - gapStart) * k) * (0.7 + rand() * 0.6);
      t = T.chaos + Math.ceil((t + gap - T.chaos) / (BEAT / 2)) * (BEAT / 2);
    }
  }

  // Step 3: the quick tasks tick off, then the morning walk finishes and the
  // fence opens. The walk route draws between walkStart and the last check.
  const walkStart = T.step3 + BEAT * 1.5;
  const checks = [T.step3 + BEAT, T.step3 + BEAT * 2, T.step3 + BEAT * 3, T.step3 + BEAT * 7];
  const unlock = checks[3] + BEAT * 1.5;

  return { BPM, BEAT, BAR, T, notes, checks, unlock, walkStart, LABELS, KINDS };
})();
