// SpiralOS shared client helpers: CSRF-aware API wrapper, topbar, clock,
// connection badge, settings cache and a safe polling loop.
// Everything is rendered with textContent / DOM nodes, never innerHTML,
// so server data (process names, log lines) can't inject markup.
(function () {
  'use strict';

  const NAV = [
    ['Dashboard', '/index.html'],
    ['Processes', '/processes.html'],
    ['Logs', '/logs.html'],
    ['Settings', '/settings.html'],
  ];

  const DEFAULT_SETTINGS = {
    refreshIntervalMs: 2000, cpuWarnPercent: 70, cpuCritPercent: 85,
    ramWarnPercent: 75, ramCritPercent: 90, dashboardProcessRows: 8,
    processPageRows: 100, logLines: 200,
  };

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function readCookie(name) {
    const m = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return m ? decodeURIComponent(m[1]) : null;
  }

  async function api(path, options) {
    const opts = options || {};
    const method = (opts.method || 'GET').toUpperCase();
    const headers = { Accept: 'application/json' };
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET') {
      const token = readCookie('XSRF-TOKEN');
      if (token) headers['X-XSRF-TOKEN'] = token;
    }
    const res = await fetch(path, {
      method, headers, credentials: 'same-origin', cache: 'no-store',
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    });
    if (res.status === 401) {
      window.location.href = '/login.html';
      throw new Error('SESSION_EXPIRED');
    }
    const text = await res.text();
    let data = null;
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) {
      const msg = res.status === 403
        ? 'Request rejected by the security check. Reload the page and try again.'
        : (data && data.error) || 'Request failed (' + res.status + ')';
      const err = new Error(msg);
      err.status = res.status;
      throw err;
    }
    return data;
  }

  let statusEl = null;
  let clockEl = null;

  function setConnection(online) {
    if (!statusEl) return;
    statusEl.classList.toggle('offline', !online);
    statusEl.replaceChildren(
      el('span', 'status-dot'),
      document.createTextNode('[\u00a0' + (online ? 'ONLINE' : 'OFFLINE') + '\u00a0]')
    );
  }

  async function logout() {
    try { await api('/logout', { method: 'POST' }); } catch (e) { /* fall through to login */ }
    window.location.href = '/login.html';
  }

  function buildTopbar() {
    const host = document.getElementById('topbar');
    if (!host) return;
    const here = window.location.pathname === '/' ? '/index.html' : window.location.pathname;

    const brand = el('div', 'brand');
    brand.append(el('span', 'brand-name', 'SpiralOS'), el('span', 'brand-version', 'v1.0'));

    const nav = el('nav', 'nav-links');
    NAV.forEach(([label, href]) => {
      const a = el('a', here === href ? 'active' : '', label);
      a.href = href;
      if (here === href) a.setAttribute('aria-current', 'page');
      nav.append(a);
    });

    const cluster = el('div', 'status-cluster');
    clockEl = el('time', 'clock', '--:--:--');
    statusEl = el('span', 'status-line');
    const out = el('button', 'btn-link', 'Log out');
    out.type = 'button';
    out.addEventListener('click', logout);
    cluster.append(clockEl, statusEl, out);

    host.append(brand, nav, cluster);
    setConnection(true);
    const tick = () => { clockEl.textContent = new Date().toLocaleTimeString('en-US', { hour12: false }); };
    tick();
    setInterval(tick, 1000);
  }

  async function loadSettings() {
    try { return await api('/api/v1/settings'); } catch (e) { return DEFAULT_SETTINGS; }
  }

  // Runs fn now and then every getIntervalMs() after it finishes. Never overlaps
  // itself, pauses while the tab is hidden, and resumes right away when it returns.
  function poll(fn, getIntervalMs) {
    let timer = null;
    let running = false;
    async function tick() {
      if (running) return;
      running = true;
      if (!document.hidden) {
        try { await fn(); setConnection(true); }
        catch (e) { if (e.message !== 'SESSION_EXPIRED') { setConnection(false); console.error(e); } }
      }
      running = false;
      timer = setTimeout(tick, Math.max(1000, getIntervalMs()));
    }
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) { clearTimeout(timer); tick(); }
    });
    tick();
  }

  const levelFor = (v, warn, crit) => (v >= crit ? 'crit' : v >= warn ? 'warn' : 'good');
  const COLORS = { good: 'var(--green)', warn: 'var(--amber)', crit: 'var(--red)' };

  function statusCell(raw) {
    const s = String(raw || '').toUpperCase();
    const bucket = s === 'RUNNING' ? 'running' : (s === 'STOPPED' || s === 'ZOMBIE' || s === 'INVALID') ? 'stopped' : 'elevated';
    const label = s ? s.charAt(0) + s.slice(1).toLowerCase() : 'Unknown';
    const td = el('td', 'col-status');
    const wrap = el('span', 'status-cell ' + bucket);
    wrap.append(el('span', 'status-sq'), document.createTextNode(label));
    td.append(wrap);
    return td;
  }

  window.SpiralOS = { api, el, setConnection, loadSettings, poll, statusCell, levelFor, COLORS, DEFAULT_SETTINGS };
  buildTopbar();
})();
