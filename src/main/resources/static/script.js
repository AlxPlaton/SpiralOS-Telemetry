// SpiralOS dashboard
// Polls the Spring Boot backend for real telemetry and process data,
// and wires up the in-page nav. No mock/random data — everything here
// reflects whatever TelemetryController returns.

const TELEMETRY_URL = '/api/v1/telemetry';
const PROCESSES_URL = '/api/v1/telemetry/processes';
// NOTE: processor.getSystemCpuLoad(1000) on the backend blocks for ~1s per
// call. Polling at 1000ms leaves almost no slack between requests — fine
// under light load, but worth watching for request pileup if the server
// gets busy. Bump this to 2000+ if you see growing latency.
const POLL_MS = 1000;
const HISTORY_LENGTH = 24;

const STATUS_COLOR = {
  good: 'var(--green)',
  warn: 'var(--amber)',
  crit: 'var(--red)',
};

function statusFor(value, warnAt, critAt) {
  if (value >= critAt) return 'crit';
  if (value >= warnAt) return 'warn';
  return 'good';
}

function pointsFromHistory(history, min, max) {
  const w = 100, h = 28, pad = 2;
  if (history.length < 2) return '';
  return history
    .map((v, i) => {
      const x = (i / (history.length - 1)) * w;
      const norm = (v - min) / (max - min);
      const clamped = Math.min(1, Math.max(0, norm));
      const y = h - pad - clamped * (h - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

function applyStatus(cardEl, flagEl, status) {
  const color = STATUS_COLOR[status];
  cardEl.style.setProperty('--status-color', color);
  flagEl.textContent = status === 'good' ? 'nominal' : status === 'warn' ? 'elevated' : 'critical';
}

// Rolling client-side history for the sparklines — the server only gives us
// a point-in-time reading each poll, so we keep the trailing window here.
const history = {
  cpu: [],
  ram: [],
  net: [],
};

function pushHistory(key, value) {
  history[key].push(value);
  if (history[key].length > HISTORY_LENGTH) history[key].shift();
}

// ---- DOM refs ----
const cpuCard = document.getElementById('card-cpu');
const cpuFlag = document.getElementById('cpu-flag');
const cpuValue = document.getElementById('cpu-value');
const cpuSparkLine = document.getElementById('cpu-spark-line');

const ramCard = document.getElementById('card-ram');
const ramFlag = document.getElementById('ram-flag');
const ramValue = document.getElementById('ram-value');
const ramSparkLine = document.getElementById('ram-spark-line');
const ramSub = document.getElementById('ram-sub');

const netCard = document.getElementById('card-net');
const netFlag = document.getElementById('net-flag');
const netValue = document.getElementById('net-value');
const netSparkLine = document.getElementById('net-spark-line');
const netUp = document.getElementById('net-up');
const netDown = document.getElementById('net-down');

const clockEl = document.getElementById('clock');
const statusLine = document.getElementById('status-line');
const processBody = document.getElementById('process-body');
const processSub = document.getElementById('process-sub');

function renderTelemetry(data) {
  // CPU
  const cpuUsage = Number(data.cpuUsage) || 0;
  pushHistory('cpu', cpuUsage);
  cpuValue.innerHTML = `${Math.round(cpuUsage)}<span class="unit">%</span>`;
  cpuSparkLine.setAttribute('points', pointsFromHistory(history.cpu, 0, 100));
  applyStatus(cpuCard, cpuFlag, statusFor(cpuUsage, 70, 85));

  // Memory
  const totalGB = Number(data.totalMemoryGB) || 0;
  const availableGB = Number(data.availableMemoryGB) || 0;
  const usedGB = Math.max(0, totalGB - availableGB);
  const ramPercent = totalGB > 0 ? (usedGB / totalGB) * 100 : 0;
  pushHistory('ram', ramPercent);
  ramValue.innerHTML = `${Math.round(ramPercent)}<span class="unit">%</span>`;
  ramSub.textContent = `${usedGB.toFixed(1)} GB / ${totalGB.toFixed(1)} GB`;
  ramSparkLine.setAttribute('points', pointsFromHistory(history.ram, 0, 100));
  applyStatus(ramCard, ramFlag, statusFor(ramPercent, 75, 90));

  // Network
  const upMBs = Number(data.networkUpMBs) || 0;
  const downMBs = Number(data.networkDownMBs) || 0;
  const totalMBs = upMBs + downMBs;
  pushHistory('net', totalMBs);
  netValue.innerHTML = `${totalMBs.toFixed(2)}<span class="unit">MB/s</span>`;
  netUp.textContent = `▲ ${upMBs.toFixed(2)} MB/s`;
  netDown.textContent = `▼ ${downMBs.toFixed(2)} MB/s`;
  netSparkLine.setAttribute('points', pointsFromHistory(history.net, 0, Math.max(10, totalMBs)));
  applyStatus(netCard, netFlag, 'good');
}

// Maps OSHI's OSProcess.State values (and anything else the backend sends)
// into the three visual buckets the table already knows how to draw.
function statusBucket(rawStatus) {
  const s = String(rawStatus || '').toUpperCase();
  if (s === 'RUNNING') return 'running';
  if (s === 'STOPPED' || s === 'ZOMBIE' || s === 'INVALID') return 'stopped';
  return 'elevated'; // SLEEPING, WAITING, SUSPENDED, OTHER, etc.
}

function statusLabel(rawStatus) {
  const s = String(rawStatus || '');
  if (!s) return 'Unknown';
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}

function renderProcesses(list) {
  if (!Array.isArray(list) || list.length === 0) {
    processBody.innerHTML = '<tr><td colspan="5" class="placeholder-note">No process data returned.</td></tr>';
    return;
  }

  processBody.innerHTML = list
    .map((p) => {
      const bucket = statusBucket(p.status);
      const cpuPercent = Number(p.cpuPercent) || 0;
      const memoryMB = Number(p.memoryMB) || 0;
      return `
        <tr data-pid="${p.pid}">
          <td class="col-pid">${p.pid}</td>
          <td>${escapeHtml(p.name)}</td>
          <td class="col-num cpu-cell">${cpuPercent.toFixed(1)}%</td>
          <td class="col-num">${Math.round(memoryMB)} MB</td>
          <td class="col-status">
            <span class="status-cell ${bucket}"><span class="status-sq"></span>${statusLabel(p.status)}</span>
          </td>
        </tr>`;
    })
    .join('');
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = String(str ?? '');
  return div.innerHTML;
}

function setConnectionState(connected) {
  statusLine.classList.toggle('offline', !connected);
  statusLine.innerHTML = connected
    ? '<span class="status-dot" id="status-dot"></span>[&nbsp;ONLINE&nbsp;]'
    : '<span class="status-dot" id="status-dot"></span>[&nbsp;OFFLINE&nbsp;]';
}

async function poll() {
  try {
    const [telemetryRes, processesRes] = await Promise.all([
      fetch(TELEMETRY_URL),
      fetch(PROCESSES_URL),
    ]);

    if (!telemetryRes.ok || !processesRes.ok) throw new Error('Non-200 response from backend');

    const telemetry = await telemetryRes.json();
    const processes = await processesRes.json();

    renderTelemetry(telemetry);
    renderProcesses(processes);
    processSub.textContent = 'OSHI telemetry · updated every 3s';
    setConnectionState(true);
  } catch (err) {
    console.error('SpiralOS telemetry poll failed:', err);
    processSub.textContent = 'connection lost — retrying…';
    setConnectionState(false);
  }
}

function updateClock() {
  const now = new Date();
  clockEl.textContent = now.toLocaleTimeString('en-US', { hour12: false });
}

// ---- nav wiring: real in-page jumps with active-state tracking ----
function setupNav() {
  const links = Array.from(document.querySelectorAll('#nav-links a'));
  links.forEach((link) => {
    link.addEventListener('click', () => {
      links.forEach((l) => l.classList.remove('active'));
      link.classList.add('active');
    });
  });

  // Keep the active tab in sync with whichever section is actually in view
  // (e.g. when the user scrolls manually rather than clicking a nav link).
  const sections = links
    .map((l) => document.querySelector(l.getAttribute('href')))
    .filter(Boolean);

  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return;
          const match = links.find((l) => l.getAttribute('href') === `#${entry.target.id}`);
          if (match) {
            links.forEach((l) => l.classList.remove('active'));
            match.classList.add('active');
          }
        });
      },
      { rootMargin: '-45% 0px -50% 0px' }
    );
    sections.forEach((s) => observer.observe(s));
  }
}

// ---- init ----
setupNav();
updateClock();
poll();

setInterval(poll, POLL_MS);
setInterval(updateClock, 1000);
