(function () {
  'use strict';
  const S = window.SpiralOS;
  const { api, el } = S;
  const HISTORY = 24;
  const FLAG = { good: 'nominal', warn: 'elevated', crit: 'critical' };
  const $ = (id) => document.getElementById(id);
  const hist = { cpu: [], ram: [], net: [] };
  let settings = S.DEFAULT_SETTINGS;

  function push(key, v) {
    hist[key].push(v);
    if (hist[key].length > HISTORY) hist[key].shift();
  }

  function points(h, min, max) {
    if (h.length < 2) return '';
    return h.map((v, i) => {
      const x = (i / (h.length - 1)) * 100;
      const n = Math.min(1, Math.max(0, (v - min) / (max - min)));
      return x.toFixed(1) + ',' + (26 - n * 24).toFixed(1);
    }).join(' ');
  }

  function setValue(node, number, unit) {
    node.textContent = number;
    node.append(el('span', 'unit', unit));
  }

  function paint(cardId, flagId, status) {
    $(cardId).style.setProperty('--status-color', S.COLORS[status]);
    $(flagId).textContent = FLAG[status];
  }

  function renderTelemetry(t) {
    const cpu = Number(t.cpuUsage) || 0;
    push('cpu', cpu);
    setValue($('cpu-value'), Math.round(cpu), '%');
    $('cpu-spark-line').setAttribute('points', points(hist.cpu, 0, 100));
    $('cpu-sub').textContent = t.logicalCores + ' logical cores · processes total ' + (Number(t.totalProcessCpuPercent) || 0).toFixed(1) + '%';
    paint('card-cpu', 'cpu-flag', S.levelFor(cpu, settings.cpuWarnPercent, settings.cpuCritPercent));

    const total = Number(t.totalMemoryGB) || 0;
    const used = Math.max(0, total - (Number(t.availableMemoryGB) || 0));
    const pct = total > 0 ? (used / total) * 100 : 0;
    push('ram', pct);
    setValue($('ram-value'), Math.round(pct), '%');
    $('ram-sub').textContent = used.toFixed(1) + ' GB / ' + total.toFixed(1) + ' GB';
    $('ram-spark-line').setAttribute('points', points(hist.ram, 0, 100));
    paint('card-ram', 'ram-flag', S.levelFor(pct, settings.ramWarnPercent, settings.ramCritPercent));

    const up = Number(t.networkUpMBs) || 0;
    const down = Number(t.networkDownMBs) || 0;
    push('net', up + down);
    setValue($('net-value'), (up + down).toFixed(2), 'MB/s');
    $('net-up').textContent = '▲ ' + up.toFixed(2) + ' MB/s';
    $('net-down').textContent = '▼ ' + down.toFixed(2) + ' MB/s';
    $('net-spark-line').setAttribute('points', points(hist.net, 0, Math.max(1, ...hist.net)));
    paint('card-net', 'net-flag', 'good');
  }

  function renderProcesses(list) {
    const body = $('process-body');
    if (!list.length) {
      const tr = el('tr');
      const td = el('td', 'placeholder-note', 'No process data yet.');
      td.colSpan = 5;
      tr.append(td);
      body.replaceChildren(tr);
      return;
    }
    body.replaceChildren(...list.map((p) => {
      const tr = el('tr');
      tr.append(
        el('td', 'col-pid', p.pid), el('td', '', p.name),
        el('td', 'col-num', p.cpuPercent.toFixed(1) + '%'),
        el('td', 'col-num', Math.round(p.memoryMB) + ' MB'),
        S.statusCell(p.status)
      );
      return tr;
    }));
  }

  // ---- Trend charts (server keeps 60 min of history; we load it once, then append) ----
  const pctChart = new S.TrendChart($('chart-pct'), [
    { key: 'cpu', colorVar: '--series-cpu', label: 'CPU', unit: '%' },
    { key: 'ram', colorVar: '--series-ram', label: 'Mem', unit: '%' },
  ], { yMax: 100, readout: $('pct-readout') });
  const netChart = new S.TrendChart($('chart-net'), [
    { key: 'net', colorVar: '--series-net', label: 'Net', unit: ' MB/s' },
  ], { yMax: 'auto', readout: $('net-readout') });
  let trendMinutes = 15;
  let trend = [];
  let events = [];

  async function loadHistory() {
    const h = await api('/api/v1/telemetry/history?minutes=' + trendMinutes);
    trend = h.points;
  }

  function appendPoint(t) {
    const total = Number(t.totalMemoryGB) || 0;
    const p = {
      t: Number(t.timestamp),
      cpu: Number(t.cpuUsage) || 0,
      ram: total > 0 ? Math.max(0, total - (Number(t.availableMemoryGB) || 0)) / total * 100 : 0,
      net: (Number(t.networkUpMBs) || 0) + (Number(t.networkDownMBs) || 0),
    };
    if (!trend.length || p.t > trend[trend.length - 1].t) trend.push(p);
    const cutoff = p.t - trendMinutes * 60000;
    while (trend.length && trend[0].t < cutoff) trend.shift();
  }

  function drawTrends() {
    const windowMs = trendMinutes * 60000;
    const marks = (metrics) => events.filter((e) => metrics.includes(e.metric)).map((e) => ({ t: e.raisedAt, level: e.level }));
    pctChart.set({
      points: trend, windowMs, markers: marks(['cpu', 'ram']),
      thresholds: [
        { value: settings.cpuCritPercent, colorVar: '--series-cpu', label: 'CPU crit ' + settings.cpuCritPercent + '%' },
        { value: settings.ramCritPercent, colorVar: '--series-ram', label: 'Mem crit ' + settings.ramCritPercent + '%' },
      ],
    });
    netChart.set({ points: trend, windowMs, markers: marks(['net']), thresholds: [] });
  }

  document.querySelectorAll('.range-btn').forEach((btn) => {
    btn.addEventListener('click', async () => {
      trendMinutes = Number(btn.dataset.minutes);
      document.querySelectorAll('.range-btn').forEach((b) => b.setAttribute('aria-pressed', String(b === btn)));
      try { await loadHistory(); } catch (e) { console.error(e); }
      drawTrends();
    });
  });

  // ---- Alerts ----
  const clock = (t) => new Date(t).toLocaleTimeString('en-US', { hour12: false });

  function duration(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    return s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + (s % 60) + 's';
  }

  function renderAlerts(a) {
    events = a.recent;
    const banner = $('alert-banner');
    if (a.active.length) {
      const crit = a.active.some((x) => x.level === 'CRITICAL');
      banner.className = 'alert-banner' + (crit ? ' crit' : '');
      banner.textContent = '▲ ' + a.active.length + (a.active.length === 1 ? ' active alert: ' : ' active alerts: ')
        + a.active.map((x) => x.message).join(' · ');
      banner.hidden = false;
    } else {
      banner.hidden = true;
    }

    const list = $('alert-list');
    if (!a.recent.length) {
      list.replaceChildren(el('li', 'placeholder-note',
        'No alerts yet. Thresholds come from Settings; spikes are compared with the last 5 minutes.'));
      return;
    }
    list.replaceChildren(...a.recent.slice(0, 10).map((e) => {
      const li = el('li', 'alert-item');
      const open = e.clearedAt === null;
      li.append(
        el('span', 'alert-level ' + e.level, e.level.toLowerCase()),
        el('span', 'alert-time', clock(e.raisedAt)),
        el('span', 'alert-msg', e.message),
        el('span', 'alert-state' + (open ? ' open' : ''),
          open ? 'active ' + duration(Date.now() - e.raisedAt) : 'cleared after ' + duration(e.clearedAt - e.raisedAt))
      );
      return li;
    }));
  }

  async function refresh() {
    const [t, p, a] = await Promise.all([
      api('/api/v1/telemetry'),
      api('/api/v1/processes?sort=cpu&order=desc&limit=' + settings.dashboardProcessRows),
      api('/api/v1/alerts'),
    ]);
    renderTelemetry(t);
    renderProcesses(p.processes);
    renderAlerts(a);
    // reload history after a gap (e.g. the tab was hidden and polling paused)
    if (!trend.length || Number(t.timestamp) - trend[trend.length - 1].t > 15000) await loadHistory();
    appendPoint(t);
    drawTrends();
  }

  S.loadSettings().then((s) => { settings = s; S.poll(refresh, () => settings.refreshIntervalMs); });
})();
