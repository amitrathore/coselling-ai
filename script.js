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
