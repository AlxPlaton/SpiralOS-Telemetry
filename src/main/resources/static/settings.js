(function () {
  'use strict';
  const S = window.SpiralOS;
  const { api, el } = S;
  const $ = (id) => document.getElementById(id);

  // [key, label, min, max, step, hint]  (ranges are enforced again by the server)
  const GROUPS = [
    { title: 'Refresh', fields: [
      ['refreshIntervalMs', 'Refresh interval (ms)', 1000, 60000, 500, 'How often the dashboard, processes and logs pages poll the server.'],
    ] },
    { title: 'Alert thresholds (%)', fields: [
      ['cpuWarnPercent', 'CPU warning at', 1, 100, 1, 'Card turns amber.'],
      ['cpuCritPercent', 'CPU critical at', 1, 100, 1, 'Card turns red. Must be above the warning level.'],
      ['ramWarnPercent', 'Memory warning at', 1, 100, 1, ''],
      ['ramCritPercent', 'Memory critical at', 1, 100, 1, ''],
    ] },
    { title: 'Display', fields: [
      ['dashboardProcessRows', 'Dashboard process rows', 3, 20, 1, ''],
      ['processPageRows', 'Processes page max rows', 10, 500, 10, ''],
      ['logLines', 'Default log lines', 50, 1000, 50, ''],
    ] },
  ];

  const form = $('settings-form');
  const msg = $('settings-msg');

  function say(text, kind) {
    msg.textContent = text;
    msg.className = 'form-msg ' + (kind || '');
  }

  function build() {
    GROUPS.forEach((g) => {
      const set = el('fieldset');
      set.append(el('legend', '', g.title));
      g.fields.forEach(([key, label, min, max, step, hint]) => {
        const row = el('div', 'field');
        const lab = el('label', '', label);
        lab.htmlFor = 'f-' + key;
        const input = el('input');
        Object.assign(input, { id: 'f-' + key, name: key, type: 'number', min, max, step, required: true });
        row.append(lab, input);
        if (hint) row.append(el('div', 'hint', hint));
        set.append(row);
      });
      form.append(set);
    });
    const actions = el('div', 'form-actions');
    const save = el('button', 'btn btn-primary', 'Save settings');
    save.type = 'submit';
    const reset = el('button', 'btn', 'Reset to defaults');
    reset.type = 'button';
    reset.addEventListener('click', onReset);
    actions.append(save, reset);
    form.append(actions);
  }

  function fill(s) {
    GROUPS.forEach((g) => g.fields.forEach(([key]) => { $('f-' + key).value = s[key]; }));
  }

  async function onSave(e) {
    e.preventDefault();
    const body = {};
    GROUPS.forEach((g) => g.fields.forEach(([key]) => { body[key] = Number($('f-' + key).value); }));
    try {
      fill(await api('/api/v1/settings', { method: 'PUT', body }));
      say('Saved. Other pages pick this up the next time they load.', 'ok');
    } catch (err) { say(err.message, 'error'); }
  }

  async function onReset() {
    try {
      fill(await api('/api/v1/settings/reset', { method: 'POST' }));
      say('Defaults restored.', 'ok');
    } catch (err) { say(err.message, 'error'); }
  }

  build();
  form.addEventListener('submit', onSave);
  S.loadSettings().then(fill);
  api('/api/v1/auth/me').then((me) => { $('whoami').textContent = 'Signed in as ' + me.username + '.'; }).catch(() => {});
})();
