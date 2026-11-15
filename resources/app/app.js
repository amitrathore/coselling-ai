/* app.coselling.ai — the 5Cs flywheel for a game you host.
   The GM does the maths (coselling-ai.flywheel); this file draws it. */
(() => {
  const KEY = (k) => `coselling.app.${k}`;
  const store = {
    get(k) { try { return localStorage.getItem(KEY(k)); } catch (_) { return null; } },
    set(k, v) { try { localStorage.setItem(KEY(k), v); } catch (_) {} },
    drop(k) { try { localStorage.removeItem(KEY(k)); } catch (_) {} },
  };
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => [...el.querySelectorAll(s)];
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const params = new URLSearchParams(location.search);
  const S = { authcfg: null, games: [], game: null, range: '30d', model: null, selected: null };
  const RANGES = ['7d', '30d', '90d', 'all'];
  const RANGE_WORDS = { '7d': 'the last 7 days', '30d': 'the last 30 days', '90d': 'the last 90 days', all: 'all time' };
  const PRIOR_WORDS = { '7d': 'prior 7 days', '30d': 'prior 30 days', '90d': 'prior 90 days' };

  /* The auth app returns via /auth/success?token=…, which the GM bounces to
     "/?token=…". Take it and strip it from the URL. */
  function captureToken() {
    const t = params.get('token');
    if (!t) return;
    store.set('token', t);
    params.delete('token'); params.delete('provider');
    syncUrl();
  }
  const auth = () => { const t = store.get('token'); return t ? { Authorization: 'Bearer ' + t } : {}; };
  const signInUrl = () => {
    const b = S.authcfg?.auth_base_url;
    return b ? b.replace(/\/$/, '') + '/?config=' + encodeURIComponent(location.origin + '/auth.json') : '';
  };
  function syncUrl() {
    const q = params.toString();
    history.replaceState({}, '', location.pathname + (q ? '?' + q : ''));
  }

  async function api(url) {
    let r;
    try { r = await fetch(url, { headers: auth() }); } catch (_) {
      return { ok: false, status: 0, body: { message: "Couldn't reach the app. Check your connection." } };
    }
    let body = null;
    try { body = await r.json(); } catch (_) {}
    return { ok: r.ok, status: r.status, body };
  }

  // --- formatting -----------------------------------------------------------
  const nf = new Intl.NumberFormat();
  function money(minor, compact) {
    if (minor == null) return '—';
    const cur = (S.model?.currency || 'usd').toUpperCase();
    const v = minor / 100;
    try {
      return new Intl.NumberFormat(undefined, {
        style: 'currency', currency: cur,
        notation: compact && Math.abs(v) >= 10000 ? 'compact' : 'standard',
        maximumFractionDigits: Math.abs(v) >= 100 || compact ? 0 : 2,
      }).format(v);
    } catch (_) { return nf.format(v) + ' ' + cur; }
  }
  function fmt(m, compact) {
    if (!m || m.value == null) return '—';
    const v = m.value;
    switch (m.format) {
      case 'money': return money(v, compact);
      case 'percent': return (v * 100 < 10 && v > 0 ? (v * 100).toFixed(1) : Math.round(v * 100)) + '%';
      case 'multiple': return v.toFixed(1) + '×';
      case 'number': return v.toFixed(1);
      default: return compact && v >= 10000 ? new Intl.NumberFormat(undefined, { notation: 'compact' }).format(v) : nf.format(v);
    }
  }
  /* Change against the prior period. Rates move in points, everything else in %. */
  function delta(m) {
    if (!m || m.value == null || m.prior == null) return '';
    if (m.format === 'percent') {
      const pp = Math.round((m.value - m.prior) * 100);
      return pp === 0 ? 'no change' : (pp > 0 ? '▲ ' : '▼ ') + Math.abs(pp) + ' pts';
    }
    if (m.prior === 0) return m.value > 0 ? '▲ new' : 'no change';
    const pct = Math.round(((m.value - m.prior) / Math.abs(m.prior)) * 100);
    return pct === 0 ? 'no change' : (pct > 0 ? '▲ ' : '▼ ') + Math.abs(pct) + '%';
  }
  const pct = (r) => (r == null ? '—' : Math.round(r * 100) + '%');
  const cName = (id) => S.model?.cs.find((c) => c.id === id)?.name || id;

  // --- views ----------------------------------------------------------------
  function show(view) {
    $$('[data-view]').forEach((el) => { el.hidden = el.dataset.view !== view; });
    $('[data-controls]').hidden = !['board', 'loading', 'error'].includes(view) || !S.games.length;
  }
  function showError(title, text) {
    $('[data-error-title]').textContent = title;
    $('[data-error-text]').textContent = text || '';
    show('error');
  }
  function setHero(title, meta) {
    const h = $('[data-title]');
    if (title) h.textContent = title; else h.innerHTML = 'See your network <em>compound.</em>';
    $('[data-meta]').textContent = meta || '';
  }

  // --- the wheel ------------------------------------------------------------
  const W = 640, CX = 320, CY = 320, R = 218, NODE = 70;
  const angle = (i) => (-90 + i * 72) * Math.PI / 180;
  const at = (a, r = R) => [CX + r * Math.cos(a), CY + r * Math.sin(a)];

  function wheelSvg(m) {
    const cs = m.cs;
    const b = m.bottleneck || {};
    const nodeGap = (NODE + 14) / R; // radians a node covers on the ring
    let arcs = '', pills = '', nodes = '';
    m.handoffs.forEach((h, i) => {
      const a0 = angle(i) + nodeGap, a1 = angle(i + 1) - nodeGap;
      const [x0, y0] = at(a0), [x1, y1] = at(a1);
      const isB = b.handoff === h.id;
      const cls = (isB ? ' is-bottleneck' : '') + (h.disabled ? ' is-off' : '');
      arcs += `<path class="arc${cls}" d="M${x0.toFixed(1)} ${y0.toFixed(1)} A${R} ${R} 0 0 1 ${x1.toFixed(1)} ${y1.toFixed(1)}"/>`;
      // Arrowhead at the arc's end, pointing along the tangent.
      const t = a1 + Math.PI / 2, s = isB ? 11 : 8;
      const p1 = [x1 - s * Math.cos(t) + s * 0.6 * Math.cos(t - Math.PI / 2), y1 - s * Math.sin(t) + s * 0.6 * Math.sin(t - Math.PI / 2)];
      const p2 = [x1 - s * Math.cos(t) - s * 0.6 * Math.cos(t - Math.PI / 2), y1 - s * Math.sin(t) - s * 0.6 * Math.sin(t - Math.PI / 2)];
      arcs += `<path class="arrow${isB ? ' is-bottleneck' : ''}" d="M${x1.toFixed(1)} ${y1.toFixed(1)} L${p1[0].toFixed(1)} ${p1[1].toFixed(1)} L${p2[0].toFixed(1)} ${p2[1].toFixed(1)} Z"/>`;
      const label = (isB ? '⚠ ' : '') + (h.disabled ? 'off' : pct(h.rate)) + (h.approx && h.rate != null ? '*' : '');
      const [px, py] = at((angle(i) + angle(i + 1)) / 2, R);
      const w = 14 + label.length * 8;
      pills += `<g class="pill${isB ? ' is-bottleneck' : ''}" data-handoff="${esc(h.id)}"><rect x="${(px - w / 2).toFixed(1)}" y="${(py - 14).toFixed(1)}" width="${w}" height="28" rx="14"/><text x="${px.toFixed(1)}" y="${(py + 5).toFixed(1)}" text-anchor="middle">${esc(label)}</text><rect class="hit" x="${(px - w / 2 - 8).toFixed(1)}" y="${(py - 22).toFixed(1)}" width="${w + 16}" height="44"/></g>`;
    });
    cs.forEach((c, i) => {
      const [x, y] = at(angle(i));
      const off = !c.enabled;
      const value = off ? 'Off' : fmt(c.headline, true);
      const d = off ? 'not enabled' : delta(c.headline);
      nodes += `<g class="node c-${c.id}${off ? ' is-off' : ''}${S.selected === c.id ? ' is-selected' : ''}" data-c="${c.id}" tabindex="0" role="button" aria-label="${esc(c.name)}: ${esc(off ? 'not enabled' : (c.headline?.label + ' ' + value))}">
        <circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${NODE}" fill="var(--c-${c.id})"/>
        <text class="c-name" x="${x.toFixed(1)}" y="${(y - 18).toFixed(1)}">${esc(c.name)}</text>
        <text class="c-value" x="${x.toFixed(1)}" y="${(y + 12).toFixed(1)}">${esc(value)}</text>
        <text class="c-delta" x="${x.toFixed(1)}" y="${(y + 32).toFixed(1)}">${esc(d)}</text>
      </g>`;
    });
    const centerTitle = b.handoff ? `${cName(b.from)} → ${cName(b.to)}` : b.c ? cName(b.c) : 'Loop in motion';
    const centerSub = b.handoff ? 'is the weakest handoff' : b.c ? 'is where the loop starts' : 'no clear bottleneck';
    return `<svg class="wheel" viewBox="0 0 ${W} ${W}" role="group" aria-label="The 5Cs flywheel for ${esc(m.game?.name || '')}">
      <circle class="ring" cx="${CX}" cy="${CY}" r="${R - 58}"/>
      ${arcs}
      <g class="center">
        <g class="sun-g"><text class="sun" x="${CX}" y="${CY - 22}" aria-hidden="true">✺</text></g>
        <text class="c-title" x="${CX}" y="${CY + 22}">${esc(centerTitle)}</text>
        <text class="c-sub" x="${CX}" y="${CY + 44}">${esc(centerSub)}</text>
      </g>
      ${pills}
      ${nodes}
    </svg>`;
  }

  function loopList(m) {
    const b = m.bottleneck || {};
    return m.cs.map((c, i) => {
      const h = m.handoffs[i];
      const isB = b.handoff === h.id;
      const row = `<li class="c-row${S.selected === c.id ? ' is-selected' : ''}" data-c="${c.id}" tabindex="0" role="button">
        <span class="dot c-${c.id}" style="background:var(--c-${c.id})">${i + 1}</span>
        <span><span class="row-name">${esc(c.name)}</span><br><span class="row-label">${esc(c.enabled ? c.headline?.label : 'Not enabled')}</span></span>
        <span class="row-value">${esc(c.enabled ? fmt(c.headline, true) : 'Off')}<small>${esc(c.enabled ? delta(c.headline) : '')}</small></span></li>`;
      const hand = `<li class="handoff${isB ? ' is-bottleneck' : ''}"><span aria-hidden="true">↓</span><b>${isB ? '⚠ ' : ''}${esc(h.disabled ? 'off' : pct(h.rate))}</b><span>${esc(h.label)}${h.approx ? ' (approx.)' : ''}</span></li>`;
      return row + hand;
    }).join('');
  }

  // --- tooltip ----------------------------------------------------------------
  let tip;
  function tooltip(html, evt) {
    const wrap = $('[data-wheel]');
    if (!tip) { tip = document.createElement('div'); tip.className = 'tooltip'; tip.setAttribute('role', 'tooltip'); wrap.appendChild(tip); }
    if (!html) { tip.hidden = true; return; }
    tip.innerHTML = html; tip.hidden = false;
    const r = wrap.getBoundingClientRect();
    const x = Math.min(evt.clientX - r.left + 14, r.width - 270), y = evt.clientY - r.top + 14;
    tip.style.left = Math.max(0, x) + 'px'; tip.style.top = y + 'px';
  }
  function handoffTip(id) {
    const h = S.model.handoffs.find((x) => x.id === id);
    if (!h) return '';
    const prior = h.prior != null ? `<br>${esc(PRIOR_WORDS[S.range] || 'prior')}: ${pct(h.prior)}` : '';
    const basis = h.n != null ? `<br>out of ${nf.format(h.n)}` : '';
    const target = h.target != null ? `<br>healthy: ${pct(h.target)}+` : '';
    return `<b>${esc(cName(h.from))} → ${esc(cName(h.to))}</b>${esc(h.label)}: ${h.disabled ? 'not measured' : pct(h.rate)}${basis}${prior}${target}${h.approx ? '<br>* approximate' : ''}`;
  }

  // --- side panels ----------------------------------------------------------
  function insightHtml(m) {
    const b = m.bottleneck;
    if (!b) return `<p class="kicker">Insight</p><h2>The loop is turning.</h2><p>Every handoff we can measure is at a healthy level in ${esc(RANGE_WORDS[m.range])}, and none is falling. Keep giving cosellers things worth sharing.</p>`;
    const flag = b.kind === 'falling' ? 'Falling handoff' : b.kind === 'dormant' ? 'Start here' : 'Bottleneck';
    const rate = b.rate != null
      ? `<p class="insight-rate">${pct(b.rate)} ${esc(m.handoffs.find((h) => h.id === b.handoff)?.label.toLowerCase() || '')}${b.prior != null ? ` · was ${pct(b.prior)}` : ''}${b.target != null ? ` · healthy is ${pct(b.target)}+` : ''}</p>`
      : '';
    return `<span class="flag">⚠ ${esc(flag)}</span><h2>${esc(b.title)}</h2><p>${esc(b.move)}</p>${rate}`;
  }

  function signalsDl(c) {
    return `<dl class="signals">${c.signals.map((s) => `<div><dt>${esc(s.label)}</dt><dd>${esc(fmt(s))}${s.of != null ? ` <small class="delta">of ${nf.format(s.of)}</small>` : ''} <span class="delta">${esc(delta(s))}</span></dd></div>`).join('')}</dl>`;
  }

  function extras(c) {
    const m = S.model;
    switch (c.id) {
      case 'content': {
        const posts = (c['top-posts'] || []).filter((p) => p.votes || p.comments || p.touches || p['published-at']);
        return posts.length
          ? `<p class="kicker">Top posts</p><ul class="list">${posts.map((p) => `<li><span>${esc(p.title)}</span><span>${p.votes} votes · ${p.comments} comments · ${p.touches} shares landed</span></li>`).join('')}</ul>`
          : '<p class="note">No posts yet. What cosellers share starts here.</p>';
      }
      case 'community': {
        const n = c.network || {};
        return `<p class="kicker">Referral network</p><ul class="list">
          <li><span>Members who joined through someone</span><span>${nf.format(n.referred || 0)}</span></li>
          <li><span>People who brought someone in</span><span>${nf.format(n.recruiters || 0)}</span></li>
          <li><span>Average brought in, per recruiter</span><span>${n['recruits-per-recruiter'] == null ? '—' : n['recruits-per-recruiter'].toFixed(1)}</span></li>
          <li><span>Deepest referral chain</span><span>${nf.format(n['max-depth'] || 0)} ${n['max-depth'] === 1 ? 'hop' : 'hops'}</span></li></ul>`;
      }
      case 'commerce':
        return (c['other-currencies'] || []).length
          ? `<p class="note">Figures are in ${esc(m.currency.toUpperCase())}. Sales in ${esc(c['other-currencies'].map((x) => x.toUpperCase()).join(', '))} aren't converted or added in.</p>` : '';
      case 'coselling': {
        const top = c['top-cosellers'] || [];
        const a = c.attribution || {};
        const rule = `Credit follows the person: everyone who reached a buyer in the ${a['window-days'] || 30} days before the purchase shares equally${a['override-bps'] ? `, and each coseller passes ${a['override-bps'] / 100}% of their share up to whoever brought them in` : ''}. Buyers pay the same either way.`;
        return (top.length
          ? `<p class="kicker">Most active cosellers</p><ul class="list">${top.map((t) => `<li><span>@${esc(t.coseller)}</span><span>${nf.format(t.reach)} shares landed · ${money(t.earned)} shared${t.override ? ` (${money(t.override)} from recruits)` : ''}</span></li>`).join('')}</ul>`
          : '<p class="note" style="margin-bottom:14px">No one has shared in this range.</p>') + `<p class="note">${esc(rule)}</p>`;
      }
      case 'campaigns': {
        const up = c.upcoming || [];
        return (up.length
          ? `<p class="kicker">Coming up</p><ul class="list">${up.map((u) => `<li><span>${esc(u.title)}</span><span>${new Date(u['starts-at']).toLocaleDateString()}</span></li>`).join('')}</ul>`
          : '') + '<p class="note">Campaigns come from the Attention calendar. Lift compares shares and joins on campaign days with the other days in the range.</p>';
      }
      default: return '';
    }
  }

  function drillHtml(id) {
    const m = S.model;
    const i = m.cs.findIndex((c) => c.id === id);
    const c = m.cs[i];
    const into = m.handoffs[(i + 4) % 5], out = m.handoffs[i];
    const head = `<div class="drill-head"><span class="dot" style="background:var(--c-${c.id})"></span><h2>${esc(c.name)}</h2></div>`;
    if (!c.enabled) {
      const proto = { content: 'Attention', commerce: 'Market', coselling: 'Coseller', campaigns: 'Attention' }[c.id];
      return head + `<p class="off">This game doesn't run the ${esc(proto)} protocol, so there's nothing to measure here yet.</p>`;
    }
    const flow = `<div class="flow"><div>Fed by ${esc(cName(into.from))}<b>${esc(into.disabled ? '—' : pct(into.rate))}</b>${esc(into.label)}</div><div>Feeds ${esc(cName(out.to))}<b>${esc(out.disabled ? '—' : pct(out.rate))}</b>${esc(out.label)}</div></div>`;
    return head + `<p class="headline-num">${esc(fmt(c.headline))}</p>
      <p class="headline-label">${esc(c.headline.label)} · ${esc(RANGE_WORDS[m.range])}${c.headline.of != null ? ` · of ${nf.format(c.headline.of)}` : ''} <span class="delta">${esc(delta(c.headline))}</span></p>
      ${signalsDl(c)}${flow}${extras(c)}`;
  }

  function tableHtml(m) {
    const hasPrior = m.range !== 'all';
    const rows = m.cs.map((c) => {
      const head = `<tr class="c-group"><td colspan="${hasPrior ? 4 : 2}">${esc(c.name)}</td></tr>`;
      if (!c.enabled) return head + `<tr><td>Not enabled</td><td class="num">—</td>${hasPrior ? '<td class="num">—</td><td class="num"></td>' : ''}</tr>`;
      return head + [c.headline, ...c.signals].map((s) => `<tr><td>${esc(s.label)}</td><td class="num">${esc(fmt(s))}</td>${hasPrior ? `<td class="num">${esc(fmt({ ...s, value: s.prior }))}</td><td class="num">${esc(delta(s))}</td>` : ''}</tr>`).join('');
    }).join('');
    const hand = m.handoffs.map((h) => `<tr><td>${esc(cName(h.from))} → ${esc(cName(h.to))}: ${esc(h.label)}${h.approx ? ' (approx.)' : ''}</td><td class="num">${esc(h.disabled ? '—' : pct(h.rate))}</td>${hasPrior ? `<td class="num">${esc(pct(h.prior))}</td><td class="num"></td>` : ''}</tr>`).join('');
    return `<table><thead><tr><th>Signal</th><th class="num">${esc(RANGE_WORDS[m.range])}</th>${hasPrior ? `<th class="num">${esc(PRIOR_WORDS[m.range])}</th><th class="num">Change</th>` : ''}</tr></thead>
      <tbody>${rows}<tr class="c-group"><td colspan="${hasPrior ? 4 : 2}">Handoffs</td></tr>${hand}</tbody></table>`;
  }

  function render() {
    const m = S.model;
    if (!S.selected) S.selected = m.bottleneck?.to || m.bottleneck?.c || 'coselling';
    $('[data-wheel]').innerHTML = wheelSvg(m);
    tip = null;
    $('[data-loop-list]').innerHTML = loopList(m);
    $('[data-insight]').innerHTML = insightHtml(m);
    $('[data-drill]').innerHTML = drillHtml(S.selected);
    $('[data-table]').innerHTML = tableHtml(m);
    const observed = m['observed-at'] ? new Date(m['observed-at']) : null;
    setHero(m.game?.name || m.game?.['game-name'],
      `${RANGE_WORDS[m.range][0].toUpperCase() + RANGE_WORDS[m.range].slice(1)}${observed ? ' · read ' + observed.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : ''} · * approximate`);
    show('board');
  }

  function select(id) {
    S.selected = id;
    $$('[data-c]').forEach((el) => el.classList.toggle('is-selected', el.dataset.c === id));
    $('[data-drill]').innerHTML = drillHtml(id);
  }

  // --- loading ----------------------------------------------------------------
  async function loadFlywheel() {
    if (!S.game) return;
    $$('.ranges button').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.range === S.range)));
    if (!S.model) show('loading');
    const r = await api(`/api/app/games/${encodeURIComponent(S.game)}/flywheel?range=${encodeURIComponent(S.range)}`);
    if (r.status === 401) { store.drop('token'); return boot(); }
    if (!r.ok) {
      S.model = null;
      setHero(S.games.find((g) => g['game-name'] === S.game)?.name, '');
      return showError(r.status === 403 ? "That game isn't yours." : "We couldn't load the flywheel.", r.body?.message);
    }
    S.model = r.body;
    render();
  }

  async function boot() {
    captureToken();
    S.authcfg = await (async () => { try { const r = await fetch('/auth.json'); return r.ok ? r.json() : null; } catch (_) { return null; } })();
    if (!store.get('token')) { setHero(null, ''); return show('signed-out'); }
    show('loading');
    const [games, me] = await Promise.all([api('/api/app/games'), api('/auth/me')]);
    if (games.status === 401) { store.drop('token'); setHero(null, ''); return show('signed-out'); }
    if (!games.ok) return showError("We couldn't load your games.", games.body?.message);
    S.games = games.body.games || [];
    const who = me.ok ? (me.body?.email || me.body?.user?.email || '') : '';
    $('[data-who]').textContent = who;
    if (!S.games.length) { setHero(null, ''); return show('no-games'); }
    const wanted = params.get('game') || store.get('game');
    S.game = S.games.some((g) => g['game-name'] === wanted) ? wanted : S.games[0]['game-name'];
    const wantedRange = params.get('range') || store.get('range');
    S.range = RANGES.includes(wantedRange) ? wantedRange : '30d';
    switcherLabel(); pushRecent(S.game);
    remember();
    await loadFlywheel();
  }
  function remember() {
    store.set('game', S.game); store.set('range', S.range);
    params.set('game', S.game); params.set('range', S.range); syncUrl();
  }

  // --- game switcher ----------------------------------------------------------
  /* Hosts can run dozens of games, so the switcher is a filter box over the
     list, with the games you opened last pinned on top. */
  const RECENT_MAX = 5;
  const gameLabel = (g) => g.name || g['game-name'];
  function recent() {
    try { const r = JSON.parse(store.get('recent') || '[]'); return Array.isArray(r) ? r : []; } catch (_) { return []; }
  }
  function pushRecent(name) {
    store.set('recent', JSON.stringify([name, ...recent().filter((n) => n !== name)].slice(0, RECENT_MAX)));
  }
  function switcherLabel() {
    const g = S.games.find((x) => x['game-name'] === S.game);
    $('[data-game-label]').textContent = g ? gameLabel(g) : '';
  }
  /* Name or handle, case-insensitive; names that start with the query first. */
  function matches(q) {
    const n = q.trim().toLowerCase();
    const hit = (g) => [gameLabel(g), g['game-name']].map((v) => String(v).toLowerCase());
    return S.games
      .map((g) => ({ g, at: Math.min(...hit(g).map((v) => { const i = v.indexOf(n); return i < 0 ? Infinity : i === 0 ? 0 : 1; })) }))
      .filter((x) => x.at !== Infinity)
      .sort((a, b) => a.at - b.at)
      .map((x) => x.g);
  }
  const SW = { open: false, items: [], active: 0 };
  function optionHtml(g, i) {
    const name = g['game-name'];
    const sub = gameLabel(g) !== name ? `<small>${esc(name)}</small>` : '';
    return `<li role="option" id="game-opt-${i}" data-game="${esc(name)}" aria-selected="${name === S.game}"${i === SW.active ? ' class="active"' : ''}><span>${esc(gameLabel(g))}</span>${sub}</li>`;
  }
  function drawList() {
    const q = $('#game-filter').value;
    const groups = [];
    if (q.trim()) {
      const found = matches(q);
      groups.push([found.length ? `${found.length} ${found.length === 1 ? 'match' : 'matches'}` : null, found]);
    } else {
      const pinned = recent().map((n) => S.games.find((g) => g['game-name'] === n)).filter(Boolean);
      if (pinned.length && S.games.length > RECENT_MAX) groups.push(['Recent', pinned]);
      groups.push([`All games (${S.games.length})`, S.games]);
    }
    SW.items = groups.flatMap(([, gs]) => gs);
    SW.active = Math.min(SW.active, Math.max(0, SW.items.length - 1));
    let i = 0;
    $('#game-list').innerHTML = SW.items.length
      ? groups.map(([head, gs]) => (head ? `<li class="switcher-head" role="presentation">${esc(head)}</li>` : '') + gs.map((g) => optionHtml(g, i++)).join('')).join('')
      : `<li class="switcher-empty" role="presentation">No game matches “${esc(q.trim())}”.</li>`;
    const input = $('#game-filter');
    if (SW.items.length) input.setAttribute('aria-activedescendant', `game-opt-${SW.active}`);
    else input.removeAttribute('aria-activedescendant');
    $(`#game-opt-${SW.active}`)?.scrollIntoView({ block: 'nearest' });
  }
  function openSwitcher() {
    SW.open = true; SW.active = 0;
    $('#game').setAttribute('aria-expanded', 'true');
    $('[data-switcher-pop]').hidden = false;
    $('#game-filter').value = '';
    drawList();
    $('#game-filter').focus();
  }
  function closeSwitcher(refocus) {
    if (!SW.open) return;
    SW.open = false;
    $('#game').setAttribute('aria-expanded', 'false');
    $('[data-switcher-pop]').hidden = true;
    if (refocus) $('#game').focus();
  }
  function chooseGame(name) {
    closeSwitcher(true);
    pushRecent(name);
    if (name === S.game) return;
    S.game = name; S.model = null; S.selected = null;
    switcherLabel(); remember(); loadFlywheel();
  }

  // --- events ---------------------------------------------------------------
  document.addEventListener('click', (e) => {
    const t = e.target.closest('[data-signin],[data-signout],[data-retry],[data-range],[data-c]');
    if (!t) return;
    if (t.matches('[data-signin]')) {
      const u = signInUrl();
      if (u) location.href = u; else showError('Sign-in is unavailable.', 'Try again in a moment.');
    } else if (t.matches('[data-signout]')) {
      store.drop('token');
      const u = signInUrl();
      location.href = u ? u + '&logout=true' : '/';
    } else if (t.matches('[data-retry]')) {
      S.model ? loadFlywheel() : boot();
    } else if (t.matches('[data-range]')) {
      if (t.dataset.range === S.range) return;
      S.range = t.dataset.range; remember(); loadFlywheel();
    } else if (t.matches('[data-c]')) {
      select(t.dataset.c);
      if (matchMedia('(max-width: 980px)').matches) $('[data-drill]').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  });
  document.addEventListener('keydown', (e) => {
    const t = e.target.closest?.('[data-c]');
    if (t && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); select(t.dataset.c); }
  });
  $('#game').addEventListener('click', () => (SW.open ? closeSwitcher(true) : openSwitcher()));
  $('#game').addEventListener('keydown', (e) => {
    if (!SW.open && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); openSwitcher(); }
  });
  $('#game-filter').addEventListener('input', () => { SW.active = 0; drawList(); });
  $('#game-filter').addEventListener('keydown', (e) => {
    const n = SW.items.length;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (n) { SW.active = (SW.active + (e.key === 'ArrowDown' ? 1 : n - 1)) % n; drawList(); }
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (n) chooseGame(SW.items[SW.active]['game-name']);
    } else if (e.key === 'Escape') {
      e.preventDefault(); closeSwitcher(true);
    } else if (e.key === 'Tab') closeSwitcher(false);
  });
  $('#game-list').addEventListener('mousedown', (e) => e.preventDefault());
  $('#game-list').addEventListener('click', (e) => {
    const li = e.target.closest('[data-game]');
    if (li) chooseGame(li.dataset.game);
  });
  document.addEventListener('pointerdown', (e) => {
    if (SW.open && !e.target.closest('[data-switcher]')) closeSwitcher(false);
  });
  document.addEventListener('mousemove', (e) => {
    if (!S.model) return;
    const pill = e.target.closest?.('.wheel [data-handoff]');
    const node = e.target.closest?.('.wheel [data-c]');
    if (pill) tooltip(handoffTip(pill.dataset.handoff), e);
    else if (node) {
      const c = S.model.cs.find((x) => x.id === node.dataset.c);
      tooltip(c.enabled ? `<b>${esc(c.name)}</b>${esc(c.headline.label)}: ${esc(fmt(c.headline))}${c.headline.prior != null ? `<br>${esc(PRIOR_WORDS[S.range])}: ${esc(fmt({ ...c.headline, value: c.headline.prior }))}` : ''}<br>Click for detail` : `<b>${esc(c.name)}</b>Not enabled for this game`, e);
    } else if (tip && !tip.hidden) tooltip(null);
  });

  boot();
})();
