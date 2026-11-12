/* Postmark — behaviour: a dated circular postmark by each page title, and
   wavy cancellation marks thunked onto some of the stamps. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'postmark') return;

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }
    var NS = 'http://www.w3.org/2000/svg';
    var uid = 0;

    function waves(x0, x1, rows, y0, gap) {
        var d = '';
        for (var r = 0; r < rows; r++) {
            var y = y0 + r * gap;
            d += 'M' + x0 + ' ' + y;
            for (var x = x0; x < x1; x += 12) d += ' q3 -4 6 0 t6 0';
        }
        return d;
    }

    function postmark(date) {
        var id = 'pm-ring-' + (++uid);
        var m = date.toLocaleDateString(undefined, { month: 'short' }).toUpperCase();
        var line1 = String(date.getDate()).padStart(2, '0') + ' ' + m;
        var line2 = String(date.getFullYear());
        return '<svg viewBox="0 0 168 96" fill="none" stroke="currentColor">' +
            '<path d="' + waves(2, 82, 5, 26, 11) + '" stroke-width="2.2"/>' +
            '<circle cx="124" cy="48" r="40" stroke-width="2.4"/><circle cx="124" cy="48" r="27" stroke-width="1.4"/>' +
            '<defs><path id="' + id + '" d="M124 48 m-33 0 a33 33 0 1 1 66 0 a33 33 0 1 1 -66 0"/></defs>' +
            '<text font-family="IBM Plex Mono, monospace" font-size="8.5" font-weight="600" letter-spacing="2.2" fill="currentColor" stroke="none"><textPath href="#' + id + '" startOffset="0">ROAMLY POST · PAR AVION ·</textPath></text>' +
            '<text x="124" y="46" text-anchor="middle" font-family="IBM Plex Mono, monospace" font-size="10" font-weight="600" fill="currentColor" stroke="none">' + line1 + '</text>' +
            '<text x="124" y="58" text-anchor="middle" font-family="IBM Plex Mono, monospace" font-size="9" fill="currentColor" stroke="none">' + line2 + '</text>' +
            '</svg>';
    }

    function cancel() {
        return '<svg viewBox="0 0 150 70" fill="none" stroke="currentColor" stroke-width="2.4">' +
            '<path d="' + waves(0, 90, 4, 16, 11) + '"/>' +
            '<circle cx="118" cy="34" r="27"/><circle cx="118" cy="34" r="19" stroke-width="1.2"/>' +
            '</svg>';
    }

    ready(function () {
        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (h) {
            var host = h.closest('.page-header, .page-toolbar');
            var pm = document.createElement('div');
            pm.className = 'pm-postmark';
            pm.setAttribute('aria-hidden', 'true');
            pm.innerHTML = postmark(new Date());
            host.appendChild(pm);
        }
        // cancel roughly every other stamp, deterministically per page
        var seed = location.pathname.length * 7 + 3;
        document.querySelectorAll('.card.stat-card, .grid > .card').forEach(function (c, i) {
            seed = (seed * 9301 + 49297) % 233280;
            if (seed / 233280 > 0.5) return;
            var cs = getComputedStyle(c);
            if (cs.maskImage === 'none' && cs.webkitMaskImage === 'none') return; // not a stamp
            var el = document.createElement('div');
            el.className = 'pm-cancel';
            el.setAttribute('aria-hidden', 'true');
            el.style.setProperty('--pm-rot', (-18 + (seed % 30)) + 'deg');
            el.style.animationDelay = (200 + i * 70) + 'ms';
            el.innerHTML = cancel();
            c.appendChild(el);
        });
    });
})();
