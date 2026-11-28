/* Chronicle layout behaviour: the sidebar (collapse, rail tooltips, "More"
   group, account menu), the phone "More" sheet, the day jump, the "/" search
   shortcut and the shared date context. Markup: layouts/chronicle/shell.html.

   window.RoamlyChronicle
     .selectedDate          'YYYY-MM-DD' or null (null = the latest day)
     .setDate(iso, source)  set it; listeners get (iso, source). On /timeline/
                            it is mirrored to ?d= (replaceState), elsewhere to
                            sessionStorage so the next timeline visit can use it.
     .jumpTo(iso)           the "Jump to day" action: on /timeline/ it selects the
                            day (listeners always fire, even for the same day);
                            elsewhere it remembers the day and navigates to
                            /timeline/?date=iso. Future days clamp to today.
     .onDate(cb)            subscribe; returns an unsubscribe function
     .today()               the browser's local 'YYYY-MM-DD'
     .parse(iso)            local-noon Date for an ISO day, or null
     .format(iso, opts)     toLocaleDateString of that day
     .isTimeline            true on /timeline/
     .collapse(bool)        rail (true) / full (false) sidebar, remembered
     .isCollapsed()         true while the sidebar is the 64px rail
   /timeline/ reads the selected day from ?d= or ?date=.

   Overflow menus for pages: any [data-ch-menu] wrapper holding one toggle
   [aria-haspopup][aria-controls] and a [hidden] panel of [role="menuitem"]
   links/buttons works with no setup (delegated, so dynamic markup is fine).

   Loaded with `defer` from the style layer, so it runs after the shell markup
   is parsed and before DOMContentLoaded. Pages that need the API should wait
   for DOMContentLoaded. */
