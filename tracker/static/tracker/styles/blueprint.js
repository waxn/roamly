/* Blueprint — behaviour: the page title measures itself with a dimension
   line, hovered tiles get a width call-out, a faint crosshair follows the
   pointer with a coordinate readout, and a title block sits in the corner. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'blueprint') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();
    var fine = matchMedia('(hover: hover) and (pointer: fine)').matches;

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    ready(function () {
        // dimension line under the title, kept in sync with its width
        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (h) {
            var dim = document.createElement('span');
            dim.className = 'bp-dim';
            dim.setAttribute('aria-hidden', 'true');
            dim.innerHTML = '<b></b><i></i><b></b>';
            h.appendChild(dim);
            var label = dim.querySelector('i');
            var measure = function () { label.textContent = Math.round(h.getBoundingClientRect().width) + ' px'; };
            measure();
            if ('ResizeObserver' in window) new ResizeObserver(measure).observe(h);
        }

        // title block — only where there's a sheet to sign, not over a full map
        var fullMap = document.querySelector('.maplibregl-map');
        var hasSheet = document.querySelector('main .container');
        if (hasSheet && !(fullMap && fullMap.getBoundingClientRect().height > innerHeight * 0.5)) {
            var who = document.querySelector('.navbar-user-name');
            var tb = document.createElement('div');
            tb.className = 'bp-titleblock';
            tb.setAttribute('aria-hidden', 'true');
            var title = h ? h.childNodes[0] && h.childNodes[0].textContent.trim() : document.title;
            var rows = [
                ['Project', 'Roamly'],
                ['Drawing', (title || 'Sheet').slice(0, 22)],
                ['Drawn by', who ? who.textContent.trim() : '—'],
                ['Date', new Date().toISOString().slice(0, 10)],
                ['Scale', '1 : 1']
            ];
            tb.innerHTML = '<div class="bp-tb-name">ROAMLY</div>' + rows.map(function (r) { return '<div>' + r[0] + '</div><div></div>'; }).join('');
            var cells = tb.querySelectorAll('div:nth-child(2n+3)');
            rows.forEach(function (r, i) { if (cells[i]) cells[i].textContent = r[1]; });
            document.body.appendChild(tb);
        }

        if (!fine) return;

        // width call-out on hovered tiles
        var current = null, callout = null;
        document.addEventListener('pointerover', function (e) {
            var c = e.target.closest && e.target.closest('.card');
            if (c === current) return;
            if (callout) { callout.remove(); callout = null; }
            current = c;
            if (!c) return;
            var r = c.getBoundingClientRect();
            callout = document.createElement('div');
            callout.className = 'bp-callout';
            callout.setAttribute('aria-hidden', 'true');
            callout.innerHTML = '<span></span>';
            callout.firstChild.textContent = Math.round(r.width) + ' × ' + Math.round(r.height);
            c.appendChild(callout);
        }, true);

        if (reduced) return;
        // crosshair + readout
        var xh = document.createElement('div'), yh = document.createElement('div'), rd = document.createElement('div');
        xh.className = 'bp-xh'; yh.className = 'bp-yh'; rd.className = 'bp-coords';
        [xh, yh, rd].forEach(function (el) { el.setAttribute('aria-hidden', 'true'); el.style.display = 'none'; document.body.appendChild(el); });
        var mx = 0, my = 0, raf = 0;
        function paint() {
            raf = 0;
            xh.style.transform = 'translateY(' + my + 'px)';
            yh.style.transform = 'translateX(' + mx + 'px)';
            rd.style.transform = 'translate(' + (mx + 10) + 'px,' + (my + 8) + 'px)';
            rd.textContent = 'X ' + String(mx).padStart(4, '0') + '  Y ' + String(my + Math.round(scrollY)).padStart(4, '0');
        }
        addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            mx = Math.round(e.clientX); my = Math.round(e.clientY);
            var overMap = e.target.closest && e.target.closest('.maplibregl-map');
            var show = overMap ? 'none' : '';
            xh.style.display = yh.style.display = rd.style.display = show;
            if (!raf) raf = requestAnimationFrame(paint);
        }, { passive: true });
        document.addEventListener('pointerleave', function () { xh.style.display = yh.style.display = rd.style.display = 'none'; });
    });
})();
