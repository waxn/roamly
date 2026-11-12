/* Transit — behaviour: split-flap stat numbers, an LED clock in the
   navbar, and a station-code roundel on each page title. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'transit') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    // ── split-flap: wrap each character of a plain-text stat value in a tile.
    // Pages rewrite .stat-value text as data lands, so a MutationObserver
    // re-wraps; it disconnects while writing so it never sees its own edits.
    var mo = null;
    function flap(el) {
        if (!el || el.children.length) {
            if (!el || el.querySelector(':not(.tr-flap)')) return;
        }
        var text = el.textContent;
        if (!text || !text.trim() || text.length > 16) return;
        if (el.dataset.trText === text && el.querySelector('.tr-flap')) return;
        el.dataset.trText = text;
        var frag = document.createDocumentFragment();
        var i = 0;
        Array.from(text).forEach(function (ch) {
            var s = document.createElement('span');
            s.className = 'tr-flap' + (/[\s,.:]/.test(ch) ? ' tr-sep' : '');
            s.textContent = ch;
            if (!reduced && !/\s/.test(ch)) {
                s.classList.add('tr-go');
                s.style.animationDelay = (i++ * 45) + 'ms';
            }
            frag.appendChild(s);
        });
        el.setAttribute('aria-label', text);
        el.dataset.trN = Array.from(text).length;
        el.style.setProperty('--tr-n', Math.max(4, Array.from(text).length));
        el.textContent = '';
        el.appendChild(frag);
    }
    function scan() {
        if (mo) mo.disconnect();
        document.querySelectorAll('.stat-value').forEach(flap);
        if (mo) mo.observe(document.body, { childList: true, subtree: true, characterData: true });
    }
    var pending = false;
    function schedule() { if (!pending) { pending = true; requestAnimationFrame(function () { pending = false; scan(); }); } }

    ready(function () {
        // station code roundel: first three letters of the title, Stats → STA
        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (h && !h.querySelector('.tr-code')) {
            var words = h.textContent.trim().split(/\s+/);
            var code = (words.length > 1 ? words.map(function (w) { return w[0]; }).join('') : words[0]).replace(/[^A-Za-z0-9]/g, '').slice(0, 3).toUpperCase();
            if (code) {
                var c = document.createElement('span');
                c.className = 'tr-code';
                c.setAttribute('aria-hidden', 'true');
                c.textContent = code;
                h.insertBefore(c, h.firstChild);
            }
        }

        // LED clock
        var end = document.querySelector('.navbar-end');
        if (end) {
            var clk = document.createElement('span');
            clk.className = 'tr-clock';
            clk.setAttribute('aria-hidden', 'true');
            end.insertBefore(clk, end.firstChild);
            var tick = function () {
                var d = new Date();
                clk.innerHTML = String(d.getHours()).padStart(2, '0') + '<span class="tr-colon">:</span>' + String(d.getMinutes()).padStart(2, '0');
            };
            tick();
            setInterval(tick, 10000);
        }

        mo = new MutationObserver(function (muts) {
            for (var i = 0; i < muts.length; i++) {
                var t = muts[i].target;
                var el = t.nodeType === 1 ? t : t.parentElement;
                if (el && (el.closest('.stat-value') || (el.querySelector && el.querySelector('.stat-value')))) { schedule(); return; }
            }
        });
        scan();
    });
})();