(function () {
    'use strict';
    var de = document.documentElement;
    if (de.getAttribute('data-layout') !== 'chronicle') return;

    var ISO = /^\d{4}-\d{2}-\d{2}$/;
    var STORE = 'roamly_ch_date';
    var SIDE_KEY = 'roamly_ch_side';
    var MORE_KEY = 'roamly_ch_more';
    var isTimeline = /^\/timeline\/?$/.test(location.pathname);
    var phoneMq = window.matchMedia('(max-width: 768px)');

    function isPhone() { return phoneMq.matches; }
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
    function lsSet(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }

    // ── Shared date context ───────────────────────────────────────────
    var listeners = [];
    var selected = null;
    if (isTimeline) {
        var params = new URLSearchParams(location.search);
        var q = params.get('d') || params.get('date');
        selected = parse(q) ? q : null;
        if (selected && params.has('date')) {
            // Normalise ?date= to the ?d= the page mirrors from now on.
            try {
                var nu = new URL(location.href);
                nu.searchParams.delete('date'); nu.searchParams.set('d', selected);
                history.replaceState(history.state, '', nu.pathname + nu.search + nu.hash);
            } catch (e) {}
        }
    } else {
        var s = storeGet();
        selected = parse(s) ? s : null;
    }

    function apply(next, source, force) {
        if (next === selected && !force) return;
        selected = next;
        if (isTimeline) {
            try {
                var u = new URL(location.href);
                u.searchParams.delete('date');
                if (next) u.searchParams.set('d', next); else u.searchParams.delete('d');
                history.replaceState(history.state, '', u.pathname + u.search + u.hash);
            } catch (e) {}
        }
        storeSet(next);
        syncJump();
        listeners.slice().forEach(function (cb) {
            try { cb(next, source || ''); } catch (e) { if (window.console) console.error(e); }
        });
    }
    function setDate(iso, source) { apply(parse(iso) ? iso : null, source, false); }
    function onDate(cb) {
        if (typeof cb !== 'function') return function () {};
        listeners.push(cb);
        return function () { listeners = listeners.filter(function (x) { return x !== cb; }); };
    }
    function jumpTo(iso) {
        if (!parse(iso)) return false;
        if (iso > today()) iso = today();
        if (isTimeline) {
            apply(iso, 'jump', true);
        } else {
            storeSet(iso);
            location.href = '/timeline/?date=' + encodeURIComponent(iso);
        }
        return true;
    }

    // ── Sidebar collapse ──────────────────────────────────────────────
    var side = document.getElementById('lx-ch-side');
    var collapseBtn = document.getElementById('lx-ch-collapse');
    function isCollapsed() { return de.getAttribute('data-ch-side') === 'rail'; }
    function syncCollapseBtn() {
        if (!collapseBtn) return;
        var c = isCollapsed();
        var label = c ? 'Expand sidebar' : 'Collapse sidebar';
        collapseBtn.setAttribute('aria-expanded', c ? 'false' : 'true');
        collapseBtn.setAttribute('aria-label', label);
        collapseBtn.setAttribute('title', label);
        syncMore();
    }
    function collapse(on) {
        on = !!on;
        if (on === isCollapsed()) return;
        if (on) de.setAttribute('data-ch-side', 'rail'); else de.removeAttribute('data-ch-side');
        lsSet(SIDE_KEY, on ? '1' : '0');
        syncCollapseBtn();
        hideTip();
        closeAllMenus(null, false);
        // The content column changed width without a window resize; maps and
        // charts that size themselves on `resize` need to hear about it.
        try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    }
    syncCollapseBtn();
    if (collapseBtn) collapseBtn.addEventListener('click', function () { collapse(!isCollapsed()); });

    window.RoamlyChronicle = {
        get selectedDate() { return selected; },
        setDate: setDate,
        jumpTo: jumpTo,
        onDate: onDate,
        today: today,
        parse: parse,
        format: format,
        isTimeline: isTimeline,
        collapse: collapse,
        isCollapsed: isCollapsed
    };

    // ── Rail tooltips ─────────────────────────────────────────────────
    // A fixed element on <body>, not a ::after: the nav list scrolls, and a
    // scroll container clips anything that pokes out sideways.
    var tip = null, tipTimer = 0;
    function hideTip() { clearTimeout(tipTimer); if (tip) tip.hidden = true; }
    function tipText(el) {
        if (el.classList && el.classList.contains('yago-chip')) {
            var line = document.getElementById('yago-line');
            return '1 year ago' + (line && line.textContent ? ': ' + line.textContent : '');
        }
        return el.getAttribute('data-tip');
    }
    function showTip(el, delay) {
        clearTimeout(tipTimer);
        if (!isCollapsed() || isPhone() || anyMenuOpen()) { hideTip(); return; }
        tipTimer = setTimeout(function () {
            var text = tipText(el);
            if (!text) return;
            if (!tip) {
                tip = document.createElement('div');
                tip.className = 'lx-ch-tip';
                tip.setAttribute('aria-hidden', 'true');
                document.body.appendChild(tip);
            }
            var r = el.getBoundingClientRect();
            tip.textContent = text;
            tip.style.left = Math.round(r.right + 12) + 'px';
            tip.style.top = Math.round(r.top + r.height / 2 - 14) + 'px';
            tip.hidden = false;
        }, delay);
    }
    function tipTarget(t) {
        if (!t || !t.closest) return null;
        var el = t.closest('[data-tip], .yago-chip');
        return el && side && side.contains(el) ? el : null;
    }
    if (side) {
        side.addEventListener('mouseover', function (e) {
            var el = tipTarget(e.target);
            if (el) showTip(el, 250); else hideTip();
        });
        side.addEventListener('mouseleave', hideTip);
        side.addEventListener('focusin', function (e) {
            var el = tipTarget(e.target);
            if (el && e.target.matches && e.target.matches(':focus-visible')) showTip(el, 0); else hideTip();
        });
        side.addEventListener('focusout', hideTip);
        var navEl = side.querySelector('.lx-ch-nav');
        if (navEl) navEl.addEventListener('scroll', hideTip, { passive: true });
    }

    // ── "More" group (sidebar) ────────────────────────────────────────
    // html[data-ch-more] "1" open / "0" closed / absent = open only while the
    // current page lives inside it (CSS). The toggle always flips what is visible.
    var moreGroup = document.getElementById('lx-ch-more');
    var moreBtn = document.getElementById('lx-ch-more-toggle');
    var moreList = document.getElementById('lx-ch-more-list');
    function moreOpen() { return !!moreList && moreList.offsetParent !== null && !isCollapsed(); }
    function syncMore() { if (moreBtn) moreBtn.setAttribute('aria-expanded', moreOpen() ? 'true' : 'false'); }
    if (moreGroup && moreBtn && moreList) {
        syncMore();
        moreBtn.addEventListener('click', function () {
            var next = moreOpen() ? '0' : '1';
            de.setAttribute('data-ch-more', next);
            lsSet(MORE_KEY, next);
            syncMore();
        });
    }

    // ── Menus (account, page overflow menus) ──────────────────────────
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
    function allMenus() { return Array.prototype.slice.call(document.querySelectorAll('[data-ch-menu]')); }
    function closeAllMenus(except, focusBtn) {
        allMenus().forEach(function (m) { if (m !== except) closeMenu(m, !!focusBtn && m.contains(document.activeElement)); });
    }
    function anyMenuOpen() {
        return allMenus().some(function (m) { var p = menuParts(m); return p.panel && !p.panel.hidden; });
    }
    function openMenu(m, focus) {
        var p = menuParts(m);
        if (!p.btn || !p.panel) return;
        closeAllMenus(m, false);
        hideTip();
        p.panel.hidden = false;
        p.btn.setAttribute('aria-expanded', 'true');
        if (focus) {
            var list = menuItems(p.panel);
            var t = focus === 'last' ? list[list.length - 1] : list[0];
            if (t) t.focus();
        }
    }
    function menuItems(panel) {
        return Array.prototype.slice.call(panel.querySelectorAll('[role="menuitem"]'))
            .filter(function (a) { return a.offsetParent !== null && !a.disabled; });
    }
    document.addEventListener('click', function (e) {
        var t = e.target;
        if (!t || !t.closest) return;
        var toggle = t.closest('[data-ch-menu] [aria-haspopup]');
        if (toggle) {
            var m = toggle.closest('[data-ch-menu]');
            var p = menuParts(m);
            if (p.btn === toggle && p.panel) {
                e.stopPropagation();
                if (p.panel.hidden) openMenu(m, false); else closeMenu(m, false);
                return;
            }
        }
        if (!t.closest('[data-ch-menu]')) closeAllMenus(null, false);
        else if (t.closest('[role="menuitem"]')) closeAllMenus(null, false);
    });
    document.addEventListener('keydown', function (e) {
        var t = e.target;
        if (!t || !t.closest) return;
        var m = t.closest('[data-ch-menu]');
        if (!m) return;
        var p = menuParts(m);
        if (!p.btn || !p.panel) return;
        if (t === p.btn) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                openMenu(m, e.key === 'ArrowUp' ? 'last' : 'first');
            }
            return;
        }
        if (!p.panel.contains(t)) return;
        var list = menuItems(p.panel);
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

    // ── Day jump (sidebar + phone sheet) ──────────────────────────────
    var jumpInputs = Array.prototype.slice.call(document.querySelectorAll('[data-ch-jump]'));
    function syncJump() {
        var iso = selected || today();
        jumpInputs.forEach(function (inp) {
            inp.max = today();
            if (inp.value !== iso) inp.value = iso;
        });
    }
    function isOverlay(inp) {
        // In the rail the input is an invisible hit area over the calendar
        // icon; there a click has to ask for the picker itself.
        try { return parseFloat(getComputedStyle(inp).opacity) === 0; } catch (e) { return false; }
    }
    Array.prototype.forEach.call(document.querySelectorAll('.lx-ch-jump, .lx-ch-sheet-jump'), function (lab) {
        // The native indicator is hidden, so the calendar icon (anywhere on the
        // row but the field itself) opens the picker.
        lab.addEventListener('click', function (e) {
            var inp = lab.querySelector('[data-ch-jump]');
            if (!inp || e.target === inp) return;
            if (typeof inp.showPicker === 'function') { try { inp.showPicker(); } catch (x) {} }
        });
    });
    jumpInputs.forEach(function (inp) {
        inp.addEventListener('click', function () {
            if (isOverlay(inp) && typeof inp.showPicker === 'function') {
                try { inp.showPicker(); } catch (e) {}
            }
        });
        inp.addEventListener('change', function () {
            var iso = inp.value;
            if (!parse(iso)) { syncJump(); return; }
            if (closeSheet) closeSheet();
            jumpTo(iso);
        });
    });
    syncJump();

    // ── "/" opens search ──────────────────────────────────────────────
    document.addEventListener('keydown', function (e) {
        if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return;
        var t = e.target;
        var tag = t && t.tagName ? t.tagName.toLowerCase() : '';
        if (tag === 'input' || tag === 'textarea' || tag === 'select' || (t && t.isContentEditable)) return;
        if (document.querySelector('.rly-dialog-overlay.open, .ask-app')) return;  // Ask has its own "/"
        var own = document.getElementById('search-input');
        if (own) { e.preventDefault(); try { own.focus(); if (own.select) own.select(); } catch (x) {} return; }
        var link = document.getElementById('lx-ch-search');
        if (link && link.href) { e.preventDefault(); location.href = link.href; }
    });

    // ── Phone "More" sheet ────────────────────────────────────────────
    var sheet = document.getElementById('lx-ch-sheet');
    var scrim = document.getElementById('lx-ch-sheet-scrim');
    var opener = document.querySelector('[data-ch-sheet-open]');
    var lastFocus = null;
    var hideTimer = 0;
    var closeSheet = null;
    function reduced() {
        try { return !!(window.RoamlyStyle && window.RoamlyStyle.reducedMotion && window.RoamlyStyle.reducedMotion()); }
        catch (e) { return false; }
    }
    if (sheet && scrim && opener) {
        var sheetFocusables = function () {
            return Array.prototype.slice.call(sheet.querySelectorAll('a[href], button:not([disabled]), input:not([disabled])'))
                .filter(function (el) { return el.offsetParent !== null || el === document.activeElement; });
        };
        var openSheet = function () {
            clearTimeout(hideTimer);
            lastFocus = document.activeElement;
            sheet.hidden = false; scrim.hidden = false;
            // Next frame, so the transform transition runs from its closed state.
            requestAnimationFrame(function () {
                requestAnimationFrame(function () { sheet.classList.add('is-open'); scrim.classList.add('is-open'); });
            });
            opener.setAttribute('aria-expanded', 'true');
            de.style.overflow = 'hidden';
            var first = sheet.querySelector('.lx-ch-sheet-search') || sheetFocusables()[0];
            if (first) setTimeout(function () { try { first.focus({ preventScroll: true }); } catch (e) { first.focus(); } }, 30);
        };
        closeSheet = function () {
            if (sheet.hidden) return;
            sheet.classList.remove('is-open'); scrim.classList.remove('is-open');
            opener.setAttribute('aria-expanded', 'false');
            de.style.overflow = '';
            clearTimeout(hideTimer);
            hideTimer = setTimeout(function () { sheet.hidden = true; scrim.hidden = true; }, reduced() ? 0 : 220);
            if (lastFocus && lastFocus.focus) { try { lastFocus.focus({ preventScroll: true }); } catch (e) {} }
        };
        opener.addEventListener('click', function () { if (sheet.hidden) openSheet(); else closeSheet(); });
        scrim.addEventListener('click', closeSheet);
        Array.prototype.forEach.call(sheet.querySelectorAll('[data-ch-sheet-close]'), function (b) {
            b.addEventListener('click', closeSheet);
        });
        sheet.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                var np = document.getElementById('notif-panel');
                if (np && !np.hidden) return;   // base.html closes the bell first
                e.preventDefault(); closeSheet(); return;
            }
            if (e.key !== 'Tab') return;
            var f = sheetFocusables();
            if (!f.length) return;
            var first = f[0], last = f[f.length - 1];
            if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
        });
    }

    // ── Bell: sidebar foot on desktop, sheet header on phones ─────────
    function placeBell() {
        var bell = document.getElementById('notif');
        var slot = document.getElementById(isPhone() ? 'lx-ch-bell-sheet' : 'lx-ch-bell-side');
        if (bell && slot && bell.parentNode !== slot) slot.appendChild(bell);
    }
    placeBell();
    function onBreakpoint() {
        if (!isPhone() && closeSheet) closeSheet();
        placeBell();
        hideTip();
    }
    if (phoneMq.addEventListener) phoneMq.addEventListener('change', onBreakpoint);
    else if (phoneMq.addListener) phoneMq.addListener(onBreakpoint);

    // The bell is inside the closed sheet on a phone: mirror its unread dot on
    // the dock's More item so a pending notification is still visible.
    var dot = document.getElementById('notif-dot');
    var dockDot = document.getElementById('lx-ch-dock-dot');
    if (dot && dockDot && window.MutationObserver) {
        var syncDot = function () { dockDot.hidden = !!dot.hidden; };
        syncDot();
        new MutationObserver(syncDot).observe(dot, { attributes: true, attributeFilter: ['hidden'] });
    }

    // Escape: innermost first. A sidebar menu closes before anything else; the
    // bell and dialogs handle their own.
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') hideTip();
    });
})();
