(function () {
  'use strict';
  const S = window.SpiralOS;
  const { api, el } = S;
  const $ = (id) => document.getElementById(id);
  const view = $('log-view');
  const sourceSel = $('log-source');
  const levelSel = $('log-level');
  const linesSel = $('log-lines');
  const search = $('log-search');
  const follow = $('log-follow');
  const pauseBtn = $('log-pause');
  let paused = false;
  let settings = S.DEFAULT_SETTINGS;
  let seq = 0;

  const fmtTime = (ms) => (ms ? new Date(ms).toLocaleTimeString('en-US', { hour12: false }) : '');

  function render(data) {
    const frag = document.createDocumentFragment();
    if (!data.lines.length) frag.append(el('div', 'log-empty', 'No log lines match.'));
    data.lines.forEach((l) => {
      const row = el('div', 'log-line');
      if (l.time) row.append(el('span', 'log-time', fmtTime(l.time)));
      if (l.level && /^[A-Z]+$/.test(l.level)) row.append(el('span', 'log-level lvl-' + l.level, l.level));
      row.append(el('span', 'log-msg', (l.logger ? l.logger + ': ' : '') + l.message));
      frag.append(row);
    });
    view.replaceChildren(frag);
    if (follow.checked) view.scrollTop = view.scrollHeight;
    $('log-meta').textContent = data.count + ' lines';
  }

  async function refresh() {
    if (paused) return;
    const mine = ++seq;
    const qs = new URLSearchParams({
      source: sourceSel.value || 'app', lines: linesSel.value, level: levelSel.value, q: search.value.trim(),
    });
    try {
      const data = await api('/api/v1/logs?' + qs);
      if (mine === seq) render(data);
    } catch (e) {
      if (e.status === 503 || e.status === 400) {
        view.replaceChildren(el('div', 'log-empty', e.message));
      } else throw e;
    }
  }

  function fail(e) { S.setConnection(false); console.error(e); }

  async function init() {
    settings = await S.loadSettings();
    const sizes = Array.from(new Set([50, 100, 200, 500, 1000, settings.logLines])).sort((a, b) => a - b);
    sizes.forEach((n) => linesSel.append(new Option(n + ' lines', n)));
    linesSel.value = String(settings.logLines);

    try {
      const sources = await api('/api/v1/logs/sources');
      sources.forEach((s) => sourceSel.append(new Option(s.label, s.id)));
    } catch (e) { sourceSel.append(new Option('SpiralOS application', 'app')); }

    sourceSel.addEventListener('change', () => { levelSel.disabled = sourceSel.value !== 'app'; refresh().catch(fail); });
    [levelSel, linesSel].forEach((n) => n.addEventListener('change', () => refresh().catch(fail)));
    let debounce = null;
    search.addEventListener('input', () => { clearTimeout(debounce); debounce = setTimeout(() => refresh().catch(fail), 250); });
    pauseBtn.addEventListener('click', () => {
      paused = !paused;
      pauseBtn.textContent = paused ? 'Resume' : 'Pause';
      if (!paused) refresh().catch(fail);
    });
    S.poll(refresh, () => Math.max(2000, settings.refreshIntervalMs));
  }

  init();
})();
