(() => {
  const data = JSON.parse(document.getElementById('brand-data').textContent);
  const url = (svg) => 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  const store = { get(k) { try { return localStorage.getItem(k); } catch { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch {} } };
  const byId = Object.fromEntries(data.marks.map((m) => [m.id, m]));
  const palById = Object.fromEntries(data.palettes.map((p) => [p.id, p]));
  const cache = new Map();
  const src = (svg) => { if (!cache.has(svg)) cache.set(svg, url(svg)); return cache.get(svg); };

  const state = { round: store.get('bf-round'), pal: store.get('bf-pal'), hero: store.get('bf-hero'), scheme: store.get('bf-scheme') };
  const round = () => data.rounds.find((r) => r.id === state.round);
  // Keep the palette and lockup choice valid for the current round.
  function settle() {
    if (!round()) state.round = data.rounds[data.rounds.length - 1].id;
    const r = round();
    if (!r.palettes.includes(state.pal)) state.pal = r.palettes[0];
    if (!r.marks.includes(state.hero)) state.hero = r.marks[0];
  }

  const roundsEl = document.getElementById('rounds');
  for (const r of data.rounds) {
    const b = document.createElement('button');
    b.className = 'round'; b.type = 'button'; b.id = 'round-' + r.id; b.dataset.round = r.id;
    b.setAttribute('role', 'tab');
    b.innerHTML = r.title + '<b>' + r.subtitle + '</b>';
    b.addEventListener('click', () => { state.round = r.id; store.set('bf-round', r.id); build(); render(true); });
    roundsEl.appendChild(b);
  }

  const pals = document.getElementById('pals');
  const marks = document.getElementById('marks');

  function palChip(p) {
    const b = document.createElement('button');
    b.className = 'pal'; b.type = 'button'; b.id = 'pal-' + p.id;
    b.setAttribute('role', 'radio'); b.dataset.pal = p.id;
    b.innerHTML = '<span class="sw">' + [p.bg, p.ink, p.foil[1], p.accent, p.split[0], p.split[1]].map((c) => '<i style="background:' + c + '"></i>').join('') + '</span>' + p.name;
    b.addEventListener('click', () => { state.pal = p.id; store.set('bf-pal', p.id); render(true); });
    return b;
  }

  function markCard(m) {
    const mono = 'url("' + url(m.mono) + '")';
    const img = (v, alt) => '<img data-mark="' + m.id + '" data-v="' + v + '" alt="' + alt + '">';
    const glyphs = '<span class="glyph"></span><span class="glyph lg"></span>';
    const el = document.createElement('article');
    el.className = 'mark';
    el.innerHTML =
      '<img class="poster" data-mark="' + m.id + '" data-v="poster" alt="' + m.name + ' poster">' +
      '<div class="info"><h3>' + m.name + '</h3><p>' + m.pitch + '</p>' +
      '<div class="previews">' +
        '<figure><div class="mac">' + img('icon', '') + '</div><figcaption>macOS</figcaption></figure>' +
        '<figure><div class="droid">' + img('icon', '') + '</div><figcaption>Android</figcaption></figure>' +
        '<figure><div class="themed"><span class="glyph"></span></div><figcaption>Themed</figcaption></figure>' +
        '<figure><div class="task">' + img('icon', '') + '</div><figcaption>32px</figcaption></figure>' +
        '<figure><div class="trays"><div class="bar dark">' + glyphs + '</div><div class="bar light">' + glyphs + '</div></div><figcaption>Tray 16 / 24</figcaption></figure>' +
      '</div>' +
      '<button class="use" type="button" id="use-' + m.id + '" data-hero-btn="' + m.id + '">Use in lockups</button></div>';
    for (const g of el.querySelectorAll('.glyph')) g.style.setProperty('--m', mono);
    el.querySelector('.use').addEventListener('click', () => { state.hero = m.id; store.set('bf-hero', m.id); render(false); });
    return el;
  }

  // Round 4 swaps the icon concepts for the app palette directions.
  const schemeBtns = [...document.querySelectorAll('[data-scheme-btn]')];
  const schemeEls = [...document.querySelectorAll('[data-scheme]')];
  if (!schemeEls.some((el) => el.dataset.scheme === state.scheme)) state.scheme = schemeEls[0] && schemeEls[0].dataset.scheme;
  function showScheme() {
    for (const el of schemeEls) el.hidden = el.dataset.scheme !== state.scheme;
    for (const b of schemeBtns) b.setAttribute('aria-checked', String(b.dataset.schemeBtn === state.scheme));
  }
  for (const b of schemeBtns) b.addEventListener('click', () => { state.scheme = b.dataset.schemeBtn; store.set('bf-scheme', state.scheme); showScheme(); });
  showScheme();

  function build() {
    settle();
    const r = round();
    const palette = r.kind === 'palette';
    document.getElementById('icon-concepts').hidden = palette;
    document.getElementById('app-palette').hidden = !palette;
    pals.replaceChildren(...r.palettes.map((id) => palChip(palById[id])));
    marks.replaceChildren(...r.marks.map((id) => markCard(byId[id])));
    document.getElementById('round-note').textContent = r.note;
    for (const b of roundsEl.children) b.setAttribute('aria-selected', String(b.dataset.round === r.id));
  }

  function render(glitch) {
    const p = palById[state.pal];
    const s = document.documentElement.style;
    s.setProperty('--bg', p.bg); s.setProperty('--panel', p.bg2); s.setProperty('--fg', p.ink);
    s.setProperty('--accent', p.accent); s.setProperty('--hot', p.split[0]); s.setProperty('--cold', p.split[1]);
    s.setProperty('color-scheme', p.light ? 'light' : 'dark');
    document.getElementById('pal-note').textContent = p.note;
    for (const b of pals.children) b.setAttribute('aria-checked', String(b.dataset.pal === p.id));
    for (const img of document.querySelectorAll('[data-mark]')) img.src = src(byId[img.dataset.mark].svg[p.id][img.dataset.v]);
    for (const img of document.querySelectorAll('[data-type]')) img.src = src(data.type[p.id][img.dataset.type]);
    for (const img of document.querySelectorAll('[data-hero]')) img.src = src(byId[state.hero].svg[p.id][img.dataset.hero]);
    for (const b of document.querySelectorAll('[data-hero-btn]')) b.setAttribute('aria-pressed', String(b.dataset.heroBtn === state.hero));
    document.getElementById('hero-name').textContent = byId[state.hero].name;
    if (glitch) { document.body.classList.remove('glitch'); void document.body.offsetWidth; document.body.classList.add('glitch'); }
  }

  build();
  render(false);
})();
