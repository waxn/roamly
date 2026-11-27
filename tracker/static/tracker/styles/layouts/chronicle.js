/* Chronicle layout behaviour: the shell's menus, the phone "More" sheet, the
   date jump, and the shared date context.

   window.RoamlyChronicle
     .selectedDate          'YYYY-MM-DD' or null (null = the latest day)
     .setDate(iso, source)  set it; listeners get (iso, source). On /timeline/
                            it is mirrored to ?d= (replaceState), elsewhere to
                            sessionStorage so the next timeline visit can use it.
     .onDate(cb)            subscribe; returns an unsubscribe function
     .today()               the browser's local 'YYYY-MM-DD'
     .parse(iso)            local-noon Date for an ISO day, or null
     .format(iso, opts)     toLocaleDateString of that day
     .isTimeline            true on /timeline/

   Loaded with `defer` from the style layer, so it runs after the shell markup
   is parsed and before DOMContentLoaded. Pages that need the API should wait
   for DOMContentLoaded. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-layout') !== 'chronicle') return;

    var ISO = /^\d{4}-\d{2}-\d{2}$/;
    var STORE = 'roamly_ch_date';
    var isTimeline = /^\/timeline\/?$/.test(location.pathname);

    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function isoOf(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
    function parse(iso) {
        if (typeof iso !== 'string' || !ISO.test(iso)) return null;
        var p = iso.split('-');
        var d = new Date(+p[0], +p[1] - 1, +p[2], 12, 0, 0);
        return isNaN(d.getTime()) || isoOf(d) !== iso ? null : d;
    }
    function today() { return isoOf(new Date()); }
    function format(iso, opts) {
        var d = parse(iso);
        if (!d) return '';
        try { return d.toLocaleDateString(undefined, opts || { day: 'numeric', month: 'short' }); }
        catch (e) { return iso; }
    }
    function storeGet() { try { return sessionStorage.getItem(STORE); } catch (e) { return null; } }
    function storeSet(v) { try { if (v) sessionStorage.setItem(STORE, v); else sessionStorage.removeItem(STORE); } catch (e) {} }

    // ── Shared date context ───────────────────────────────────────────
    var listeners = [];
    var selected = null;
    if (isTimeline) {
        var q = new URLSearchParams(location.search).get('d');
        selected = parse(q) ? q : null;
    } else {
        var s = storeGet();
        selected = parse(s) ? s : null;
    }

    function setDate(iso, source) {
        var next = parse(iso) ? iso : null;
        if (next === selected) return;
        selected = next;
        if (isTimeline) {
            try {
                var u = new URL(location.href);
                if (next) u.searchParams.set('d', next); else u.searchParams.delete('d');
                history.replaceState(history.state, '', u.pathname + u.search + u.hash);
            } catch (e) {}
        }
        storeSet(next);
        updateJumpLabel();
        listeners.slice().forEach(function (cb) {
            try { cb(next, source || ''); } catch (e) { if (window.console) console.error(e); }
        });
    }
    function onDate(cb) {
        if (typeof cb !== 'function') return function () {};
        listeners.push(cb);
        return function () { listeners = listeners.filter(function (x) { return x !== cb; }); };
    }

    window.RoamlyChronicle = {
        get selectedDate() { return selected; },
        setDate: setDate,
        onDate: onDate,
        today: today,
        parse: parse,
        format: format,
        isTimeline: isTimeline
    };

    // ── Date jump (top bar) ───────────────────────────────────────────
    var jumpInput = document.getElementById('lx-ch-datejump');
    var jumpLabel = document.getElementById('lx-ch-datejump-label');
    function updateJumpLabel() {
        if (!jumpLabel) return;
        var iso = selected || today();
        jumpLabel.textContent = iso === today() ? 'Today' : format(iso, { day: 'numeric', month: 'short' });
        if (jumpInput) jumpInput.value = iso;
    }
    if (jumpInput) {
        jumpInput.max = today();
        updateJumpLabel();
        jumpInput.addEventListener('click', function () {
            // Desktop browsers only open the calendar from its icon; ask for it.
            if (typeof jumpInput.showPicker === 'function') {
                try { jumpInput.showPicker(); } catch (e) {}
            }
        });
        jumpInput.addEventListener('change', function () {
            var iso = jumpInput.value;
            if (!parse(iso)) return;
            if (iso > today()) iso = today();
            if (isTimeline) {
                setDate(iso, 'jump');
            } else {
                storeSet(iso);
                location.href = '/timeline/?d=' + encodeURIComponent(iso);
            }
        });
    }

    // ── Menus (More, account) ─────────────────────────────────────────
    var menus = Array.prototype.slice.call(document.querySelectorAll('[data-ch-menu]'));
    function menuParts(m) {
        var btn = m.querySelector('[aria-haspopup]');
        var panel = btn ? document.getElementById(btn.getAttribute('aria-controls')) : null;
        return { btn: btn, panel: panel };
    }
    function closeMenu(m, focusBtn) {
        var p = menuParts(m);
        if (!p.btn || !p.panel || p.panel.hidden) return;
        p.panel.hidden = true;
        p.btn.setAttribute('aria-expanded', 'false');
        if (focusBtn) p.btn.focus();
    }
    function closeAll(except) { menus.forEach(function (m) { if (m !== except) closeMenu(m, false); }); }
    function items(panel) {
        return Array.prototype.slice.call(panel.querySelectorAll('a[role="menuitem"]'))
            .filter(function (a) { return a.offsetParent !== null; });
    }
    menus.forEach(function (m) {
        var p = menuParts(m);
        if (!p.btn || !p.panel) return;
        p.btn.addEventListener('click', function (e) {
            e.stopPropagation();
            var open = p.panel.hidden;
            closeAll(m);
            p.panel.hidden = !open;
            p.btn.setAttribute('aria-expanded', open ? 'true' : 'false');
        });
        p.btn.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown') {
                e.preventDefault();
                closeAll(m);
                p.panel.hidden = false;
                p.btn.setAttribute('aria-expanded', 'true');
                var list = items(p.panel);
                if (list[0]) list[0].focus();
            }
        });
        p.panel.addEventListener('keydown', function (e) {
            var list = items(p.panel);
            var i = list.indexOf(document.activeElement);
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                if (!list.length) return;
                var n = e.key === 'ArrowDown' ? (i + 1) % list.length : (i <= 0 ? list.length - 1 : i - 1);
                list[n].focus();
            } else if (e.key === 'Home' && list[0]) {
                e.preventDefault(); list[0].focus();
            } else if (e.key === 'End' && list.length) {
                e.preventDefault(); list[list.length - 1].focus();
            } else if (e.key === 'Escape') {
                e.preventDefault(); closeMenu(m, true);
            } else if (e.key === 'Tab') {
                closeMenu(m, false);
            }
        });
    });
    document.addEventListener('click', function (e) {
        if (!e.target.closest || !e.target.closest('[data-ch-menu]')) closeAll(null);
    });
    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        menus.forEach(function (m) {
            var p = menuParts(m);
            if (p.panel && !p.panel.hidden) closeMenu(m, m.contains(document.activeElement));
        });
    });

    // ── Phone "More" sheet ────────────────────────────────────────────
    var sheet = document.getElementById('lx-ch-sheet');
    var scrim = document.getElementById('lx-ch-sheet-scrim');
    var opener = document.querySelector('[data-ch-sheet-open]');
    var lastFocus = null;
    var hideTimer = 0;
    function reduced() {
        try { return !!(window.RoamlyStyle && window.RoamlyStyle.reducedMotion && window.RoamlyStyle.reducedMotion()); }
        catch (e) { return false; }
    }
    function focusables() {
        return Array.prototype.slice.call(sheet.querySelectorAll('a[href], button:not([disabled])'));
    }
    function openSheet() {
        if (!sheet || !scrim) return;
        clearTimeout(hideTimer);
        lastFocus = document.activeElement;
        sheet.hidden = false; scrim.hidden = false;
        // Next frame, so the transform transition runs from its closed state.
        requestAnimationFrame(function () {
            requestAnimationFrame(function () { sheet.classList.add('is-open'); scrim.classList.add('is-open'); });
        });
        if (opener) opener.setAttribute('aria-expanded', 'true');
        document.documentElement.style.overflow = 'hidden';
        var f = focusables();
        var first = sheet.querySelector('.lx-ch-sheet-link') || f[0];
        if (first) setTimeout(function () { first.focus({ preventScroll: true }); }, 30);
    }
    function closeSheet() {
        if (!sheet || !scrim || sheet.hidden) return;
        sheet.classList.remove('is-open'); scrim.classList.remove('is-open');
        if (opener) opener.setAttribute('aria-expanded', 'false');
        document.documentElement.style.overflow = '';
        clearTimeout(hideTimer);
        hideTimer = setTimeout(function () { sheet.hidden = true; scrim.hidden = true; }, reduced() ? 0 : 220);
        if (lastFocus && lastFocus.focus) lastFocus.focus({ preventScroll: true });
    }
    if (sheet && scrim && opener) {
        opener.addEventListener('click', function () { if (sheet.hidden) openSheet(); else closeSheet(); });
        scrim.addEventListener('click', closeSheet);
        Array.prototype.forEach.call(sheet.querySelectorAll('[data-ch-sheet-close]'), function (b) {
            b.addEventListener('click', closeSheet);
        });
        sheet.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') { e.preventDefault(); closeSheet(); return; }
            if (e.key !== 'Tab') return;
            var f = focusables();
            if (!f.length) return;
            var first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        });
        // Growing past the phone breakpoint with the sheet open would strand it.
        var mq = window.matchMedia('(min-width: 769px)');
        var onMq = function () { if (mq.matches) closeSheet(); };
        if (mq.addEventListener) mq.addEventListener('change', onMq);
        else if (mq.addListener) mq.addListener(onMq);
    }
})();
