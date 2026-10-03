document.documentElement.classList.add('js');

const toggle = document.querySelector('.nav-toggle');
const nav = document.querySelector('#site-nav');

toggle?.addEventListener('click', () => {
  const open = toggle.getAttribute('aria-expanded') === 'true';
  toggle.setAttribute('aria-expanded', String(!open));
  nav.classList.toggle('is-open', !open);
  document.body.classList.toggle('menu-open', !open);
});

nav?.querySelectorAll('a').forEach((link) => link.addEventListener('click', () => {
  toggle?.setAttribute('aria-expanded', 'false');
  nav.classList.remove('is-open');
  document.body.classList.remove('menu-open');
}));

const observer = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add('is-visible');
      observer.unobserve(entry.target);
    }
  });
}, { threshold: 0.12, rootMargin: '0px 0px -40px' });

document.querySelectorAll('.reveal').forEach((element) => observer.observe(element));

const tallyFrames = document.querySelectorAll('iframe[data-tally-src]:not([src])');

if (tallyFrames.length) {
  const loadTallyEmbeds = () => {
    window.Tally?.loadEmbeds();
    tallyFrames.forEach((frame) => {
      if (!frame.src) frame.src = frame.dataset.tallySrc;
    });
  };
  const tallyScript = document.createElement('script');
  tallyScript.src = 'https://tally.so/widgets/embed.js';
  tallyScript.onload = loadTallyEmbeds;
  tallyScript.onerror = loadTallyEmbeds;
  document.body.appendChild(tallyScript);
}

window.addEventListener('message', (event) => {
  if (typeof event.data !== 'string' || !event.data.includes('Tally.FormSubmitted')) return;
  try {
    const { payload } = JSON.parse(event.data);
    if (payload?.formId !== 'D4RKVp') return;
    const status = document.querySelector('.lead-form-status');
    if (status) status.textContent = 'Thanks — your details are in. We’ll route them to the right conversation.';
  } catch (_) {
    // Ignore unrelated cross-window messages.
  }
});

/* ── Coselling.ai network layer ──────────────────────────────────────────
   The site is served by the Coselling.ai Game Master, which records
   attribution and runs sign-in. Every page carries this, so every page is a
   share surface: a visit with ?ref= is recorded wherever it lands. */
