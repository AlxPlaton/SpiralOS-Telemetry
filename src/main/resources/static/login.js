(function () {
  'use strict';
  const m = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  const csrf = document.getElementById('csrf');
  if (m && csrf) csrf.value = decodeURIComponent(m[1]);

  const msg = document.getElementById('login-msg');
  const q = window.location.search;
  if (q.indexOf('locked') !== -1) show('Too many failed attempts. Try again in 15 minutes.');
  else if (q.indexOf('error') !== -1) show('Incorrect username or password.');

  function show(text) { msg.textContent = text; msg.hidden = false; }
})();
