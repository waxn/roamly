/* Bento — behaviour: tiles pop into place on load, and stat numbers count
   up to their value, both on first paint and whenever a page fills one in
   or changes it later. Anything that isn't a single plain number is left
   exactly as the page wrote it. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'bento') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    var NUM = /^(\D*?)(-?\d{1,3}(?:,\d{3})+|-?\d+)(\.\d+)?(\D*)$/;
    function parse(text) {
        var m = NUM.exec(text.trim());
        if (!m) return null;
        var v = parseFloat(m[2].replace(/,/g, '') + (m[3] || ''));
        if (!isFinite(v) || Math.abs(v) > 1e12) return null;
        return { pre: m[1], v: v, dec: m[3] ? m[3].length - 1 : 0, comma: m[2].indexOf(',') !== -1, post: m[4] };
    }
    function fmt(p, v) {
        var s = v.toFixed(p.dec);
        if (p.comma) {
            var parts = s.split('.');
            parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
            s = parts.join('.');
        }
        return p.pre + s + p.post;
    }

    var mo = null;
    function count(el) {
        var text = el.textContent;
        if (el._bnSelf === text) return;          // our own frame
        if (el.children.length) return;           // skeletons etc.
        var p = parse(text);
        if (!p) return;
        var from = el._bnLast != null ? el._bnLast : 0;
        el._bnLast = p.v;
        if (reduced || from === p.v) return;
        if (el._bnRaf) cancelAnimationFrame(el._bnRaf);
        var t0 = performance.now(), dur = 900;
        el.setAttribute('aria-label', text);
        (function step(now) {
            var k = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(2, -10 * k);
            var out = k >= 1 ? text : fmt(p, from + (p.v - from) * e);
            el._bnSelf = out;
            el.textContent = out;
            if (k < 1) el._bnRaf = requestAnimationFrame(step);
            else { el._bnRaf = 0; el.removeAttribute('aria-label'); }
        })(t0);
    }

    ready(function () {
        document.querySelectorAll('.stat-value').forEach(count);
        mo = new MutationObserver(function (muts) {
            var seen = new Set();
            muts.forEach(function (m) {
                var n = m.target.nodeType === 1 ? m.target : m.target.parentElement;
                var el = n && n.closest && n.closest('.stat-value');
                if (el) seen.add(el);
                if (m.type === 'childList') m.addedNodes.forEach(function (a) {
                    if (a.nodeType === 1) a.querySelectorAll && a.querySelectorAll('.stat-value').forEach(function (s) { seen.add(s); });
                });
            });
            seen.forEach(count);
        });
        mo.observe(document.body, { childList: true, subtree: true, characterData: true });

        if (reduced) return;
        var tiles = Array.from(document.querySelectorAll('main .card')).filter(function (c) {
            var r = c.getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0;
        }).slice(0, 18);
        tiles.forEach(function (c, i) {
            c.style.animationDelay = (i * 35) + 'ms';
            c.classList.add('bn-in');
            c.addEventListener('animationend', function () { c.classList.remove('bn-in'); c.style.animationDelay = ''; }, { once: true });
        });
    });
})();
