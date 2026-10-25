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

  async function refresh() {
    const [t, p] = await Promise.all([
      api('/api/v1/telemetry'),
      api('/api/v1/processes?sort=cpu&order=desc&limit=' + settings.dashboardProcessRows),
    ]);
    renderTelemetry(t);
    renderProcesses(p.processes);
  }

  S.loadSettings().then((s) => { settings = s; S.poll(refresh, () => settings.refreshIntervalMs); });
})();
