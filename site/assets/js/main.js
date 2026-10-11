/* Tether — site motion and behaviour.
   GSAP + ScrollTrigger + Lenis (vendored). Everything degrades: no JS, reduced motion, touch, small screens. */
(function () {
  'use strict';
  window.__tether = true;

  var doc = document.documentElement;
  var REPO = 'Benfranklinms/Tether';
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var hasGsap = !!(window.gsap && window.ScrollTrigger);
  var fineHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
  if (!hasGsap) { reduced = true; }
  doc.classList.toggle('motion', !reduced);
  doc.classList.toggle('no-motion', reduced);

  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var clamp = function (v, a, b) { return Math.max(a, Math.min(b, v)); };

  /* ------------------------------------------------------------ clips (one place) */
  var CLIPS = {
    clipboard: 'Copying a photo on the phone and pasting it into a note on the Mac',
    notifications: 'Replying to a notification on the Mac',
    calls: 'Answering a phone call on the Mac',
    files: 'A file dragged from the Mac lands on the phone',
    shelf: 'Shelf items appearing on the Mac and the phone',
    find: 'The Mac ringing from the phone',
    insert: 'Taking a photo with the phone from the Mac and dropping it into a document',
    more: 'Joining the phone\u2019s hotspot and opening the phone screen on the Mac'
  };
  function makeVideo(name, eager) {
    var v = document.createElement('video');
    v.muted = true; v.loop = true; v.playsInline = true;
    v.setAttribute('muted', ''); v.setAttribute('playsinline', ''); v.setAttribute('loop', '');
    v.setAttribute('preload', 'none');
    v.setAttribute('poster', 'assets/video/loop-' + name + '.jpg');
    v.setAttribute('aria-label', CLIPS[name] || name);
    v.className = 'js-loop';
    ['webm', 'mp4'].forEach(function (ext) {
      var s = document.createElement('source');
      s.setAttribute('data-src', 'assets/video/loop-' + name + '.' + ext);
      s.type = 'video/' + ext;
      v.appendChild(s);
    });
    if (eager) v.dataset.eager = '1';
    return v;
  }
  var stageMedia = $$('.ch-media');
  stageMedia.forEach(function (m) { m.appendChild(makeVideo(m.getAttribute('data-media'))); });
  $$('.ch-text').forEach(function (t, i) {
    var holder = $('.ch-mobile-media', t);
    var name = stageMedia[i] && stageMedia[i].getAttribute('data-media');
    if (holder && name) holder.appendChild(makeVideo(name));
  });

  /* ------------------------------------------------------------ lazy video loading + play when visible */
  function loadVideo(v) {
    if (v.dataset.loaded) return;
    v.dataset.loaded = '1';
    $$('source', v).forEach(function (s) { if (s.dataset.src) s.src = s.dataset.src; });
    v.load();
  }
  function tryPlay(v) {
    if (reduced && !v.classList.contains('js-film-video')) return;
    var p = v.play();
    if (p && p.catch) p.catch(function () {});
  }
  var loops = $$('video.js-loop');
  if ('IntersectionObserver' in window) {
    var near = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { loadVideo(e.target); near.unobserve(e.target); } });
    }, { rootMargin: '800px 0px' });
    var vis = new IntersectionObserver(function (es) {
      es.forEach(function (e) {
        var v = e.target;
        v._inView = e.isIntersecting;
        if (e.isIntersecting && v._wanted !== false) { loadVideo(v); tryPlay(v); }
        else v.pause();
      });
    }, { threshold: 0.15 });
    loops.forEach(function (v) { near.observe(v); vis.observe(v); });
  } else {
    loops.forEach(function (v) { loadVideo(v); tryPlay(v); });
  }
  document.addEventListener('visibilitychange', function () {
    loops.forEach(function (v) {
      if (document.hidden) v.pause();
      else if (v._inView && v._wanted !== false) tryPlay(v);
    });
  });

  /* ------------------------------------------------------------ nav */
  var nav = $('#nav');
  var burger = $('.nav-burger');
  function setScrolled(y) { nav.classList.toggle('scrolled', y > 24); }
  setScrolled(window.scrollY);
  window.addEventListener('scroll', function () { setScrolled(window.scrollY); }, { passive: true });
  if (burger) {
    burger.addEventListener('click', function () {
      var open = !nav.classList.contains('open');
      nav.classList.toggle('open', open);
      burger.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    $$('.nav-sheet a').forEach(function (a) { a.addEventListener('click', function () { nav.classList.remove('open'); burger.setAttribute('aria-expanded', 'false'); }); });
  }

  /* ------------------------------------------------------------ smooth scroll */
  var lenis = null;
  if (!reduced && window.Lenis) {
    lenis = new window.Lenis({ duration: 1.15, easing: function (t) { return Math.min(1, 1.001 - Math.pow(2, -10 * t)); }, smoothWheel: true, wheelMultiplier: 1, touchMultiplier: 1.4 });
  }
  function scrollToTarget(target) {
    var el = typeof target === 'number' ? null : (target === '#top' ? document.body : $(target));
    if (typeof target === 'number') {
      if (lenis) lenis.scrollTo(target, { duration: 1.4 }); else window.scrollTo({ top: target, behavior: reduced ? 'auto' : 'smooth' });
      return;
    }
    if (!el) return;
    var off = target === '#top' ? 0 : -70;
    if (lenis) lenis.scrollTo(el, { offset: off, duration: 1.4 });
    else window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY + off, behavior: reduced ? 'auto' : 'smooth' });
  }
  $$('a[href^="#"]').forEach(function (a) {
    a.addEventListener('click', function (e) {
      var id = a.getAttribute('href');
      if (id.length < 2) return;
      if (id !== '#top' && !$(id)) return;
      e.preventDefault();
      scrollToTarget(id);
      if (id !== '#top' && history.replaceState) history.replaceState(null, '', id);
    });
  });

  /* ------------------------------------------------------------ release links (always the latest) */
  (function releases() {
    var KEY = 'tether-latest-v1';
    function apply(rel) {
      if (!rel || !rel.tag_name || !rel.assets) return;
      var ver = String(rel.tag_name).replace(/^v/, '');
      var urls = {};
      rel.assets.forEach(function (a) {
        var m = /\.(dmg|zip|apk)$/i.exec(a.name || '');
        if (m && a.browser_download_url) urls[m[1].toLowerCase()] = a.browser_download_url;
      });
      $$('[data-asset]').forEach(function (el) { var u = urls[el.getAttribute('data-asset')]; if (u) el.href = u; });
      if (/^\d+\.\d+(\.\d+)?$/.test(ver)) $$('[data-version-label]').forEach(function (el) { el.textContent = 'v' + ver; });
    }
    try {
      var cached = JSON.parse(sessionStorage.getItem(KEY) || 'null');
      if (cached && Date.now() - cached.t < 30 * 60 * 1000) { apply(cached.r); return; }
    } catch (e) {}
    if (!window.fetch) return;
    fetch('https://api.github.com/repos/' + REPO + '/releases/latest', { headers: { Accept: 'application/vnd.github+json' } })
      .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .then(function (r) {
        var slim = { tag_name: r.tag_name, assets: (r.assets || []).map(function (a) { return { name: a.name, browser_download_url: a.browser_download_url }; }) };
        apply(slim);
        try { sessionStorage.setItem(KEY, JSON.stringify({ t: Date.now(), r: slim })); } catch (e) {}
      })
      .catch(function () { /* hardcoded v0.5.5 links stay */ });
  })();

  /* ------------------------------------------------------------ copy SHA */
  var copyBtn = $('.js-copy');
  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var txt = $('.js-sha').textContent.trim();
      var done = function () {
        copyBtn.classList.add('ok'); $('span', copyBtn).textContent = 'Copied';
        $('use', copyBtn).setAttribute('href', '#check');
        clearTimeout(copyBtn._t);
        copyBtn._t = setTimeout(function () { copyBtn.classList.remove('ok'); $('span', copyBtn).textContent = 'Copy'; $('use', copyBtn).setAttribute('href', '#copy'); }, 1800);
      };
      if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(txt).then(done, fallback);
      else fallback();
      function fallback() {
        var ta = document.createElement('textarea'); ta.value = txt; ta.setAttribute('readonly', ''); ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.select();
        try { document.execCommand('copy'); done(); } catch (e) {}
        document.body.removeChild(ta);
      }
    });
  }

  /* ------------------------------------------------------------ film player */
  var CHAPTERS = [
    [0, 'Your Mac and your Android', 'Intro'], [3.0, 'Meet Tether', 'Intro'],
    [6.0, 'Clipboard', '01 / 08'], [10.0, 'Notifications', '02 / 08'],
    [13.5, 'Calls & SMS', '03 / 08'], [17.0, 'Files & photos', '04 / 08'],
    [20.6, 'Shelf', '05 / 08'], [24.0, 'Find my Mac', '06 / 08'],
    [27.4, 'Insert from Phone', '07 / 08'], [31.6, 'And more', '08 / 08'],
    [34.6, 'Privacy', 'No cloud \u00b7 No account'], [39.6, 'Tether', 'Free and open source']
  ];
  var film = $('.js-film');
  var fv = $('.js-film-video');
  var filmUserPaused = false, filmAuto = false;
  if (film && fv) {
    var DUR = 46;
    var capEl = $('.js-cap'), capT = $('.js-cap-t'), capS = $('.js-cap-s'), timeEl = $('.js-time'), fill = $('.js-fill'), bar = $('.js-bar');
    var ticks = $('.js-ticks');
    CHAPTERS.slice(1).forEach(function (c) { var i = document.createElement('i'); i.style.left = (c[0] / DUR * 100) + '%'; ticks.appendChild(i); });
    var curCh = -1;
    var fmt = function (s) { s = Math.max(0, Math.round(s)); return Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2); };
    function setChapter(t) {
      var idx = 0;
      for (var i = 0; i < CHAPTERS.length; i++) if (t >= CHAPTERS[i][0] - 0.05) idx = i;
      if (idx === curCh) return;
      curCh = idx;
      capEl.classList.add('out');
      setTimeout(function () { capT.textContent = CHAPTERS[idx][1]; capS.textContent = CHAPTERS[idx][2]; capEl.classList.remove('out'); }, 220);
    }
    function tick() {
      var d = fv.duration || DUR, t = fv.currentTime || 0;
      fill.style.transform = 'scaleX(' + clamp(t / d, 0, 1) + ')';
      timeEl.textContent = fmt(d - t);
      bar.setAttribute('aria-valuenow', Math.round(t));
      setChapter(t);
      if (!fv.paused) requestAnimationFrame(tick);
    }
    fv.addEventListener('play', function () { film.classList.add('playing'); requestAnimationFrame(tick); });
    fv.addEventListener('pause', function () { film.classList.remove('playing'); tick(); });
    fv.addEventListener('seeked', tick);
    fv.addEventListener('loadedmetadata', function () { DUR = fv.duration || DUR; tick(); });
    function playFilm(user) {
      loadVideo(fv);
      if (user) filmUserPaused = false;
      var p = fv.play(); if (p && p.catch) p.catch(function () {});
    }
    function togglePlay() {
      if (fv.paused) playFilm(true); else { fv.pause(); filmUserPaused = true; }
    }
    $('.js-play').addEventListener('click', function (e) { e.stopPropagation(); togglePlay(); });
    $('.js-film-big').addEventListener('click', function (e) { e.stopPropagation(); playFilm(true); });
    film.addEventListener('click', function (e) { if (e.target.closest('.film-ui')) return; togglePlay(); });
    $('.js-mute').addEventListener('click', function (e) {
      e.stopPropagation();
      fv.muted = !fv.muted;
      if (!fv.muted && fv.paused) playFilm(true);
      film.classList.toggle('unmuted', !fv.muted);
      this.setAttribute('aria-label', fv.muted ? 'Unmute' : 'Mute');
    });
    function seekFrom(clientX) {
      var r = bar.getBoundingClientRect();
      var p = clamp((clientX - r.left) / r.width, 0, 1);
      loadVideo(fv);
      fv.currentTime = p * (fv.duration || DUR);
      tick();
    }
    var dragging = false;
    bar.addEventListener('pointerdown', function (e) { dragging = true; bar.setPointerCapture(e.pointerId); seekFrom(e.clientX); e.stopPropagation(); });
    bar.addEventListener('pointermove', function (e) { if (dragging) seekFrom(e.clientX); });
    bar.addEventListener('pointerup', function () { dragging = false; });
    bar.addEventListener('click', function (e) { e.stopPropagation(); });
    bar.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        loadVideo(fv); fv.currentTime = clamp((fv.currentTime || 0) + (e.key === 'ArrowRight' ? 5 : -5), 0, fv.duration || DUR); tick(); e.preventDefault();
      }
    });
    // autoplay muted inline while on screen (not with reduced motion)
    if (!reduced && 'IntersectionObserver' in window) {
      new IntersectionObserver(function (es) {
        es.forEach(function (e) {
          if (e.isIntersecting && e.intersectionRatio >= 0.45) { if (!filmUserPaused) { filmAuto = true; playFilm(false); } }
          else if (!fv.paused) { fv.pause(); }
        });
      }, { threshold: [0, 0.45, 0.8] }).observe(film);
    }
  }

  /* ------------------------------------------------------------ word splitting */
  function splitWords(el) {
    if (el.dataset.splitDone) return [];
    el.dataset.splitDone = '1';
    var out = [];
    var walk = function (node) {
      Array.prototype.slice.call(node.childNodes).forEach(function (n) {
        if (n.nodeType === 3) {
          var parts = n.textContent.split(/(\s+)/);
          var frag = document.createDocumentFragment();
          parts.forEach(function (p) {
            if (!p) return;
            if (/^\s+$/.test(p)) { frag.appendChild(document.createTextNode(' ')); return; }
            var m = document.createElement('span'); m.className = 'wm';
            var i = document.createElement('span'); i.className = 'wi'; i.textContent = p;
            m.appendChild(i); frag.appendChild(m); out.push(i);
          });
          node.replaceChild(frag, n);
        } else if (n.nodeType === 1 && n.tagName !== 'BR') walk(n);
      });
    };
    walk(el);
    return out;
  }
  // split everything up front so the layout never changes later
  var splitTargets = $$('[data-split]');
  splitTargets.forEach(function (el) { el._words = splitWords(el); });
  $$('.ch-text h3').forEach(function (el) { splitWords(el); });
  // privacy: plain word spans (scrubbed opacity)
  var privWords = [];
  $$('.js-words .line').forEach(function (line) {
    var words = line.textContent.trim().split(/\s+/);
    line.textContent = '';
    words.forEach(function (w, i) {
      var s = document.createElement('span'); s.className = 'w'; s.textContent = w;
      line.appendChild(s); if (i < words.length - 1) line.appendChild(document.createTextNode(' '));
      privWords.push(s);
    });
  });

  /* ------------------------------------------------------------ hex stream (privacy) */
  var hex = $('.js-hex');
  if (hex) {
    var rnd = function (n) { var s = ''; for (var i = 0; i < n; i++) s += '0123456789abcdef'[(Math.random() * 16) | 0]; return s; };
    var html = '';
    for (var i = 0; i < 26; i++) html += (i % 6 === 3 ? '<span class="lk">\u25CF noise_xx</span>' : '<span>' + rnd(8) + ' ' + rnd(8) + '</span>');
    hex.innerHTML = html + html;
  }

  /* ------------------------------------------------------------ story (pinned on wide screens) */
  var story = $('.js-story');
  var chTexts = $$('.ch-text'), chItems = $$('.ch-index li'), countEl = $('.js-count');
  var NCH = chTexts.length;
  var curStory = -1;
  var pinMQ = window.matchMedia('(min-width: 901px) and (min-height: 700px)');
  function pinMode() { return !reduced && pinMQ.matches; }
  doc.classList.toggle('pin', pinMode());
  function setStory(idx, prog) {
    if (idx !== curStory) {
      curStory = idx;
      chTexts.forEach(function (t, i) { t.classList.toggle('on', i === idx); t.setAttribute('aria-hidden', i === idx ? 'false' : 'true'); });
      chItems.forEach(function (li, i) { li.classList.toggle('on', i === idx); });
      stageMedia.forEach(function (m, i) {
        m.classList.toggle('on', i === idx);
        var v = $('video', m);
        if (!v) return;
        v._wanted = i === idx;
        if (i === idx) { loadVideo(v); if (v._inView !== false) { try { v.currentTime = 0; } catch (e) {} tryPlay(v); } }
        else v.pause();
      });
      if (countEl) countEl.textContent = ('0' + (idx + 1)).slice(-2);
    }
    if (typeof prog === 'number') {
      chItems.forEach(function (li, i) { li.style.setProperty('--p', clamp(prog * NCH - i, 0, 1).toFixed(3)); });
    }
  }
  // preload the neighbours of the stage clips once the story is near
  $$('.ch-index button').forEach(function (b) {
    b.addEventListener('click', function () {
      var i = +b.getAttribute('data-go');
      if (!pinMode() || !story) return;
      var top = story.getBoundingClientRect().top + window.scrollY;
      var span = story.offsetHeight - window.innerHeight;
      scrollToTarget(top + span * ((i + 0.5) / NCH));
    });
  });

  /* ------------------------------------------------------------ cursor glow + card glow + magnetic */
  if (fineHover && !reduced) {
    var glow = $('.glow');
    var gx = window.innerWidth / 2, gy = window.innerHeight / 3, tx = gx, ty = gy;
    window.addEventListener('pointermove', function (e) { tx = e.clientX; ty = e.clientY; }, { passive: true });
    (function loop() { gx += (tx - gx) * 0.12; gy += (ty - gy) * 0.12; glow.style.transform = 'translate3d(' + gx.toFixed(1) + 'px,' + gy.toFixed(1) + 'px,0)'; requestAnimationFrame(loop); })();
    $$('.js-card, .js-dl').forEach(function (c) {
      c.addEventListener('pointermove', function (e) {
        var r = c.getBoundingClientRect();
        c.style.setProperty('--mx', (e.clientX - r.left) + 'px');
        c.style.setProperty('--my', (e.clientY - r.top) + 'px');
      });
    });
  }
  if (fineHover && !reduced && hasGsap) {
    $$('[data-magnetic]').forEach(function (b) {
      var qx = window.gsap.quickTo(b, 'x', { duration: 0.5, ease: 'power3.out' });
      var qy = window.gsap.quickTo(b, 'y', { duration: 0.5, ease: 'power3.out' });
      var inner = b.firstElementChild;
      var ix = inner ? window.gsap.quickTo(inner, 'x', { duration: 0.5, ease: 'power3.out' }) : null;
      var iy = inner ? window.gsap.quickTo(inner, 'y', { duration: 0.5, ease: 'power3.out' }) : null;
      b.addEventListener('pointermove', function (e) {
        var r = b.getBoundingClientRect();
        var dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2);
        qx(dx * 0.28); qy(dy * 0.38);
        if (ix) { ix(dx * 0.12); iy(dy * 0.16); }
      });
      b.addEventListener('pointerleave', function () {
        window.gsap.to(b, { x: 0, y: 0, duration: 0.9, ease: 'elastic.out(1, 0.4)' });
        if (inner) window.gsap.to(inner, { x: 0, y: 0, duration: 0.9, ease: 'elastic.out(1, 0.4)' });
      });
    });
  }

  /* ============================================================ no-motion path */
  if (reduced) {
    var intro0 = $('.intro'); if (intro0) intro0.classList.add('done');
    window.addEventListener('scroll', function () { setScrolled(window.scrollY); }, { passive: true });
    setStory(0, 0);
    loops.forEach(function (v) { v._wanted = true; });
    return;
  }

  /* ============================================================ motion path */
  var gsap = window.gsap, ST = window.ScrollTrigger;
  gsap.registerPlugin(ST);
  gsap.config({ force3D: true });
  ST.config({ ignoreMobileResize: true });
  if (lenis) {
    lenis.on('scroll', function (e) { ST.update(); setScrolled(e.scroll); });
    gsap.ticker.add(function (t) { lenis.raf(t * 1000); });
    gsap.ticker.lagSmoothing(0);
    lenis.stop();
  } else {
    window.addEventListener('scroll', function () { setScrolled(window.scrollY); }, { passive: true });
  }

  /* --- hero initial state */
  var heroWords = ($('#hero-title') || {})._words || [];
  var heroFades = $$('.hero [data-fade]');
  gsap.set('.js-mac', { y: 140, opacity: 0, rotateX: 18, transformPerspective: 2200 });
  gsap.set('.js-phone', { y: 220, opacity: 0, rotate: 8 });
  gsap.set('.js-chip', { opacity: 0, scale: 0.85, y: 30 });

  /* --- intro */
  var intro = $('.intro');
  var tl = gsap.timeline({ defaults: { ease: 'expo.out' } });
  var counter = { v: 0 };
  tl.set('.ir-a', { x: -14 }).set('.ir-b', { x: 14 })
    .to('.intro-word span', { y: 0, duration: 0.8 }, 0.05)
    .to('.ir-a', { x: 0, duration: 0.9, ease: 'back.out(2)' }, 0.1)
    .to('.ir-b', { x: 0, duration: 0.9, ease: 'back.out(2)' }, 0.1)
    .to('.intro-bar', { scaleX: 1, duration: 1.0, ease: 'power2.inOut' }, 0)
    .to(counter, { v: 100, duration: 1.0, ease: 'power2.inOut', onUpdate: function () { var n = $('.ic-n'); if (n) n.textContent = String(Math.round(counter.v)).padStart(2, '0'); } }, 0)
    .to('.intro-mark', { y: -30, opacity: 0, duration: 0.5, ease: 'power2.in' }, 1.0)
    .to(intro, { clipPath: 'inset(0 0 100% 0)', duration: 0.9, ease: 'expo.inOut' }, 1.1)
    .add(function () { intro.classList.add('done'); if (lenis) lenis.start(); }, 1.95)
    .to(heroFades[0], { opacity: 1, y: 0, duration: 1 }, 1.45)
    .to(heroWords, { y: 0, duration: 1.2, stagger: 0.045 }, 1.5)
    .to(heroFades.slice(1), { opacity: 1, y: 0, duration: 1.1, stagger: 0.08 }, 1.85)
    .to('.js-mac', { y: 0, opacity: 1, rotateX: 0, duration: 1.8, ease: 'elastic.out(1, 0.85)' }, 1.75)
    .to('.js-phone', { y: 0, opacity: 1, rotate: 0, duration: 1.9, ease: 'elastic.out(1, 0.75)' }, 1.95)
    .to('.js-chip', { opacity: 1, scale: 1, y: 0, duration: 1.1, stagger: 0.18, ease: 'back.out(2.2)' }, 2.5);
  intro.style.clipPath = 'inset(0 0 0% 0)';
  // if the page loads scrolled down (anchor / restore), skip straight to the end
  if (window.scrollY > 200) { tl.progress(1); intro.classList.add('done'); if (lenis) lenis.start(); }

  /* --- hero scroll: lid tilts into place, phone and chips parallax */
  gsap.fromTo('.js-lid', { rotateX: 20, transformPerspective: 1800 }, {
    rotateX: 0, ease: 'none',
    scrollTrigger: { trigger: '.stage-wrap', start: 'top 95%', end: 'top 25%', scrub: 0.6 }
  });
  gsap.to('.js-phone', { yPercent: -16, ease: 'none', scrollTrigger: { trigger: '.stage-wrap', start: 'top 60%', end: 'bottom top', scrub: 0.6 } });
  gsap.to('.chip-a', { y: -120, ease: 'none', scrollTrigger: { trigger: '.stage-wrap', start: 'top 60%', end: 'bottom top', scrub: 0.8 } });
  gsap.to('.chip-b', { y: -200, ease: 'none', scrollTrigger: { trigger: '.stage-wrap', start: 'top 60%', end: 'bottom top', scrub: 0.8 } });
  gsap.to('.js-win', { y: -26, ease: 'none', scrollTrigger: { trigger: '.stage-wrap', start: 'top 60%', end: 'bottom top', scrub: 0.6 } });
  gsap.to('.js-wall', { yPercent: 6, scale: 1.06, ease: 'none', scrollTrigger: { trigger: '.stage-wrap', start: 'top bottom', end: 'bottom top', scrub: true } });
  gsap.to('.hero-copy', { y: -60, opacity: 0.25, ease: 'none', scrollTrigger: { trigger: '.hero', start: 'top top', end: 'bottom 30%', scrub: true } });

  /* --- generic reveals */
  splitTargets.forEach(function (el) {
    if (el.id === 'hero-title') return;
    gsap.to(el._words, { y: 0, duration: 1.1, ease: 'expo.out', stagger: 0.05, scrollTrigger: { trigger: el, start: 'top 86%', once: true } });
  });
  $$('[data-fade]').forEach(function (el) {
    if (el.closest('.hero')) return;
    gsap.to(el, { opacity: 1, y: 0, duration: 1.1, ease: 'expo.out', scrollTrigger: { trigger: el, start: 'top 90%', once: true } });
  });

  /* --- counters */
  $$('[data-count]').forEach(function (el) {
    var target = +el.getAttribute('data-count');
    if (!target) return;
    var o = { v: 0 };
    el.textContent = '0';
    gsap.to(o, { v: target, duration: 1.8, ease: 'power3.out', scrollTrigger: { trigger: el, start: 'top 92%', once: true }, onUpdate: function () { el.textContent = Math.round(o.v); } });
  });

  /* --- marquee: base drift + scroll velocity */
  var rows = $$('.mq-row');
  rows.forEach(function (r) { r.innerHTML = r.innerHTML + r.innerHTML; r._x = 0; r._dir = +r.getAttribute('data-dir') || -1; });
  var vel = 0;
  if (lenis) lenis.on('scroll', function (e) { vel = e.velocity || 0; });
  else ST.create({ onUpdate: function (s) { vel = s.getVelocity() / 60; } });
  var mqVisible = true;
  ST.create({ trigger: '.marquee', start: 'top bottom', end: 'bottom top', onToggle: function (s) { mqVisible = s.isActive; } });
  gsap.ticker.add(function (time, delta) {
    if (!mqVisible) return;
    var dt = Math.min(delta, 50) / 16.67;
    var boost = clamp(Math.abs(vel) * 0.6, 0, 26);
    rows.forEach(function (r) {
      var half = r.scrollWidth / 2;
      r._x += (0.6 + boost) * dt * r._dir * (vel < 0 ? -1 : 1);
      if (r._x <= -half) r._x += half;
      if (r._x > 0) r._x -= half;
      r.style.transform = 'translate3d(' + r._x.toFixed(2) + 'px,0,0)';
    });
    vel *= 0.92;
  });

  /* --- film card: grows and its corners un-round */
  if (film) {
    var radius0 = window.innerWidth < 640 ? 20 : (window.innerWidth < 900 ? 26 : 40);
    gsap.fromTo(film, { scale: 0.8, borderRadius: radius0 * 1.4, y: 40 }, {
      scale: 1, borderRadius: Math.round(radius0 * 0.35), y: 0, ease: 'none',
      scrollTrigger: { trigger: '.film-wrap', start: 'top bottom', end: 'center center', scrub: 0.5 }
    });
  }

  /* --- bento: staggered in with depth */
  gsap.set('.js-card, [data-pill], .js-dl', { opacity: 0 });
  ST.batch('.js-card', {
    start: 'top 88%', once: true,
    onEnter: function (els) {
      gsap.fromTo(els, { opacity: 0, y: 90, rotateX: 14, z: -140, transformPerspective: 1400, transformOrigin: '50% 100%' },
        { opacity: 1, y: 0, rotateX: 0, z: 0, duration: 1.4, ease: 'expo.out', stagger: 0.12, clearProps: 'transform' });
    }
  });
  ST.batch('[data-pill]', {
    start: 'top 94%', once: true,
    onEnter: function (els) { gsap.fromTo(els, { opacity: 0, y: 24, scale: 0.92 }, { opacity: 1, y: 0, scale: 1, duration: 0.9, ease: 'back.out(1.8)', stagger: 0.05 }); }
  });

  /* --- story */
  var mm = gsap.matchMedia();
  mm.add('(min-width: 901px) and (min-height: 700px)', function () {
    doc.classList.add('pin');
    curStory = -1; setStory(0, 0);
    var box = $('.ch-texts');
    var fit = function () { var h = 0; chTexts.forEach(function (t) { h = Math.max(h, t.offsetHeight); }); box.style.height = h + 'px'; };
    fit(); ST.addEventListener('refreshInit', fit);
    var st = ST.create({
      trigger: story, start: 'top top', end: 'bottom bottom',
      onUpdate: function (s) {
        var p = s.progress;
        setStory(clamp(Math.floor(p * NCH), 0, NCH - 1), p);
      }
    });
    var stTilt = gsap.fromTo('.js-ch-stage', { scale: 0.92, rotateY: -6, transformPerspective: 1800 }, {
      scale: 1, rotateY: 0, ease: 'none', scrollTrigger: { trigger: story, start: 'top bottom', end: 'top top', scrub: 0.6 }
    });
    var stOut = gsap.to('.story-pin > .wrap', { opacity: 0.0, y: -40, ease: 'none', scrollTrigger: { trigger: story, start: 'bottom 85%', end: 'bottom 35%', scrub: true } });
    return function () { st.kill(); stTilt.kill(); stOut.kill(); ST.removeEventListener('refreshInit', fit); box.style.height = ''; doc.classList.remove('pin'); };
  });
  mm.add('(max-width: 900px), (max-height: 699px)', function () {
    doc.classList.remove('pin');
    loops.forEach(function (v) { v._wanted = true; });
    chTexts.forEach(function (t) { t.classList.add('on'); t.removeAttribute('aria-hidden'); });
    var tw = [];
    chTexts.forEach(function (t) {
      var words = $$('.wi', t);
      var rest = [$('.num', t), $('p', t), $('.ch-mobile-media', t)];
      gsap.set(words, { y: '110%' });
      gsap.set(rest, { opacity: 0, y: 30 });
      tw.push(ST.create({ trigger: t, start: 'top 85%', once: true, onEnter: function () {
        gsap.to(words, { y: 0, duration: 1.1, ease: 'expo.out', stagger: 0.05 });
        gsap.to(rest, { opacity: 1, y: 0, duration: 1.1, ease: 'expo.out', stagger: 0.1, delay: 0.1 });
      } }));
    });
    return function () { tw.forEach(function (s) { s.kill(); }); gsap.set($$('.ch-text .wi, .ch-text .num, .ch-text p, .ch-mobile-media'), { clearProps: 'all' }); };
  });

  /* --- privacy: word-by-word, scrubbed */
  if (privWords.length) {
    gsap.to(privWords, { opacity: 1, ease: 'none', stagger: 0.12, scrollTrigger: { trigger: '.js-words', start: 'top 82%', end: 'bottom 45%', scrub: 0.5 } });
  }
  if (hex) {
    gsap.to(hex, { xPercent: -50, ease: 'none', duration: 40, repeat: -1 });
  }
  $$('[data-col]').forEach(function (c, i) {
    var kids = Array.prototype.slice.call(c.children);
    gsap.set(kids, { opacity: 0, y: 26 });
    ST.create({ trigger: c, start: 'top 88%', once: true, onEnter: function () {
      gsap.to(c, { '--s': 1, duration: 1.4, ease: 'expo.inOut', delay: i * 0.12 });
      gsap.to(kids, { opacity: 1, y: 0, duration: 1.1, ease: 'expo.out', stagger: 0.07, delay: 0.25 + i * 0.12 });
    } });
  });

  /* --- download cards */
  ST.batch('.js-dl', {
    start: 'top 88%', once: true,
    onEnter: function (els) { gsap.fromTo(els, { opacity: 0, y: 70, scale: 0.97 }, { opacity: 1, y: 0, scale: 1, duration: 1.3, ease: 'expo.out', stagger: 0.12, clearProps: 'transform' }); }
  });

  /* --- footer wordmark */
  gsap.fromTo('.js-wordmark span', { yPercent: 60, opacity: 0 }, {
    yPercent: 0, opacity: 1, ease: 'none', stagger: 0.06,
    scrollTrigger: { trigger: '.js-wordmark', start: 'top bottom', end: 'bottom bottom', scrub: 0.6 }
  });

  /* --- refresh after fonts / images settle (no layout jumps later) */
  var refresh = function () { ST.refresh(); };
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(refresh);
  window.addEventListener('load', refresh);
  var rw = window.innerWidth;
  window.addEventListener('resize', function () {
    // ignore mobile URL-bar height changes; only width changes need a refresh
    if (Math.abs(window.innerWidth - rw) > 2) { rw = window.innerWidth; ST.refresh(); }
  });
})();
