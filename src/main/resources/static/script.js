// ServerDash telemetry simulation
// Mocks a real-time feed of OSHI-style system metrics: each tick advances
// a short rolling history per metric, which drives both the numeric
// readout and the sparkline trend line.

const HISTORY_LENGTH = 24;
const TICK_MS = 3000;

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

function randomWalk(prev, min, max, maxStep) {
  const step = (Math.random() * 2 - 1) * maxStep;
  return Math.min(max, Math.max(min, prev + step));
}

function pointsFromHistory(history, min, max) {
  const w = 100, h = 28, pad = 2;
  return history
    .map((v, i) => {
      const x = (i / (history.length - 1)) * w;
      const norm = (v - min) / (max - min);
      const y = h - pad - norm * (h - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

function applyStatus(cardEl, flagEl, sparkLineEl, status) {
  const color = STATUS_COLOR[status];
  cardEl.style.setProperty('--status-color', color);
  flagEl.textContent = status === 'good' ? 'nominal' : status === 'warn' ? 'elevated' : 'critical';
}

// ---- state ----
const state = {
  cpu: { history: Array(HISTORY_LENGTH).fill(62), value: 62 },
  ram: { history: Array(HISTORY_LENGTH).fill(81), value: 81 },
  net: { history: Array(HISTORY_LENGTH).fill(5.7), value: 5.7, up: 1.2, down: 4.5 },
};

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

function renderCpu() {
  const s = state.cpu;
  const status = statusFor(s.value, 70, 85);
  cpuValue.innerHTML = `${Math.round(s.value)}<span class="unit">%</span>`;
  cpuSparkLine.setAttribute('points', pointsFromHistory(s.history, 40, 95));
  applyStatus(cpuCard, cpuFlag, cpuSparkLine, status);
}

function renderRam() {
  const s = state.ram;
  const status = statusFor(s.value, 75, 90);
  const gb = (s.value / 100 * 16).toFixed(1);
  ramValue.innerHTML = `${Math.round(s.value)}<span class="unit">%</span>`;
  ramSub.textContent = `${gb} GB / 16.0 GB`;
  ramSparkLine.setAttribute('points', pointsFromHistory(s.history, 55, 98));
  applyStatus(ramCard, ramFlag, ramSparkLine, status);
}

function renderNet() {
  const s = state.net;
  netValue.innerHTML = `${s.value.toFixed(1)}<span class="unit">MB/s</span>`;
  netUp.textContent = `▲ ${s.up.toFixed(1)} MB/s`;
  netDown.textContent = `▼ ${s.down.toFixed(1)} MB/s`;
  netSparkLine.setAttribute('points', pointsFromHistory(s.history, 0, 12));
  // Network has no alert threshold in this build — always neutral/good.
  applyStatus(netCard, netFlag, netSparkLine, 'good');
}

function tick() {
  state.cpu.value = randomWalk(state.cpu.value, 42, 92, 6);
  state.cpu.history.push(state.cpu.value);
  state.cpu.history.shift();

  state.ram.value = randomWalk(state.ram.value, 58, 95, 3);
  state.ram.history.push(state.ram.value);
  state.ram.history.shift();

  state.net.up = Math.max(0.1, randomWalk(state.net.up, 0, 4, 0.5));
  state.net.down = Math.max(0.1, randomWalk(state.net.down, 0, 9, 1));
  state.net.value = state.net.up + state.net.down;
  state.net.history.push(state.net.value);
  state.net.history.shift();

  renderCpu();
  renderRam();
  renderNet();
  jitterProcesses();
}

function jitterProcesses() {
  document.querySelectorAll('#process-body tr').forEach((row) => {
    const base = parseFloat(row.dataset.baseCpu);
    const cell = row.querySelector('.cpu-cell');
    const current = parseFloat(cell.textContent);
    const next = Math.max(0, randomWalk(current, base * 0.4, base * 1.8, base * 0.15 || 0.3));
    cell.textContent = `${next.toFixed(1)}%`;
  });
}

function updateClock() {
  const now = new Date();
  clockEl.textContent = now.toLocaleTimeString('en-US', { hour12: false });
}

// ---- init ----
renderCpu();
renderRam();
renderNet();
updateClock();

setInterval(tick, TICK_MS);
setInterval(updateClock, 1000);
