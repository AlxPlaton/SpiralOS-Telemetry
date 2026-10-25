(function () {
  'use strict';
  const S = window.SpiralOS;
  const { api, el } = S;
  const $ = (id) => document.getElementById(id);

  const COLUMNS = [
    { key: 'pid', label: 'Pid', cls: 'col-pid', sort: true },
    { key: 'name', label: 'Name', cls: '', sort: true },
    { key: 'user', label: 'User', cls: 'col-user', sort: false },
    { key: 'cpu', label: 'CPU %', cls: 'col-num', sort: true },
    { key: 'memory', label: 'Memory', cls: 'col-num', sort: true },
    { key: 'threads', label: 'Threads', cls: 'col-narrow', sort: true },
    { key: 'status', label: 'Status', cls: 'col-status', sort: false },
  ];
  const state = { sort: 'cpu', order: 'desc', q: '' };
  let settings = S.DEFAULT_SETTINGS;
  let seq = 0;

  function buildHeader() {
    const head = $('proc-head');
    COLUMNS.forEach((c) => {
      const th = el('th', c.cls);
      th.dataset.key = c.key;
      if (c.sort) {
        const b = el('button', 'th-btn', c.label);
        b.type = 'button';
        b.addEventListener('click', () => {
          if (state.sort === c.key) state.order = state.order === 'desc' ? 'asc' : 'desc';
          else { state.sort = c.key; state.order = c.key === 'name' ? 'asc' : 'desc'; }
          markSort();
          refresh().catch(fail);
        });
        th.append(b);
      } else {
        th.textContent = c.label;
      }
      head.append(th);
    });
    markSort();
  }

  function markSort() {
    document.querySelectorAll('#proc-head th').forEach((th) => {
      if (th.dataset.key === state.sort) th.setAttribute('aria-sort', state.order === 'asc' ? 'ascending' : 'descending');
      else th.removeAttribute('aria-sort');
    });
  }

  function stat(id, value, unit) {
    const n = $(id);
    n.textContent = value;
    if (unit) n.append(el('span', 'unit', unit));
  }

  function render(d) {
    stat('v-total', d.totalCpuPercent.toFixed(1), '%');
    stat('v-system', d.systemCpuPercent.toFixed(1), '%');
    stat('v-procs', d.processCount);
    stat('v-threads', d.threadCount);
    $('stat-total').style.setProperty('--status-color',
      S.COLORS[S.levelFor(d.totalCpuPercent, settings.cpuWarnPercent, settings.cpuCritPercent)]);
    $('stat-system').style.setProperty('--status-color',
      S.COLORS[S.levelFor(d.systemCpuPercent, settings.cpuWarnPercent, settings.cpuCritPercent)]);
    $('proc-sub').textContent = 'showing ' + d.processes.length + ' of ' + d.matched + ' matching';

    const body = $('process-body');
    if (!d.processes.length) {
      const tr = el('tr');
      const td = el('td', 'placeholder-note', 'No processes match.');
      td.colSpan = COLUMNS.length;
      tr.append(td);
      body.replaceChildren(tr);
      return;
    }
    body.replaceChildren(...d.processes.map((p) => {
      const tr = el('tr');
      tr.append(
        el('td', 'col-pid', p.pid), el('td', '', p.name), el('td', 'col-user', p.user),
        el('td', 'col-num', p.cpuPercent.toFixed(1) + '%'),
        el('td', 'col-num', Math.round(p.memoryMB) + ' MB'),
        el('td', 'col-narrow', p.threads), S.statusCell(p.status)
      );
      return tr;
    }));
  }

  async function refresh() {
    const mine = ++seq;
    const qs = new URLSearchParams({ sort: state.sort, order: state.order, limit: settings.processPageRows, q: state.q });
    const data = await api('/api/v1/processes?' + qs);
    if (mine === seq) render(data);
  }

  function fail(e) { S.setConnection(false); console.error(e); }

  buildHeader();
  let debounce = null;
  $('proc-search').addEventListener('input', (e) => {
    clearTimeout(debounce);
    debounce = setTimeout(() => { state.q = e.target.value.trim(); refresh().catch(fail); }, 250);
  });
  S.loadSettings().then((s) => { settings = s; S.poll(refresh, () => settings.refreshIntervalMs); });
})();
