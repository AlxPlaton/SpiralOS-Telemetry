// ServerDash telemetry script connected to Spring Boot /api/v1/telemetry
const HISTORY_LENGTH = 24;
const TICK_MS = 1000; // Poll backend every 1 second

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
  cpu: { history: Array(HISTORY_LENGTH).fill(0), value: 0 },
  ram: { history: Array(HISTORY_LENGTH).fill(0), value: 0, usedGB: 0, totalGB: 16 },
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
  cpuSparkLine.setAttribute('points', pointsFromHistory(s.history, 0, 100));
  applyStatus(cpuCard, cpuFlag, cpuSparkLine, status);
}

function renderRam() {
  const s = state.ram;
  const status = statusFor(s.value, 75, 90);
  ramValue.innerHTML = `${Math.round(s.value)}<span class="unit">%</span>`;
  ramSub.textContent = `${s.usedGB.toFixed(1)} GB / ${s.totalGB.toFixed(1)} GB`;
  ramSparkLine.setAttribute('points', pointsFromHistory(s.history, 0, 100));
  applyStatus(ramCard, ramFlag, ramSparkLine, status);
}

function renderNet() {
  const s = state.net;
  netValue.innerHTML = `${s.value.toFixed(1)}<span class="unit">MB/s</span>`;
  netUp.textContent = `▲ ${s.up.toFixed(1)} MB/s`;
  netDown.textContent = `▼ ${s.down.toFixed(1)} MB/s`;
  netSparkLine.setAttribute('points', pointsFromHistory(s.history, 0, 12));
  applyStatus(netCard, netFlag, netSparkLine, 'good');
}

// ---- Live Telemetry Fetch Loop ----
async function fetchTelemetry() {
  try {
    const response = await fetch('/api/v1/telemetry');
    const data = await response.json();
    
    // Log live payload to console (Crucial for DevTools Screenshot 4)
    console.log('OSHI Telemetry received:', data);

    // 1. Update CPU metric from OSHI
    if (data.cpuUsage !== undefined) {
      state.cpu.value = data.cpuUsage;
      state.cpu.history.push(data.cpuUsage);
      state.cpu.history.shift();
    }

    // 2. Update RAM metric from OSHI
    if (data.totalMemoryGB !== undefined && data.availableMemoryGB !== undefined) {
      const usedGB = data.totalMemoryGB - data.availableMemoryGB;
      const ramPercent = Math.round((usedGB / data.totalMemoryGB) * 100);
      
      state.ram.value = ramPercent;
      state.ram.usedGB = usedGB;
      state.ram.totalGB = data.totalMemoryGB;
      state.ram.history.push(ramPercent);
      state.ram.history.shift();
    }

    // 3. Update Network I/O simulation
    state.net.up = Math.max(0.1, randomWalk(state.net.up, 0, 4, 0.5));
    state.net.down = Math.max(0.1, randomWalk(state.net.down, 0, 9, 1));
    state.net.value = state.net.up + state.net.down;
    state.net.history.push(state.net.value);
    state.net.history.shift();

    renderCpu();
    renderRam();
    renderNet();
    jitterProcesses();
  } catch (error) {
    console.error('Error fetching telemetry from Spring Boot backend:', error);
  }
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

// ---- Initialize ----
updateClock();
setInterval(updateClock, 1000);

// Initial telemetry fetch & polling loop
fetchTelemetry();
setInterval(fetchTelemetry, TICK_MS);