// The teaser soundtrack, synthesised with Web Audio. It renders the whole 30s
// offline into one AudioBuffer, so the player can seek anywhere.
//
//   0 – chaos      radio static and a drone
//   chaos – build  172 BPM two-step breaks, distorted reese bass, a ping per notification
//   build – drop   snare roll and riser, then a beat of silence
//   drop           impact, metal clank as the crown locks on
//   drop – end     half-time, warm pads, plucked arpeggio, chimes as tasks tick off
//   end – 30s      final chord, fade out
window.BF_MUSIC = (() => {
  const { BEAT, BAR, T, notes, checks, unlock } = window.BF_TIMELINE;
  const SR = 44100;
  const STEP = BEAT / 4;
  const mtof = (m) => 440 * 2 ** ((m - 69) / 12);

  function noiseBuffer(ctx, secs) {
    const b = ctx.createBuffer(1, ctx.sampleRate * secs, ctx.sampleRate);
    const d = b.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    return b;
  }

  function impulse(ctx, secs, decay) {
    const n = ctx.sampleRate * secs;
    const b = ctx.createBuffer(2, n, ctx.sampleRate);
    for (let c = 0; c < 2; c++) {
      const d = b.getChannelData(c);
      for (let i = 0; i < n; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / n) ** decay;
    }
    return b;
  }

  function driveCurve(k) {
    const c = new Float32Array(1024);
    for (let i = 0; i < c.length; i++) {
      const x = (i / (c.length - 1)) * 2 - 1;
      c[i] = ((1 + k) * x) / (1 + k * Math.abs(x));
    }
    return c;
  }

  function render() {
    const ctx = new OfflineAudioContext(2, SR * T.length, SR);
    const S = mix(ctx);
    intro(S);
    chaos(S);
    build(S);
    drop(S);
    calm(S);
    return ctx.startRendering();
  }

  function mix(ctx) {
    const master = ctx.createGain();
    master.gain.setValueAtTime(0.62, 0);
    master.gain.setValueAtTime(0.62, T.length - 1.6);
    master.gain.linearRampToValueAtTime(0, T.length);
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -18;
    comp.ratio.value = 6;
    comp.attack.value = 0.002;
    comp.release.value = 0.18;
    // Soft clip after the compressor so peaks round off instead of clipping.
    const limit = ctx.createWaveShaper();
    limit.curve = new Float32Array(1024).map((_, i) => Math.tanh(((i / 1023) * 2 - 1) * 1.4) / Math.tanh(1.4));
    master.connect(comp);
    comp.connect(limit);
    limit.connect(ctx.destination);

    const verb = ctx.createConvolver();
    verb.buffer = impulse(ctx, 3, 2.6);
    const verbIn = ctx.createGain();
    const verbOut = ctx.createGain();
    verbOut.gain.value = 0.4;
    verbIn.connect(verb);
    verb.connect(verbOut);
    verbOut.connect(master);

    const delay = ctx.createDelay(1);
    delay.delayTime.value = BEAT * 0.75;
    const fb = ctx.createGain();
    fb.gain.value = 0.36;
    const delayOut = ctx.createGain();
    delayOut.gain.value = 0.35;
    delay.connect(fb);
    fb.connect(delay);
    delay.connect(delayOut);
    delayOut.connect(master);
    delayOut.connect(verbIn);

    return { ctx, out: master, verb: verbIn, delay, noise: noiseBuffer(ctx, 2), drive: driveCurve(30) };
  }

  // ---- instruments ----

  function noiseHit(S, t, dur, gain, type, freq, q = 0.8, dest = S.out) {
    const { ctx } = S;
    const src = ctx.createBufferSource();
    src.buffer = S.noise;
    src.loop = true;
    const f = ctx.createBiquadFilter();
    f.type = type;
    f.frequency.value = freq;
    f.Q.value = q;
    const g = ctx.createGain();
    g.gain.setValueAtTime(gain, t);
    g.gain.exponentialRampToValueAtTime(0.0008, t + dur);
    src.connect(f);
    f.connect(g);
    g.connect(dest);
    src.start(t, Math.random() * 1.5);
    src.stop(t + dur + 0.02);
    return g;
  }

  function tone(S, t, { type = 'sine', f0, f1 = f0, glide = 0.1, dur, gain, attack = 0.002, dest = S.out }) {
    const { ctx } = S;
    const o = ctx.createOscillator();
    o.type = type;
    o.frequency.setValueAtTime(f0, t);
    if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, t + glide);
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(gain, t + attack);
    g.gain.exponentialRampToValueAtTime(0.0008, t + dur);
    o.connect(g);
    g.connect(dest);
    o.start(t);
    o.stop(t + dur + 0.05);
  }

  const kick = (S, t, gain = 1, tail = 0.38, f0 = 160, f1 = 44) => {
    tone(S, t, { f0, f1, glide: 0.09, dur: tail, gain });
    noiseHit(S, t, 0.015, gain * 0.35, 'highpass', 3500);
  };

  const snare = (S, t, gain = 0.7) => {
    noiseHit(S, t, 0.22, gain, 'bandpass', 1900, 0.7);
    noiseHit(S, t, 0.12, gain * 0.5, 'highpass', 5500);
    tone(S, t, { type: 'triangle', f0: 200, f1: 150, glide: 0.06, dur: 0.1, gain: gain * 0.6 });
  };

  const hat = (S, t, gain = 0.18, open = false) => noiseHit(S, t, open ? 0.18 : 0.035, gain, 'highpass', 8500);

  function reese(S, t, dur, midi, gain = 0.3) {
    const { ctx } = S;
    const f = ctx.createBiquadFilter();
    f.type = 'lowpass';
    f.Q.value = 7;
    f.frequency.setValueAtTime(200, t);
    f.frequency.linearRampToValueAtTime(1500, t + dur * 0.45);
    f.frequency.linearRampToValueAtTime(320, t + dur);
    const sh = ctx.createWaveShaper();
    sh.curve = S.drive;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(gain, t + 0.01);
    g.gain.setValueAtTime(gain, t + dur - 0.04);
    g.gain.linearRampToValueAtTime(0.0001, t + dur);
    for (const detune of [-16, 16]) {
      const o = ctx.createOscillator();
      o.type = 'sawtooth';
      o.frequency.value = mtof(midi);
      o.detune.value = detune;
      o.connect(f);
      o.start(t);
      o.stop(t + dur + 0.02);
    }
    f.connect(sh);
    sh.connect(g);
    g.connect(S.out);
    tone(S, t, { f0: mtof(midi - 12), dur: dur + 0.02, gain: gain * 0.9, attack: 0.01 });
  }

  function pad(S, t, dur, midis, gain = 0.03) {
    const { ctx } = S;
    const f = ctx.createBiquadFilter();
    f.type = 'lowpass';
    f.frequency.value = 1100;
    const g = ctx.createGain();
    g.gain.setValueAtTime(0.0001, t);
    g.gain.linearRampToValueAtTime(gain, t + 0.7);
    g.gain.setValueAtTime(gain, t + dur - 0.2);
    g.gain.linearRampToValueAtTime(0.0001, t + dur + 1.2);
    for (const m of midis) {
      for (const detune of [-8, 8]) {
        const o = ctx.createOscillator();
        o.type = 'sawtooth';
        o.frequency.value = mtof(m);
        o.detune.value = detune;
        o.connect(f);
        o.start(t);
        o.stop(t + dur + 1.3);
      }
    }
    f.connect(g);
    g.connect(S.out);
    g.connect(S.verb);
  }

  const pluck = (S, t, midi, gain = 0.07) => {
    tone(S, t, { type: 'triangle', f0: mtof(midi), dur: 0.32, gain, dest: S.out });
    tone(S, t, { type: 'triangle', f0: mtof(midi), dur: 0.32, gain: gain * 0.8, dest: S.delay });
  };

  function bell(S, t, midi, gain = 0.12) {
    const f = mtof(midi);
    for (const [ratio, g, d] of [[1, 1, 1.6], [2.76, 0.35, 0.8], [5.4, 0.15, 0.4]]) {
      tone(S, t, { f0: f * ratio, dur: d, gain: gain * g });
      tone(S, t, { f0: f * ratio, dur: d, gain: gain * g * 0.6, dest: S.verb });
    }
  }

  const ping = (S, t, n) => {
    const base = [2093, 2637, 1568][n.tone];
    tone(S, t, { f0: base, dur: 0.07, gain: 0.08 });
    tone(S, t + 0.07, { f0: base * 1.5, dur: 0.1, gain: 0.07 });
    tone(S, t, { f0: base, dur: 0.07, gain: 0.05, dest: S.delay });
  };

  // ---- sections ----

  function intro(S) {
    // Detuned radio static sweeping toward the first downbeat.
    const g = noiseHit(S, 0, T.chaos + 0.05, 0.12, 'bandpass', 900, 2);
    g.gain.cancelScheduledValues(0);
    g.gain.setValueAtTime(0.05, 0);
    g.gain.linearRampToValueAtTime(0.22, T.chaos - 0.02);
    g.gain.linearRampToValueAtTime(0.0001, T.chaos);
    tone(S, 0, { type: 'sawtooth', f0: mtof(28), f1: mtof(40), glide: T.chaos, dur: T.chaos, gain: 0.08, attack: 0.4 });
    for (let i = 0; i < 6; i++) tone(S, 0.2 + i * 0.19, { type: 'square', f0: 900 + Math.random() * 1800, dur: 0.04, gain: 0.03 });
  }

  const BASS = [[0, 6, 40], [6, 4, 40], [10, 2, 43], [12, 4, 42], [16, 6, 40], [22, 3, 47], [25, 3, 45], [28, 4, 43]];

  const SCROLL_BASS = [[0, 4, 40], [4, 4, 43], [8, 4, 45], [12, 4, 47]];

  function chaos(S) {
    for (let bar = 1; bar < 8; bar++) {
      const b = T.chaos + (bar - 1) * BAR;
      const act = b < T.scroll ? 'notify' : b < T.time ? 'scroll' : 'time';
      // Two-step: kick on 1 and the "and" of 3, snares on 2 and 4.
      kick(S, b);
      kick(S, b + 10 * STEP, 0.9);
      if (bar % 2 === 0) kick(S, b + 2 * STEP, 0.5);
      snare(S, b + 4 * STEP);
      snare(S, b + 12 * STEP);
      snare(S, b + 7 * STEP, 0.15);
      snare(S, b + 15 * STEP, 0.2);
      hat(S, b + 14 * STEP, 0.12, true);

      if (act === 'notify') {
        for (let s = 0; s < 16; s += 2) hat(S, b + s * STEP, s % 4 === 2 ? 0.2 : 0.12);
      } else if (act === 'scroll') {
        // Rolling sixteenths, extra ghost snares, and a scroll-wheel click
        // that speeds up the longer the feed runs.
        for (let s = 0; s < 16; s++) hat(S, b + s * STEP, s % 4 === 2 ? 0.2 : s % 2 ? 0.07 : 0.12);
        snare(S, b + 9 * STEP, 0.12);
        snare(S, b + 13 * STEP, 0.1);
        const k = (b - T.scroll) / (T.time - T.scroll);
        for (let t = b, gap = STEP * (1 - k * 0.45); t < b + BAR; t += gap) noiseHit(S, t, 0.012, 0.16, 'highpass', 6000);
      } else {
        // Tick-tock on the eighths: the morning running out.
        for (let s = 0; s < 16; s += 2) {
          hat(S, b + s * STEP, 0.1);
          tone(S, b + s * STEP, { f0: s % 4 ? 1100 : 1500, dur: 0.05, gain: 0.12 });
        }
        if (bar === 6) tone(S, b, { type: 'sawtooth', f0: 900, f1: 90, glide: BAR, dur: BAR, gain: 0.05, attack: 0.05 });
      }

      // Glitch stutter at the end of every other bar.
      if (bar % 2 === 0) {
        for (let k = 0; k < 6; k++) tone(S, b + 14 * STEP + k * (STEP / 2), { type: 'square', f0: 300 + Math.random() * 1500, dur: STEP / 2.2, gain: 0.05 });
      }
    }

    // Bass: the two-bar riff, a climbing line under the feed, the riff a
    // semitone up for the last act.
    for (let bar = 1; bar < 8; bar++) {
      const b = T.chaos + (bar - 1) * BAR;
      const scroll = b >= T.scroll && b < T.time;
      const pattern = scroll ? SCROLL_BASS : BASS.filter(([s]) => (bar % 2 ? s < 16 : s >= 16)).map(([s, l, m]) => [s % 16, l, m]);
      const shift = b >= T.time ? 1 : 0;
      for (const [s, len, m] of pattern) reese(S, b + s * STEP, len * STEP, m + shift, bar === 1 ? 0.22 : 0.3);
    }
    reese(S, T.build, BAR / 2, 41, 0.3);

    for (const n of notes) ping(S, n.t, n);
  }

  function build(S) {
    // Snare roll: sixteenths, then thirty-seconds, getting louder.
    for (let k = 0; k < 8; k++) snare(S, T.build + k * STEP, 0.2 + k * 0.03);
    // Ends half a beat early so there's a real gap before the drop.
    for (let k = 0; k < 12; k++) snare(S, T.build + BAR / 2 + k * (STEP / 2), 0.4 + k * 0.04);
    const r = noiseHit(S, T.build, BAR, 0.3, 'highpass', 300, 1);
    r.gain.cancelScheduledValues(T.build);
    r.gain.setValueAtTime(0.03, T.build);
    r.gain.exponentialRampToValueAtTime(0.35, T.drop - BEAT / 2);
    r.gain.linearRampToValueAtTime(0.0001, T.drop - BEAT / 2 + 0.02);
    tone(S, T.build, { type: 'sawtooth', f0: 180, f1: 1400, glide: BAR - BEAT / 2, dur: BAR - BEAT / 2, gain: 0.05, attack: 0.3 });
  }

  function drop(S) {
    const t = T.drop;
    kick(S, t, 1.3, 2.4, 120, 28);
    const crash = noiseHit(S, t, 2.6, 0.35, 'lowpass', 7000, 0.5, S.verb);
    crash.connect(S.out);
    // Metal clank: the crown locks on.
    noiseHit(S, t, 0.35, 0.3, 'bandpass', 3200, 9);
    tone(S, t, { type: 'square', f0: 240, f1: 120, glide: 0.2, dur: 0.3, gain: 0.08 });
    bell(S, t + 0.02, 84, 0.08);
  }

  const CHORDS = [
    [53, 57, 60, 64], // Fmaj7
    [55, 59, 62, 69], // Gadd9
    [57, 60, 64, 67], // Am7
    [55, 59, 60, 64], // Cmaj7/G
    [53, 57, 60, 64, 67], // Fmaj9
    [48, 55, 59, 62, 64], // Cmaj9
  ];

  function calm(S) {
    CHORDS.forEach((chord, i) => {
      const t = T.drop + i * 2 * BAR;
      const dur = i === CHORDS.length - 1 ? T.length - t : 2 * BAR;
      pad(S, t, dur, chord, i === 0 ? 0.03 : 0.045);
      if (t >= T.step1 - BAR) tone(S, t, { f0: mtof(chord[0] - 24), dur: Math.min(dur, 2 * BAR), gain: 0.32, attack: 0.05 });
      // Arpeggio from step 1 on: eighths up and down the chord.
      const tones = [...chord, ...chord.map((m) => m + 12)];
      const order = [0, 2, 4, 3, 5, 7, 6, 4];
      for (let k = 0; k < 16; k++) {
        const at = t + k * (BEAT / 2);
        if (at < T.step1 || at > T.length - 1.4) continue;
        pluck(S, at, tones[order[k % order.length] % tones.length] + 12, 0.075);
      }
    });

    // Half-time drums, soft.
    for (let t = T.drop + BAR; t < T.length - 1.5; t += BAR) {
      kick(S, t, 0.7, 0.3, 110, 46);
      kick(S, t + 10 * STEP, 0.45, 0.25, 110, 46);
      noiseHit(S, t + 8 * STEP, 0.06, 0.45, 'bandpass', 2600, 5);
      for (let s = 0; s < 16; s += 2) hat(S, t + s * STEP + (s % 4 ? STEP * 0.2 : 0), 0.07);
    }

    // Birds.
    for (const t of [T.drop + 2.4, T.drop + 2.65, T.step2 + 1.3]) {
      tone(S, t, { f0: 2900, f1: 4300, glide: 0.06, dur: 0.09, gain: 0.03 });
      tone(S, t + 0.1, { f0: 4100, f1: 3000, glide: 0.07, dur: 0.1, gain: 0.025 });
    }

    // Tasks ticking off, then the fence opening.
    [84, 86, 88, 91].forEach((m, i) => bell(S, checks[i], m, 0.1));
    [72, 76, 79, 83, 86, 88, 91, 95].forEach((m, i) => pluck(S, unlock + i * 0.045, m, 0.05));

    // End card swell.
    bell(S, T.end, 79, 0.06);
    bell(S, T.end + BEAT, 83, 0.05);
  }

  return { render };
})();
