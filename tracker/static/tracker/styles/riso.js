/* Riso — behaviour: the ink layers slip out of register the faster you
   scroll (and settle back when you stop), and the active nav item gets
   circled in yellow marker, a different wobble every page load. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-style') !== 'riso') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    if (!reduced) {
        var lastY = scrollY, mis = 0, raf = 0, lastT = performance.now();
        function step(now) {
            var dt = Math.max(1, now - lastT); lastT = now;
            var v = Math.abs(scrollY - lastY) / dt; lastY = scrollY;
            mis = Math.min(7, mis * 0.86 + v * 2.2);
            root.style.setProperty('--mis', mis.toFixed(2) + 'px');
            raf = mis > 0.05 ? requestAnimationFrame(step) : 0;
            if (!raf) root.style.setProperty('--mis', '0px');
        }
        addEventListener('scroll', function () { if (!raf) { lastT = performance.now(); raf = requestAnimationFrame(step); } }, { passive: true });
    }

    function scribble() {
        // a loose loop that overshoots its start, like a quick marker circle
        var r = function (a) { return (Math.random() * 2 - 1) * a; };
        var pts = [];
        for (var i = 0; i <= 14; i++) {
            var t = i / 14 * Math.PI * 2.18 - 0.4;
            pts.push([50 + Math.cos(t) * (47 + r(3)), 50 + Math.sin(t) * (40 + r(4))]);
        }
        var d = 'M' + pts[0][0].toFixed(1) + ' ' + pts[0][1].toFixed(1);
        for (var j = 1; j < pts.length; j++) d += ' L' + pts[j][0].toFixed(1) + ' ' + pts[j][1].toFixed(1);
        return d;
    }

    ready(function () {
        document.querySelectorAll('.navbar-nav a.active, .navbar-nav-toggle.active').forEach(function (a) {
            var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('class', 'rs-circle');
            svg.setAttribute('viewBox', '0 0 100 100');
            svg.setAttribute('preserveAspectRatio', 'none');
            svg.setAttribute('aria-hidden', 'true');
            var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            p.setAttribute('d', scribble());
            p.setAttribute('vector-effect', 'non-scaling-stroke');
            svg.appendChild(p);
            a.appendChild(svg);
        });
    });
})();