(() => {
  const KEY = (k) => `coselling.${k}`;
  const REF_TTL = 30 * 864e5;
  const store = {
    get(k, s = localStorage) { try { return s.getItem(KEY(k)); } catch (_) { return null; } },
    set(k, v, s = localStorage) { try { s.setItem(KEY(k), v); } catch (_) {} },
    drop(k, s = localStorage) { try { s.removeItem(KEY(k)); } catch (_) {} },
  };
  const S = { session: null, authcfg: null, coseller: null };
  const params = new URLSearchParams(location.search);
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /* The auth app returns via /auth/success?token=… and the GM bounces the
     query to "/". This is the only way the token reaches the page; strip it. */
  function captureToken() {
    const t = params.get('token');
    if (!t) return;
    store.set('token', t);
    params.delete('token'); params.delete('provider');
    const q = params.toString();
    history.replaceState({}, '', location.pathname + (q ? '?' + q : '') + location.hash);
  }
  const auth = () => { const t = store.get('token'); return t ? { Authorization: 'Bearer ' + t } : {}; };
  const signedIn = () => !!S.session?.authenticated;
  const seat = () => S.session?.selectedSeat || null;
  const seated = () => !!seat()?.playerName;
  const roles = () => seat()?.playerRoles || (seat()?.playerRole ? [seat().playerRole] : []);

  /* First touch wins, and survives the auth round-trip. */
  function stashRef() {
    const t = params.get('ref');
    if (!t) return;
    try {
      const prior = JSON.parse(store.get('ref') || 'null');
      if (prior && Date.now() - prior.at < REF_TTL) return;
    } catch (_) {}
    store.set('ref', JSON.stringify({ token: t, at: Date.now() }));
  }
  async function fireTouch() {
    const t = params.get('ref');
    if (!t) return;
    try {
      await fetch('/public/touch', { method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: t, path: location.pathname }) });
    } catch (_) {}
  }
  async function claimIfNeeded() {
    if (!seated() || store.get('claimed') === '1') return;
    try {
      const r = await fetch('/coseller/claim-visitor', { method: 'POST', credentials: 'include', headers: auth() });
      if (r.ok) store.set('claimed', '1');
    } catch (_) {}
  }

  async function getJSON(url, opts = {}) {
    try {
      const r = await fetch(url, Object.assign({ credentials: 'include', headers: auth() }, opts));
      return r.ok ? await r.json() : null;
    } catch (_) { return null; }
  }
  async function loadSession() {
    S.session = await getJSON('/session');
    S.authcfg = await getJSON('/auth.json', { credentials: 'omit', headers: {} });
  }
  async function loadCoseller() {
    S.coseller = (seated() && roles().includes('coseller')) ? await getJSON('/api/coseller/me') : null;
  }

  function signInUrl() {
    const b = S.authcfg?.auth_base_url;
    return b ? b.replace(/\/$/, '') + '/?config=' + encodeURIComponent(location.origin + '/auth.json') : '';
  }
  const signOutUrl = () => { const u = signInUrl(); return u ? u + '&logout=true' : ''; };
  const rememberReturn = (path) => store.set('return', path, sessionStorage);
  /* Sign-in always lands on "/"; send people back to where they started. */
  function resumeReturn() {
    const path = store.get('return', sessionStorage);
    if (!path || !signedIn()) return false;
    store.drop('return', sessionStorage);
    if (path === location.pathname) return false;
    location.replace(path);
    return true;
  }

  /* A link to THIS page with your ref, replacing anyone else's. */
  function refUrl(href = location.href) {
    const t = S.coseller?.['ref-token'];
    if (!t) return '';
    const u = new URL(href, location.origin);
    ['ref', 'token', 'provider'].forEach((p) => u.searchParams.delete(p));
    u.searchParams.set('ref', t);
    return u.toString();
  }
  function toast(msg) {
    let el = document.querySelector('.gm-toast');
    if (!el) { el = document.createElement('p'); el.className = 'gm-toast'; el.setAttribute('role', 'status'); document.body.appendChild(el); }
    el.textContent = msg; el.classList.add('is-on');
    clearTimeout(toast.t); toast.t = setTimeout(() => el.classList.remove('is-on'), 3200);
  }
  async function copy(url) {
    try { await navigator.clipboard.writeText(url); toast('Link copied. Share it anywhere.'); }
    catch (_) { toast('Copy failed. Select the link and copy it.'); }
  }
  async function share(url) {
    if (navigator.share) {
      try { await navigator.share({ title: document.title, url }); } catch (_) {}
    } else copy(url);
  }

  /* Signed-in people see their handle in the header. */
  function renderHeader() {
    const cta = document.querySelector('.header-cta');
    if (!cta || !seated()) return;
    cta.href = '/pages/share/';
    cta.innerHTML = `@${esc(seat().playerName)} <span aria-hidden="true">↗</span>`;
  }

  /* Cosellers get a share button on every page: any page is a share link. */
  function renderShareButton() {
    if (!S.coseller?.['ref-token'] || document.querySelector('[data-gm="share"]')) return;
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'gm-share-fab';
    b.innerHTML = 'Share this page <span aria-hidden="true">↗</span>';
    b.addEventListener('click', () => share(refUrl()));
    document.body.appendChild(b);
  }

  function signInBlock(returnTo, lead) {
    const u = signInUrl();
    return `<p>${lead}</p>
      ${u ? `<a class="button button-primary" href="${esc(u)}" data-return="${esc(returnTo)}">Sign in to start <span aria-hidden="true">→</span></a>`
          : '<p class="gm-note">Sign-in is not available right now. Please try again shortly.</p>'}`;
  }

  /* /pages/join/: sign in, then choose a permanent handle. */
  function renderJoin(el) {
    if (!signedIn()) {
      el.innerHTML = signInBlock('/pages/join/', 'Sign in with your email or Google account. Then pick your handle.');
    } else if (!seated()) {
      el.innerHTML = `<form class="gm-form" novalidate>
          <label for="gm-handle">Your handle</label>
          <div class="gm-row"><span aria-hidden="true">@</span><input id="gm-handle" name="handle" autocomplete="username" autocapitalize="none" spellcheck="false" required></div>
          <p class="gm-note">Choose carefully: your handle is permanent. It is your name in the network and on your rewards.</p>
          <p class="gm-error" role="alert"></p>
          <button class="button button-primary" type="submit">Claim my handle <span aria-hidden="true">→</span></button>
        </form>`;
      el.querySelector('form').addEventListener('submit', claimHandle);
    } else {
      el.innerHTML = `<p>You're in as <strong>@${esc(seat().playerName)}</strong>.</p>
        <a class="button button-primary" href="/pages/share/">Get your share link <span aria-hidden="true">→</span></a>`;
    }
  }
  /* The handle is permanent: MoM has no rename, and player-name is the join
     key across game state and the ledger. MoM owns validation; show its words. */
  async function claimHandle(ev) {
    ev.preventDefault();
    const form = ev.currentTarget;
    const name = form.handle.value.trim();
    const err = form.querySelector('.gm-error'); err.textContent = '';
    if (!name) { err.textContent = 'Pick a handle first.'; return; }
    form.querySelector('button').disabled = true;
    try {
      const r = await fetch('/join/claim', { method: 'POST', credentials: 'include',
        headers: Object.assign({ 'Content-Type': 'application/json' }, auth()),
        body: JSON.stringify({ 'player-name': name, roles: ['audience'] }) });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) {
        err.textContent = body.message || body.error?.message || body.error || `Could not claim that handle (${r.status}).`;
        form.querySelector('button').disabled = false;
        return;
      }
      await loadSession(); await claimIfNeeded();
      location.assign('/pages/share/');
    } catch (_) {
      err.textContent = 'Network hiccup. Try again?';
      form.querySelector('button').disabled = false;
    }
  }

  /* /pages/share/: activate Coselling, then copy or share your link. */
  function renderShare(el) {
    if (!signedIn()) {
      el.innerHTML = signInBlock('/pages/share/', 'Sign in to get your share link.');
    } else if (!seated()) {
      el.innerHTML = `<p>First, pick your handle.</p>
        <a class="button button-primary" href="/pages/join/">Pick a handle <span aria-hidden="true">→</span></a>`;
    } else if (!S.coseller?.['ref-token']) {
      el.innerHTML = `<p>Signed in as <strong>@${esc(seat().playerName)}</strong>. One step to your link.</p>
        <button class="button button-primary" type="button" data-activate>Get my share link <span aria-hidden="true">→</span></button>`;
      el.querySelector('[data-activate]').addEventListener('click', activate);
    } else {
      const link = refUrl(location.origin + '/');
      el.innerHTML = `<p>Your link, <strong>@${esc(seat().playerName)}</strong>:</p>
        <p class="gm-link"><input readonly value="${esc(link)}" aria-label="Your share link"></p>
        <div class="gm-actions">
          <button class="button button-primary" type="button" data-copy>Copy link <span aria-hidden="true">⧉</span></button>
          <button class="button button-dark" type="button" data-share>Share <span aria-hidden="true">↗</span></button>
        </div>
        <p class="gm-note">This links to the homepage. On any other page, use <strong>Share this page</strong> at the bottom right: the link carries your name wherever it goes.</p>
        ${signOutUrl() ? `<p class="gm-note"><a href="${esc(signOutUrl())}" data-signout>Sign out</a></p>` : ''}`;
      el.querySelector('[data-copy]').addEventListener('click', () => copy(link));
      el.querySelector('[data-share]').addEventListener('click', () => share(link));
      el.querySelector('[data-signout]')?.addEventListener('click', () => store.drop('token'));
    }
  }
  async function activate(ev) {
    ev.currentTarget.disabled = true;
    try {
      const r = await fetch('/api/coseller/activate', { method: 'POST', credentials: 'include', headers: auth() });
      const body = await r.json().catch(() => ({}));
      if (!r.ok || body.error) throw new Error(body?.error?.message || body?.error?.code || 'Activation failed. Try again?');
      await loadSession(); await loadCoseller();
      toast('You’re in. Your link is ready.');
    } catch (e) { toast(e.message); }
    render();
  }

  function render() {
    renderHeader();
    renderShareButton();
    const join = document.querySelector('[data-gm="join"]');
    if (join) renderJoin(join);
    const panel = document.querySelector('[data-gm="share"]');
    if (panel) renderShare(panel);
  }

  document.addEventListener('click', (ev) => {
    const a = ev.target.closest?.('a[data-return]');
    if (a) rememberReturn(a.dataset.return);
  });

  async function boot() {
    captureToken();
    stashRef();
    fireTouch();
    await loadSession();
    if (resumeReturn()) return;
    await claimIfNeeded();
    await loadCoseller();
    render();
  }
  boot();
})();
