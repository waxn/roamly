/* Lantern — behaviour: the lamp follows the pointer, pages get a dated
   "field notes" kicker, badges stamp themselves down as they scroll in,
   buttons leave an ink blot where pressed, and a compass sits in the corner. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-style') !== 'lantern') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    // ── lamp follows the pointer (rAF-throttled, eased) ──
    var tx = innerWidth * 0.22, ty = innerHeight * 0.08, x = tx, y = ty, raf = 0;
    function tick() {
        x += (tx - x) * 0.12; y += (ty - y) * 0.12;
        root.style.setProperty('--lx', x.toFixed(1) + 'px');
        root.style.setProperty('--ly', y.toFixed(1) + 'px');
        raf = (Math.abs(tx - x) + Math.abs(ty - y) > 0.5) ? requestAnimationFrame(tick) : 0;
    }
    if (!reduced) {
        addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            tx = e.clientX; ty = e.clientY;
            if (!raf) raf = requestAnimationFrame(tick);
        }, { passive: true });
    }

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    ready(function () {
        // ── dated kicker over the page title ──
        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (h && !h.previousElementSibling?.classList?.contains('ln-kicker')) {
            var k = document.createElement('div');
            k.className = 'ln-kicker';
            k.setAttribute('aria-hidden', 'true');
            var d = new Date();
            k.textContent = 'Field notes · ' + d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
            if (h.parentNode.classList.contains('page-toolbar')) {
                var wrap = document.createElement('div');
                h.parentNode.insertBefore(wrap, h);
                wrap.appendChild(k); wrap.appendChild(h);
            } else {
                h.parentNode.insertBefore(k, h);
            }
        }

        // ── compass rose ──
        if (!document.querySelector('.maplibregl-map') || document.querySelector('.container')) {
            var c = document.createElement('div');
            c.className = 'ln-compass';
            c.setAttribute('aria-hidden', 'true');
            c.innerHTML = '<svg viewBox="0 0 100 100" width="92" height="92" fill="none" stroke="currentColor">' +
                '<circle cx="50" cy="50" r="46" stroke-width="1.2"/><circle cx="50" cy="50" r="40" stroke-width="0.6" stroke-dasharray="1 3"/>' +
                '<path d="M50 6 L50 14 M50 86 L50 94 M6 50 L14 50 M86 50 L94 50" stroke-width="1.4"/>' +
                '<text x="50" y="25" font-size="9" text-anchor="middle" fill="currentColor" stroke="none" font-family="serif">N</text>' +
                '<g class="ln-needle"><path d="M50 22 L56 50 L50 78 L44 50 Z" stroke-width="1" fill="currentColor" fill-opacity="0.25"/><path d="M50 22 L56 50 L44 50 Z" fill="currentColor" stroke="none"/></g>' +
                '<circle cx="50" cy="50" r="2.5" fill="currentColor" stroke="none"/></svg>';
            document.body.appendChild(c);
        }

        // ── stamps land as they scroll in ──
        if (!reduced && 'IntersectionObserver' in window) {
            var io = new IntersectionObserver(function (entries) {
                entries.forEach(function (en) {
                    if (en.isIntersecting) { en.target.classList.add('ln-stamped'); io.unobserve(en.target); }
                });
            }, { threshold: 0.6 });
            var seen = new WeakSet();
            function scan(rootEl) {
                (rootEl.querySelectorAll ? rootEl : document).querySelectorAll('.badge').forEach(function (b) {
                    if (!seen.has(b)) { seen.add(b); io.observe(b); }
                });
            }
            scan(document);
            var mo = new MutationObserver(function (muts) {
                for (var i = 0; i < muts.length; i++) {
                    for (var j = 0; j < muts[i].addedNodes.length; j++) {
                        var n = muts[i].addedNodes[j];
                        if (n.nodeType === 1) { if (n.classList.contains('badge')) scan(n.parentNode || document); else scan(n); }
                    }
                }
            });
            mo.observe(document.body, { childList: true, subtree: true });
        }
    });

    // ── ink blot where a button is pressed ──
    if (!reduced) {
        document.addEventListener('pointerdown', function (e) {
            var b = e.target.closest && e.target.closest('.btn');
            if (!b) return;
            var r = b.getBoundingClientRect();
            var s = document.createElement('span');
            s.className = 'ln-ink';
            s.style.left = (e.clientX - r.left) + 'px';
            s.style.top = (e.clientY - r.top) + 'px';
            b.appendChild(s);
            setTimeout(function () { s.remove(); }, 600);
        }, true);
    }
})();
