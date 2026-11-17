/* Babkin Receptár – UI. Runs inside the Android WebView (window.Android bridge)
   and also in a plain browser (fallback: bundled recipes.json via fetch). */
(function () {
  'use strict';
  var A = window.Android || null;
  var LS = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  };
  var $ = function (id) { return document.getElementById(id); };
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }

  var data = { version: 0, recipes: [] };
  var filter = LS.get('filter', 'Všetko');
  var current = null;          // open recipe
  var stack = ['home'];        // screen history

  /* ---------- text size ---------- */
  function applyScale(pct) {
    pct = Math.max(80, Math.min(200, Math.round(pct / 10) * 10));
    LS.set('scale', pct);
    document.documentElement.style.setProperty('--scale', String(pct / 100));
    $('sizeVal').textContent = pct + ' %';
    $('sizeRange').value = pct;
  }

  /* ---------- recipes ---------- */
  function validRecipes(d) { return d && typeof d === 'object' && Array.isArray(d.recipes); }
  function setData(d) {
    if (!validRecipes(d)) return;
    d.recipes = d.recipes.filter(function (r) { return r && r.id && r.name; });
    data = d;
    var ids = {}; data.recipes.forEach(function (r) { ids[r.id] = 1; });
    LS.set('recent', LS.get('recent', []).filter(function (id) { return ids[id]; }));
    renderHome();
    if (current) { var r = byId(current.id); if (r) { current = r; renderRecipe(); } }
    $('recInfo').textContent = 'Recepty: ' + data.recipes.length + ' (verzia ' + (data.version || 0) + ')';
  }
  function byId(id) { for (var i = 0; i < data.recipes.length; i++) if (data.recipes[i].id === id) return data.recipes[i]; return null; }
  function categories() {
    var seen = {}, out = [];
    data.recipes.forEach(function (r) { var c = r.category; if (c && !seen[c]) { seen[c] = 1; out.push(c); } });
    return out;
  }

  /* ---------- home ---------- */
  function card(r, recent) {
    var b = el('button', 'card' + (recent ? ' recent' : ''));
    b.appendChild(el('span', 'emoji', r.emoji || '🍲'));
    var t = el('span', 'txt');
    t.appendChild(el('span', 'name', r.name));
    var sub = [r.category, r.time].filter(Boolean).join(' · ');
    if (sub) t.appendChild(el('span', 'sub', sub));
    b.appendChild(t);
    b.addEventListener('click', function () { openRecipe(r.id); });
    return b;
  }
  function renderHome() {
    var cats = categories();
    if (filter !== 'Všetko' && cats.indexOf(filter) < 0) filter = 'Všetko';
    var fb = $('filters'); fb.innerHTML = '';
    ['Všetko'].concat(cats).forEach(function (c) {
      var b = el('button', 'chip' + (c === filter ? ' on' : ''), c);
      b.addEventListener('click', function () { filter = c; LS.set('filter', c); renderHome(); });
      fb.appendChild(b);
    });
    $('filters').parentNode.hidden = cats.length < 2;

    var match = function (r) { return filter === 'Všetko' || r.category === filter; };
    var recentIds = LS.get('recent', []).filter(function (id) { var r = byId(id); return r && match(r); }).slice(0, 3);
    var rc = $('recent'); rc.innerHTML = '';
    recentIds.forEach(function (id) { rc.appendChild(card(byId(id), true)); });
    $('recentSec').hidden = recentIds.length === 0;

    var rest = data.recipes.filter(function (r) { return match(r) && recentIds.indexOf(r.id) < 0; })
      .sort(function (a, b) { return a.name.localeCompare(b.name, 'sk'); });
    var al = $('all'); al.innerHTML = '';
    rest.forEach(function (r) { al.appendChild(card(r, false)); });
    $('allTitle').textContent = recentIds.length ? 'Ostatné recepty' : 'Všetky recepty';
    $('allTitle').hidden = rest.length === 0 && recentIds.length > 0;
    $('empty').hidden = data.recipes.length > 0;
  }

  /* ---------- recipe ---------- */
  function checks(id) { return LS.get('checks:' + id, { i: {}, s: {} }); }
  function openRecipe(id) {
    current = byId(id); if (!current) return;
    var rec = LS.get('recent', []).filter(function (x) { return x !== id; });
    rec.unshift(id); LS.set('recent', rec.slice(0, 20));
    renderRecipe();
    show('recipe');
    window.scrollTo(0, 0);
    if (A && A.keepScreenOn) A.keepScreenOn(true);
  }
  function checkRow(text, on, num, onTap) {
    var b = el('button', 'check' + (on ? ' done' : ''));
    var box = el('span', 'box', on ? '✓' : '');
    b.appendChild(box);
    var t = el('span', 't');
    if (num) t.appendChild(el('span', 'num', num + '.'));
    t.appendChild(document.createTextNode(text));
    b.appendChild(t);
    b.addEventListener('click', onTap);
    return b;
  }
  function renderRecipe() {
    var r = current, st = checks(r.id);
    $('rTitle').textContent = (r.emoji ? r.emoji + ' ' : '') + r.name;
    $('rMeta').textContent = [r.servings, r.time].filter(Boolean).join(' · ');
    $('rMeta').hidden = !$('rMeta').textContent;
    var ul = $('rIngr'); ul.innerHTML = '';
    (r.ingredients || []).forEach(function (txt, i) {
      var li = el('li');
      if (/^#\s*/.test(txt)) { li.className = 'group'; li.textContent = txt.replace(/^#\s*/, ''); }
      else li.appendChild(checkRow(txt, !!st.i[i], 0, function () { toggle('i', i); }));
      ul.appendChild(li);
    });
    var ol = $('rSteps'); ol.innerHTML = '';
    var n = 0;
    (r.steps || []).forEach(function (txt, i) {
      var li = el('li');
      if (/^#\s*/.test(txt)) { li.className = 'group'; li.textContent = txt.replace(/^#\s*/, ''); }
      else { n++; li.appendChild(checkRow(txt, !!st.s[i], n, function () { toggle('s', i); })); }
      ol.appendChild(li);
    });
    $('rNote').hidden = !r.note; $('rNote').textContent = r.note ? '💡 ' + r.note : '';
  }
  function toggle(kind, i) {
    var st = checks(current.id);
    if (st[kind][i]) delete st[kind][i]; else st[kind][i] = 1;
    LS.set('checks:' + current.id, st);
    var y = window.scrollY; renderRecipe(); window.scrollTo(0, y);
    if (A && A.vibrate) A.vibrate(20);
  }

  /* ---------- navigation ---------- */
  function show(name) {
    ['home', 'recipe', 'settings'].forEach(function (s) { $(s).hidden = s !== name; });
    if (stack[stack.length - 1] !== name) stack.push(name);
  }
  // Called by Android back button. Returns true if handled.
  window.goBack = function () {
    if (!$('confirm').hidden) { $('confirm').hidden = true; return true; }
    if (stack.length <= 1) return false;
    var leaving = stack.pop();
    if (leaving === 'recipe') { current = null; if (A && A.keepScreenOn) A.keepScreenOn(false); renderHome(); }
    ['home', 'recipe', 'settings'].forEach(function (s) { $(s).hidden = s !== stack[stack.length - 1]; });
    window.scrollTo(0, 0);
    return true;
  };

  function ask(text, yes) {
    $('confirmText').textContent = text; $('confirm').hidden = false;
    $('confirmYes').onclick = function () { $('confirm').hidden = true; yes(); };
    $('confirmNo').onclick = function () { $('confirm').hidden = true; };
  }

  /* ---------- updates (callbacks from Android) ---------- */
  window.onRecipesUpdated = function (json) { try { setData(JSON.parse(json)); } catch (e) {} };
  window.onUpdateAvailable = function (versionName, whatsNew) {
    $('updateText').textContent = 'Je pripravená nová verzia aplikácie' + (versionName ? ' (' + versionName + ')' : '') + '.' + (whatsNew ? ' ' + whatsNew : '');
    $('updateBanner').hidden = false;
    $('checkMsg').textContent = 'Nová verzia je pripravená.';
  };
  window.onUpdateStatus = function (msg) { $('checkMsg').textContent = msg; $('updateText').textContent = msg; };
  window.onNoUpdate = function () { $('checkMsg').textContent = 'Všetko je aktuálne.'; };

  /* ---------- boot ---------- */
  function boot() {
    applyScale(LS.get('scale', 100));
    $('btnSettings').addEventListener('click', function () { show('settings'); window.scrollTo(0, 0); });
    Array.prototype.forEach.call(document.querySelectorAll('[data-back]'), function (b) { b.addEventListener('click', window.goBack); });
    $('sizeMinus').addEventListener('click', function () { applyScale(LS.get('scale', 100) - 10); });
    $('sizePlus').addEventListener('click', function () { applyScale(LS.get('scale', 100) + 10); });
    $('sizeRange').addEventListener('input', function () { applyScale(+this.value); });
    $('btnReset').addEventListener('click', function () {
      ask('Odškrtnúť všetko v tomto recepte?', function () { LS.set('checks:' + current.id, { i: {}, s: {} }); renderRecipe(); window.scrollTo(0, 0); });
    });
    $('btnUpdate').addEventListener('click', function () { if (A) A.startUpdate(); });
    $('btnCheck').addEventListener('click', function () {
      $('checkMsg').textContent = 'Hľadám…';
      if (A) { A.refreshRecipes(); A.checkUpdate(true); } else $('checkMsg').textContent = 'Len v aplikácii.';
    });
    $('verInfo').textContent = 'Verzia aplikácie: ' + (A ? A.appVersion() : 'web');

    var t0 = Date.now();
    var done = function () {
      var wait = Math.max(0, 1800 - (Date.now() - t0)); // let the spoon stir a bit
      setTimeout(function () {
        show('home'); $('splash').className = 'out';
        setTimeout(function () { $('splash').hidden = true; }, 500);
      }, wait);
    };
    if (A) {
      try { setData(JSON.parse(A.getRecipes())); } catch (e) {}
      done();
      A.refreshRecipes();
      A.checkUpdate(false);
    } else {
      fetch('recipes.json').then(function (r) { return r.json(); }).then(setData).catch(function () {}).then(done);
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
