// SpiralOS trend charts: a small canvas line chart with no third-party library,
// so the strict Content-Security-Policy (script-src 'self') stays untouched.
// Draws time series, dashed threshold lines and alert markers, with a hover readout.
(function () {
  'use strict';

  const PAD = { top: 10, right: 10, bottom: 20, left: 38 };
  const GAP_MS = 15000; // break the line where samples are missing (server restart, hidden tab)

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function hhmm(t, withSeconds) {
    return new Date(t).toLocaleTimeString('en-US', {
      hour12: false, hour: '2-digit', minute: '2-digit', second: withSeconds ? '2-digit' : undefined,
    });
  }

  function niceMax(v) {
    if (v <= 1) return 1;
    const mag = Math.pow(10, Math.floor(Math.log10(v)));
    for (const step of [1, 2, 2.5, 5, 10]) {
      if (step * mag >= v) return step * mag;
    }
    return 10 * mag;
  }

  // series: [{ key, colorVar, label, unit }]; options: { yMax: number | 'auto', readout: element }
  function TrendChart(canvas, series, options) {
    this.canvas = canvas;
    this.series = series;
    this.opts = options;
    this.points = [];
    this.windowMs = 15 * 60000;
    this.thresholds = [];
    this.markers = [];
    this.hover = null;
    this.ctx = canvas.getContext('2d');

    canvas.addEventListener('mousemove', (e) => {
      const r = canvas.getBoundingClientRect();
      this.hover = e.clientX - r.left;
      this.draw();
    });
    canvas.addEventListener('mouseleave', () => { this.hover = null; this.draw(); });
    if (window.ResizeObserver) new ResizeObserver(() => this.draw()).observe(canvas);
  }

  TrendChart.prototype.set = function (data) {
    Object.assign(this, data);
    this.draw();
  };

  TrendChart.prototype.draw = function () {
    const c = this.canvas;
    const w = c.clientWidth;
    const h = c.clientHeight;
    if (!w || !h) return;
    const dpr = window.devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    const ctx = this.ctx;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const pts = this.points;
    const end = pts.length ? pts[pts.length - 1].t : Date.now();
    const start = end - this.windowMs;
    let yMax = this.opts.yMax;
    if (yMax === 'auto') {
      let m = 0;
      pts.forEach((p) => this.series.forEach((s) => { m = Math.max(m, p[s.key]); }));
      yMax = niceMax(m * 1.15);
    }
    const plotW = w - PAD.left - PAD.right;
    const plotH = h - PAD.top - PAD.bottom;
    const x = (t) => PAD.left + ((t - start) / (end - start)) * plotW;
    const y = (v) => PAD.top + plotH - (Math.min(v, yMax) / yMax) * plotH;

    const faint = cssVar('--line');
    const dim = cssVar('--text-faint');
    ctx.font = '10px ' + cssVar('--mono');
    ctx.lineWidth = 1;

    // grid + y labels
    ctx.fillStyle = dim;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (let i = 0; i <= 4; i++) {
      const v = (yMax / 4) * i;
      const yy = Math.round(y(v)) + 0.5;
      ctx.strokeStyle = faint;
      ctx.beginPath(); ctx.moveTo(PAD.left, yy); ctx.lineTo(w - PAD.right, yy); ctx.stroke();
      ctx.fillText(yMax <= 10 ? v.toFixed(1) : Math.round(v), PAD.left - 6, yy);
    }

    // time labels
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (let i = 0; i <= 4; i++) {
      const t = start + ((end - start) / 4) * i;
      ctx.textAlign = i === 0 ? 'left' : i === 4 ? 'right' : 'center';
      ctx.fillText(hhmm(t, this.windowMs <= 5 * 60000), x(t), h - PAD.bottom + 6);
    }

    // threshold lines
    ctx.setLineDash([4, 4]);
    this.thresholds.forEach((th, i) => {
      if (th.value > yMax) return;
      const yy = Math.round(y(th.value)) + 0.5;
      ctx.strokeStyle = cssVar(th.colorVar);
      ctx.globalAlpha = 0.55;
      ctx.beginPath(); ctx.moveTo(PAD.left, yy); ctx.lineTo(w - PAD.right, yy); ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.fillStyle = cssVar(th.colorVar);
      ctx.textBaseline = 'bottom';
      // alternate sides so two nearby thresholds (e.g. 85% and 90%) don't print on top of each other
      ctx.textAlign = i % 2 ? 'left' : 'right';
      ctx.fillText(th.label, i % 2 ? PAD.left + 4 : w - PAD.right - 2, yy - 2);
    });
    ctx.setLineDash([]);

    // alert markers: a short tick at the top of the plot where each alert was raised
    this.markers.forEach((m) => {
      if (m.t < start || m.t > end) return;
      const xx = Math.round(x(m.t)) + 0.5;
      ctx.strokeStyle = cssVar(m.level === 'CRITICAL' ? '--red' : '--amber');
      ctx.globalAlpha = 0.35;
      ctx.beginPath(); ctx.moveTo(xx, PAD.top); ctx.lineTo(xx, PAD.top + plotH); ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.fillStyle = ctx.strokeStyle;
      ctx.beginPath(); ctx.moveTo(xx - 4, PAD.top); ctx.lineTo(xx + 4, PAD.top); ctx.lineTo(xx, PAD.top + 6); ctx.fill();
    });

    // series
    ctx.lineWidth = 1.6;
    ctx.lineJoin = 'round';
    this.series.forEach((s) => {
      ctx.strokeStyle = cssVar(s.colorVar);
      ctx.beginPath();
      let prev = null;
      pts.forEach((p) => {
        if (p.t < start) return;
        if (prev === null || p.t - prev > GAP_MS) ctx.moveTo(x(p.t), y(p[s.key]));
        else ctx.lineTo(x(p.t), y(p[s.key]));
        prev = p.t;
      });
      ctx.stroke();
    });

    // hover crosshair + readout
    const readout = this.opts.readout;
    if (this.hover === null || !pts.length) {
      if (readout) readout.textContent = pts.length ? '' : 'collecting data…';
      return;
    }
    const t = start + ((this.hover - PAD.left) / plotW) * (end - start);
    let best = null;
    pts.forEach((p) => { if (p.t >= start && (!best || Math.abs(p.t - t) < Math.abs(best.t - t))) best = p; });
    if (!best) return;
    const xx = Math.round(x(best.t)) + 0.5;
    ctx.strokeStyle = cssVar('--text-dim');
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(xx, PAD.top); ctx.lineTo(xx, PAD.top + plotH); ctx.stroke();
    this.series.forEach((s) => {
      ctx.fillStyle = cssVar(s.colorVar);
      ctx.beginPath(); ctx.arc(xx, y(best[s.key]), 3, 0, Math.PI * 2); ctx.fill();
    });
    if (readout) {
      readout.textContent = hhmm(best.t, true) + '  ' + this.series
        .map((s) => s.label + ' ' + (s.unit === '%' ? Math.round(best[s.key]) + '%' : best[s.key].toFixed(2) + s.unit))
        .join('  ');
    }
  };

  window.SpiralOS.TrendChart = TrendChart;
})();
