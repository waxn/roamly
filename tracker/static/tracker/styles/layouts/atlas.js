/*
 * Atlas layout behaviour (Settings → Appearance → Layout → Atlas).
 * Loaded deferred by _style_layer.html on every page while the layout is
 * Atlas, in the top window AND inside every panel iframe:
 *
 *   top window   rail menus, the phone "More" sheet, moving the bell between
 *                the rail and the sheet, and on /map/ the panel: Journals /
 *                Places / Visits as an <iframe ?embed=1> over the map
 *                (desktop side panel, resizable; phone bottom sheet with
 *                peek / half / full snaps). ?panel=<id> deep-links it, Back
 *                closes it, Esc closes it.
 *   in a panel   html.lx-embedded; links and script navigations to pages that
 *                are not this panel go to the top window (a normal page refuses
 *                to be framed); Esc is forwarded to the parent.
 *
 * window.LxAtlas
 *   top:   { embedded:false, open(id[, {search}]), close(), isOpen() -> id|null,
 *            panelFor(pathname) -> id|null }
 *   panel: { embedded:true, close() }   (same as parent.postMessage({rlyPanel:'close'}, location.origin))
 * A page inside a panel that shows its own overlay can mark it open with a
 * data-lx-esc attribute so Esc closes that overlay and not the panel (the
 * usual .open modal/overlay/drawer/sheet classes are recognised already).
 */
(function () {
    'use strict';
    var de = document.documentElement;
    if (!de || de.getAttribute('data-layout') !== 'atlas') return;

    var PANEL_IDS = ['journals', 'places', 'visits'];
    var LS_WIDTH = 'roamly_atlas_panel_w';
    var W_MIN = 360, W_MAX = 720, W_DEFAULT = 440;
    var RAIL = 56;
    var OVERLAY_SEL = [
        '.rly-dialog-overlay.open', '.rly-select.open', '.rly-date.open', '.notif.open', '.map-ctx-menu.open',
        'dialog[open]', '[aria-modal="true"]', '[data-lx-esc]',
        '[class*="modal"].open', '[class*="overlay"].open', '[class*="lightbox"].open',
        '[class*="drawer"].open', '[class*="sheet"].open', '[class*="panel"].open'
    ].join(', ');

    function byId(id) { return document.getElementById(id); }
    function lsGet(k) { try { return window.localStorage.getItem(k); } catch (e) { return null; } }
    function lsSet(k, v) { try { window.localStorage.setItem(k, v); } catch (e) { /* private mode */ } }
    function media(q) {
        return window.matchMedia ? window.matchMedia(q) : { matches: false };
    }
    function onMedia(m, fn) {
        if (m.addEventListener) m.addEventListener('change', fn);
        else if (m.addListener) m.addListener(fn);
    }
    function isShown(el) {
        if (!el || !el.isConnected) return false;
        if (!(el.offsetWidth || el.offsetHeight || el.getClientRects().length)) return false;
        var v = el.ownerDocument.defaultView;
        return !v || v.getComputedStyle(el).visibility !== 'hidden';
    }
    function overlayOpen(doc, except) {
        var list;
        try { list = doc.querySelectorAll(OVERLAY_SEL); } catch (e) { return false; }
        for (var i = 0; i < list.length; i++) {
            if (except && except(list[i])) continue;
            if (isShown(list[i])) return true;
        }
        return false;
    }
    function focusEl(el) {
        if (!el) return;
        try { el.focus({ preventScroll: true }); } catch (e) { try { el.focus(); } catch (e2) { /* detached */ } }
    }
    function plainClick(e) {
        return !e.defaultPrevented && e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;
    }

    var embedded = false;
    try { embedded = window.self !== window.top; } catch (e) { embedded = true; }

    // ───────────────────────── Inside a panel ─────────────────────────
    // `win` is the panel's window. The panel wires itself (atlas.js runs in
    // it); the parent wires a framed page that came up without atlas.js.
    function topApi(win) {
        try { return win.top && win.top !== win ? (win.top.LxAtlas || null) : null; } catch (e) { return null; }
    }
    function carryLayout(win, url) {
        var keep = null;
        try { keep = new URLSearchParams(win.location.search).get('rly_layout'); } catch (e) { keep = null; }
        if (keep && !url.searchParams.has('rly_layout')) url.searchParams.set('rly_layout', keep);
    }
    function otherPanel(win, url) {
        // The id of the Atlas panel `url` belongs to, when that is not this page.
        if (url.origin !== win.location.origin || url.pathname === win.location.pathname) return null;
        var api = topApi(win);
        return api && typeof api.panelFor === 'function' && typeof api.open === 'function' ? api.panelFor(url.pathname) : null;
    }
    function routeFrom(win, url) {
        var here = win.location;
        if (url.origin === here.origin) {
            var pid = otherPanel(win, url);
            if (pid) {
                // Another panel page: the parent swaps panels.
                url.searchParams.delete('embed');
                url.searchParams.delete('rly_layout');
                topApi(win).open(pid, { search: url.search });
                return;
            }
            if (url.pathname === here.pathname || url.searchParams.get('embed') === '1') {
                // This page with other parameters (or an explicit embed link)
                // stays in the panel, without adding history to it.
                url.searchParams.set('embed', '1');
                carryLayout(win, url);
                win.location.replace(url.href);
                return;
            }
        }
        url.searchParams.delete('embed');
        try { win.top.location.assign(url.href); } catch (e) { win.open(url.href, '_top'); }
    }
    function wireFrame(win) {
        var doc;
        try { doc = win.document; } catch (e) { return; }
        if (!doc || doc.__lxAtlasWired) return;
        doc.__lxAtlasWired = true;

        // Links. Bubble phase, so a page script that handles its own link
        // (and calls preventDefault) keeps it.
        doc.addEventListener('click', function (e) {
            if (!plainClick(e)) return;
            var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
            if (!a) return;
            var target = (a.getAttribute('target') || '').toLowerCase();
            if (target && target !== '_self') return;
            if (a.hasAttribute('download')) return;
            var raw = (a.getAttribute('href') || '').trim();
            if (!raw || raw.charAt(0) === '#' || /^(javascript|mailto|tel|sms|blob|data):/i.test(raw)) return;
            var url;
            try { url = new URL(a.href, win.location.href); } catch (err) { return; }
            var here = win.location;
            if (url.origin === here.origin && url.pathname === here.pathname && url.search === here.search && url.hash) return;
            if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
            e.preventDefault();
            routeFrom(win, url);
        }, false);

        // Script navigations (location.href = …, GET forms) where the
        // Navigation API exists. Our own replace() calls carry embed=1, so they
        // pass straight through.
        var nav = win.navigation;
        if (nav && typeof nav.addEventListener === 'function') {
            nav.addEventListener('navigate', function (e) {
                // pushState/replaceState and hash moves stay in the page.
                if (e.destination && e.destination.sameDocument) return;
                if (!e.cancelable || e.hashChange || e.downloadRequest != null || e.formData) return;
                if (e.navigationType === 'reload' || e.navigationType === 'traverse') return;
                var url;
                try { url = new URL(e.destination.url); } catch (err) { return; }
                if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
                if (url.origin === win.location.origin && url.searchParams.get('embed') === '1' && !otherPanel(win, url)) return;
                e.preventDefault();
                routeFrom(win, url);
            });
        }

        // Esc: capture phase, so we see the page's overlays before its own
        // handler closes them; an open overlay keeps the Esc.
        win.addEventListener('keydown', function (e) {
            if (e.key !== 'Escape' || e.defaultPrevented) return;
            if (overlayOpen(doc)) return;
            try { win.parent.postMessage({ rlyPanel: 'close' }, win.location.origin); } catch (err) { /* parent gone */ }
        }, true);
    }

    if (embedded) {
        de.classList.add('lx-embedded');
        window.__RLY_ATLAS_CHILD__ = true;
        wireFrame(window);
        window.LxAtlas = {
            embedded: true,
            close: function () {
                try { window.parent.postMessage({ rlyPanel: 'close' }, window.location.origin); } catch (e) { /* parent gone */ }
            }
        };
        return;
    }

    // ───────────────────────── Top window ─────────────────────────
    var shell = byId('lx-at-shell');
    if (!shell) {
        // A standalone page (map editor, public adventure): no chrome to drive.
        window.LxAtlas = { embedded: false, open: function () {}, close: function () {}, isOpen: function () { return null; }, panelFor: function () { return null; } };
        return;
    }
    var phoneMq = media('(max-width: 768px)');

    // ── Rail popovers (More, Account) ──
    var openMenu = null;
    function menuItems(menu) {
        return Array.prototype.slice.call(menu.querySelectorAll('[role="menuitem"]'));
    }
    function closeMenu(refocus) {
        if (!openMenu) return;
        var m = openMenu;
        openMenu = null;
        m.menu.hidden = true;
        m.btn.setAttribute('aria-expanded', 'false');
        if (refocus) focusEl(m.btn);
    }
    function showMenu(m, focusWhich) {
        if (openMenu && openMenu !== m) closeMenu(false);
        openMenu = m;
        m.menu.hidden = false;
        m.btn.setAttribute('aria-expanded', 'true');
        if (focusWhich) {
            var items = menuItems(m.menu);
            if (items.length) focusEl(items[focusWhich === 'last' ? items.length - 1 : 0]);
        }
    }
    function setupMenu(btnId, menuId) {
        var btn = byId(btnId), menu = byId(menuId);
        if (!btn || !menu) return;
        var m = { btn: btn, menu: menu };
        btn.addEventListener('click', function (e) {
            if (openMenu === m) closeMenu(false);
            else showMenu(m, e.detail === 0 ? 'first' : null); // e.detail 0: Enter/Space
        });
        btn.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                showMenu(m, e.key === 'ArrowUp' ? 'last' : 'first');
            }
        });
        menu.addEventListener('keydown', function (e) {
            var items = menuItems(menu);
            if (!items.length) return;
            var i = items.indexOf(document.activeElement);
            if (e.key === 'ArrowDown') { e.preventDefault(); focusEl(items[(i + 1) % items.length]); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); focusEl(items[(i - 1 + items.length) % items.length]); }
            else if (e.key === 'Home') { e.preventDefault(); focusEl(items[0]); }
            else if (e.key === 'End') { e.preventDefault(); focusEl(items[items.length - 1]); }
            else if (e.key === 'Tab') { closeMenu(false); }
        });
        menu.addEventListener('click', function (e) {
            if (e.target.closest && e.target.closest('[role="menuitem"]')) closeMenu(false);
        });
    }
    setupMenu('lx-at-more-btn', 'lx-at-more-menu');
    setupMenu('lx-at-acct-btn', 'lx-at-acct-menu');
    document.addEventListener('click', function (e) {
        if (openMenu && !openMenu.menu.contains(e.target) && !openMenu.btn.contains(e.target)) closeMenu(false);
    });

    // ── Phone "More" sheet ──
    var sheet = byId('lx-at-sheet');
    var sheetBtn = byId('lx-at-sheet-btn');
    var scrim = byId('lx-at-scrim');
    var sheetOpen = false;
    function sheetFocusables() {
        if (!sheet) return [];
        return Array.prototype.filter.call(
            sheet.querySelectorAll('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
            isShown);
    }
    function openSheet() {
        if (!sheet || sheetOpen) return;
        closeMenu(false);
        sheetOpen = true;
        sheet.style.removeProperty('transform');
        de.classList.add('lx-at-sheet-open');
        if (sheetBtn) sheetBtn.setAttribute('aria-expanded', 'true');
        requestAnimationFrame(function () { if (sheetOpen) focusEl(byId('lx-at-sheet-close')); });
    }
    function closeSheet(refocus) {
        if (!sheet || !sheetOpen) return;
        sheetOpen = false;
        sheet.classList.remove('is-dragging');
        sheet.style.removeProperty('transform');
        de.classList.remove('lx-at-sheet-open');
        if (sheetBtn) sheetBtn.setAttribute('aria-expanded', 'false');
        if (refocus) focusEl(sheetBtn);
    }
    if (sheet && sheetBtn) {
        sheetBtn.addEventListener('click', function () { if (sheetOpen) closeSheet(true); else openSheet(); });
        var sheetClose = byId('lx-at-sheet-close');
        if (sheetClose) sheetClose.addEventListener('click', function () { closeSheet(true); });
        if (scrim) scrim.addEventListener('click', function () { closeSheet(true); });
        sheet.addEventListener('keydown', function (e) {
            if (e.key !== 'Tab' || !sheetOpen) return;
            var f = sheetFocusables();
            if (!f.length) return;
            var first = f[0], last = f[f.length - 1];
            if (e.shiftKey && (document.activeElement === first || !sheet.contains(document.activeElement))) { e.preventDefault(); focusEl(last); }
            else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); focusEl(first); }
        });
        // Swipe the header down to dismiss.
        var sheetHead = sheet.querySelector('.lx-at-sheet-head');
        var sheetGrip = sheet.querySelector('.lx-at-sheet-grip');
        [sheetHead, sheetGrip].forEach(function (zone) {
            if (!zone) return;
            zone.addEventListener('pointerdown', function (e) {
                if (!sheetOpen || e.button !== 0 || (e.target.closest && e.target.closest('a, button, .notif'))) return;
                var startY = e.clientY, dy = 0, moved = false;
                try { zone.setPointerCapture(e.pointerId); } catch (err) { /* capture unsupported */ }
                function move(ev) {
                    dy = Math.max(0, ev.clientY - startY);
                    if (!moved && dy < 4) return;
                    moved = true;
                    sheet.classList.add('is-dragging');
                    sheet.style.transform = 'translateY(' + dy + 'px)';
                }
                function end(ev) {
                    zone.removeEventListener('pointermove', move);
                    zone.removeEventListener('pointerup', end);
                    zone.removeEventListener('pointercancel', end);
                    try { zone.releasePointerCapture(ev.pointerId); } catch (err) { /* already released */ }
                    if (!moved) return;
                    sheet.classList.remove('is-dragging');
                    if (ev.type !== 'pointercancel' && dy > 80) closeSheet(true);
                    else sheet.style.removeProperty('transform');
                }
                zone.addEventListener('pointermove', move);
                zone.addEventListener('pointerup', end);
                zone.addEventListener('pointercancel', end);
            });
        });
    }

    // ── The bell lives once: in the rail on desktop, in the sheet on phones ──
    function placeNotif() {
        var n = byId('notif');
        if (!n) return;
        var slot = shell.querySelector('[data-lx-slot="' + (phoneMq.matches ? 'sheet' : 'rail') + '"]');
        if (slot && n.parentNode !== slot) slot.appendChild(n);
    }
    placeNotif();
    var notifDot = byId('notif-dot'), sheetDot = byId('lx-at-sheet-dot');
    function syncDot() {
        if (!sheetDot) return;
        var on = !!(notifDot && !notifDot.hidden);
        sheetDot.hidden = !on;
        if (sheetBtn) {
            if (on) sheetBtn.setAttribute('aria-label', 'More, new notifications');
            else sheetBtn.removeAttribute('aria-label');
        }
    }
    if (notifDot && window.MutationObserver) {
        new MutationObserver(syncDot).observe(notifDot, { attributes: true, attributeFilter: ['hidden'] });
    }
    syncDot();

    // ── Panels ──
    var panel = byId('lx-at-panel');
    var PANELS = {};
    var triggers = Array.prototype.slice.call(shell.querySelectorAll('[data-lx-panel]'));
    triggers.forEach(function (t) {
        var id = t.getAttribute('data-lx-panel');
        if (PANEL_IDS.indexOf(id) === -1 || PANELS[id]) return;
        var href = t.getAttribute('href');
        if (!href) return;
        var path;
        try { path = new URL(href, location.href).pathname; } catch (e) { return; }
        PANELS[id] = {
            id: id,
            href: href,
            path: path,
            label: (t.getAttribute('data-tip') || t.textContent || id).trim(),
            kicker: t.getAttribute('data-lx-kicker') || ''
        };
    });
    // Labels from the tab bar / sheet are text; the rail's come from data-tip.
    function panelFor(pathname) {
        for (var k in PANELS) if (Object.prototype.hasOwnProperty.call(PANELS, k) && PANELS[k].path === pathname) return k;
        return null;
    }

    var api = {
        embedded: false,
        open: function (id, opts) {
            var P = PANELS[id];
            if (!P) return;
            if (panel) { openPanel(id, opts || {}); return; }
            var u = new URL(P.href, location.href);
            if (opts && opts.search) u.search = opts.search;
            location.assign(u.pathname + u.search + u.hash);
        },
        close: function () { if (panel) closePanel({ focus: true }); },
        isOpen: function () { return panel ? cur : null; },
        panelFor: panelFor
    };
    window.LxAtlas = api;

    // Shared Escape handling, capture phase, so this decides before base.html's
    // handlers act: a rail menu first, then the sheet, then the panel.
    window.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape' || e.defaultPrevented) return;
        if (openMenu) { closeMenu(true); return; }
        var notif = byId('notif');
        if (sheetOpen) {
            if (notif && notif.classList.contains('open')) return; // base.html closes the bell first
            if (overlayOpen(document, function (el) { return el === sheet || (sheet && sheet.contains(el)); })) return;
            closeSheet(true);
            return;
        }
        if (panel && cur) {
            if (overlayOpen(document, function (el) { return el === sheet; })) return;
            closePanel({ focus: true });
        }
    }, true);

    onMedia(phoneMq, function () {
        placeNotif();
        if (!phoneMq.matches) closeSheet(false);
        if (panel && cur && phoneMq.matches) setSnap(snap, false);
    });

    if (!panel) return; // every page but the map: triggers stay plain links

    var head = byId('lx-at-panel-head');
    var body = byId('lx-at-panel-body');
    var titleEl = byId('lx-at-panel-title');
    var kickerEl = byId('lx-at-panel-kicker');
    var fullLink = byId('lx-at-panel-full');
    var closeBtn = byId('lx-at-panel-close');
    var msg = byId('lx-at-panel-msg');
    var msgText = byId('lx-at-panel-msg-text');
    var msgLink = byId('lx-at-panel-msg-link');
    var resizer = byId('lx-at-panel-resize');
    var grip = byId('lx-at-panel-grip');
    if (!head || !body || !titleEl) return;

    var cur = null;          // open panel id
    var active = null;       // its frame record
    var frames = [];         // at most two: the current one and the one before
    var pushed = false;      // the open panel has its own history entry
    var opener = null;       // what had focus when the panel opened
    var slowTimer = 0;
    var snap = 'half';
    var visTimer = 0;
    var dragEndAt = 0;

    triggers.forEach(function (t) {
        if (!PANELS[t.getAttribute('data-lx-panel')]) return;
        t.setAttribute('aria-controls', 'lx-at-panel');
        t.setAttribute('aria-expanded', 'false');
    });

    function embedSrc(P, search) {
        var u = new URL(P.href, location.href);
        if (search) u.search = search;
        u.searchParams.delete('panel');
        u.searchParams.set('embed', '1');
        carryLayout(window, u);
        return u.pathname + u.search + u.hash;
    }
    function fullHref() {
        var src = active ? active.src : (cur && PANELS[cur] ? PANELS[cur].href : location.pathname);
        if (active && active.loaded) {
            try { src = active.el.contentWindow.location.href; } catch (e) { /* keep src */ }
        }
        var u;
        try { u = new URL(src, location.href); } catch (e) { return location.pathname; }
        u.searchParams.delete('embed');
        u.searchParams.delete('rly_layout');
        return u.pathname + u.search + u.hash;
    }
    function updateLinks() {
        var h = fullHref();
        if (fullLink) fullLink.setAttribute('href', h);
        if (msgLink) msgLink.setAttribute('href', h);
    }
    function showMsg(kind) {
        if (!msg || !active) return;
        var label = PANELS[active.id] ? PANELS[active.id].label : 'this page';
        msg.classList.toggle('is-soft', kind === 'slow');
        if (msgText) {
            msgText.textContent = kind === 'slow'
                ? label + ' is taking a while to load.'
                : label + ' could not be shown here. Open it as a full page instead.';
        }
        panel.classList.toggle('has-msg', kind !== 'slow');
        msg.hidden = false;
    }
    function hideMsg() {
        if (msg) msg.hidden = true;
        panel.classList.remove('has-msg');
    }
    function syncFrames() {
        frames.forEach(function (f) {
            var on = f === active;
            f.el.classList.toggle('is-current', on);
            f.el.classList.toggle('is-loaded', f.loaded);
            f.el.tabIndex = on ? 0 : -1;
            if (on) f.el.removeAttribute('aria-hidden'); else f.el.setAttribute('aria-hidden', 'true');
        });
        panel.classList.toggle('is-ready', !!(active && active.loaded));
        body.setAttribute('aria-busy', active && !active.loaded && !active.failed ? 'true' : 'false');
        clearTimeout(slowTimer);
        if (active && active.failed) showMsg('fail');
        else if (active && !active.loaded) {
            hideMsg();
            var waiting = active;
            slowTimer = setTimeout(function () {
                if (active === waiting && !waiting.loaded && !waiting.failed && cur) showMsg('slow');
            }, 12000);
        } else hideMsg();
        updateLinks();
    }
    function dropFrame(f) {
        var i = frames.indexOf(f);
        if (i !== -1) frames.splice(i, 1);
        if (f.el.parentNode) f.el.parentNode.removeChild(f.el);
    }
    function onFrameLoad(f) {
        if (frames.indexOf(f) === -1) return;
        var win = null, href = '';
        try { win = f.el.contentWindow; href = win ? win.location.href : ''; } catch (e) { win = null; href = ''; }
        if (href === 'about:blank') return; // the initial empty document
        var ok = false;
        if (win && href) {
            // Unreadable (no win/href) means the page refused to be framed or
            // the browser showed its own error page; so does another origin
            // (Firefox's about:neterror is readable).
            var u;
            try { u = new URL(href); } catch (e) { u = null; }
            if (u && u.origin === location.origin && u.searchParams.get('embed') === '1') {
                ok = true;
            } else if (u && u.origin === location.origin) {
                // Left the panel (a redirect, or a script navigation the
                // browser could not intercept): it belongs in the full window.
                // A hidden, older frame doing that is simply dropped.
                if (f === active && cur) {
                    u.searchParams.delete('rly_layout');
                    location.assign(u.href);
                } else {
                    dropFrame(f);
                }
                return;
            }
        }
        f.loaded = ok;
        f.failed = !ok;
        if (ok) {
            try {
                if (!win.__RLY_ATLAS_CHILD__) {
                    win.document.documentElement.classList.add('lx-embedded');
                    wireFrame(win);
                }
            } catch (e) { /* the document went away */ }
        }
        if (f === active) syncFrames();
    }
    function makeFrame(id, src) {
        var el = document.createElement('iframe');
        var f = { id: id, src: src, el: el, loaded: false, failed: false };
        el.className = 'lx-at-frame';
        el.title = PANELS[id].label;
        el.addEventListener('load', function () { onFrameLoad(f); });
        el.setAttribute('src', src);
        body.appendChild(el);
        frames.push(f);
        // Keep the newest two (switching back is then instant); drop the rest.
        while (frames.length > 2) dropFrame(frames[0] === f ? frames[1] : frames[0]);
        return f;
    }
    function frameFor(id, src, exact) {
        for (var i = frames.length - 1; i >= 0; i--) {
            var f = frames[i];
            if (f.id === id && !f.failed && (!exact || f.src === src)) {
                frames.splice(i, 1);
                frames.push(f);
                return f;
            }
        }
        return null;
    }
    function setTriggers() {
        triggers.forEach(function (t) {
            if (PANELS[t.getAttribute('data-lx-panel')]) t.setAttribute('aria-expanded', t.getAttribute('data-lx-panel') === cur ? 'true' : 'false');
        });
    }
    function urlWithPanel(id) {
        var u = new URL(location.href);
        if (id) u.searchParams.set('panel', id); else u.searchParams.delete('panel');
        return u.pathname + u.search + u.hash;
    }

    function openPanel(id, opts) {
        var P = PANELS[id];
        if (!P) return;
        opts = opts || {};
        closeMenu(false);
        closeSheet(false);
        var wasOpen = !!cur;
        var src = embedSrc(P, opts.search || '');
        var f = frameFor(id, src, !!opts.search) || makeFrame(id, src);
        active = f;
        cur = id;
        titleEl.textContent = P.label;
        if (kickerEl) { kickerEl.textContent = P.kicker; kickerEl.hidden = !P.kicker; }
        if (!wasOpen) {
            var a = opts.opener || document.activeElement;
            opener = a && a !== document.body && !panel.contains(a) ? a : null;
            if (opts.instant) {
                de.classList.add('lx-at-noanim');
                requestAnimationFrame(function () { requestAnimationFrame(function () { de.classList.remove('lx-at-noanim'); }); });
            }
            if (phoneMq.matches) setSnap('half', false);
            de.classList.add('lx-at-panel-open');
        }
        setTriggers();
        syncFrames();
        if (opts.history !== false) {
            try {
                if (!wasOpen) { history.pushState({ lxPanel: id }, '', urlWithPanel(id)); pushed = true; }
                else history.replaceState(pushed ? { lxPanel: id } : history.state, '', urlWithPanel(id));
            } catch (e) { /* history unavailable (sandbox) */ }
        }
        if (!wasOpen && opts.focus !== false) focusEl(panel);
    }

    function closePanel(opts) {
        if (!cur) return;
        opts = opts || {};
        var hadFocus = panel.contains(document.activeElement);
        cur = null;
        clearTimeout(slowTimer);
        clearTimeout(visTimer);
        panel.classList.remove('is-dragging', 'is-resizing');
        de.classList.remove('lx-at-panel-open');
        setTriggers();
        // A frame that failed is not worth keeping; the others stay for a quick reopen.
        if (active && active.failed) { dropFrame(active); active = null; }
        if (opts.history !== false) {
            try {
                if (pushed) { pushed = false; history.back(); }
                else history.replaceState(history.state, '', urlWithPanel(null));
            } catch (e) { /* history unavailable */ }
        }
        pushed = opts.history === false ? false : pushed;
        if (opts.focus || hadFocus) {
            var back = opener && isShown(opener) ? opener : (phoneMq.matches ? sheetBtn : null);
            if (back) focusEl(back);
            else if (hadFocus) focusEl(byId('main-content'));
        }
        opener = null;
    }

    function panelFromUrl() {
        var p = null;
        try { p = new URLSearchParams(location.search).get('panel'); } catch (e) { p = null; }
        return p && PANELS[p] ? p : null;
    }
    window.addEventListener('popstate', function () {
        var id = panelFromUrl();
        if (id && id !== cur) {
            openPanel(id, { history: false, focus: false });
            pushed = !!(history.state && history.state.lxPanel);
        } else if (!id && cur) {
            closePanel({ history: false });
        }
    });

    // Triggers: rail, tab bar and sheet. On the map page they open the panel.
    shell.addEventListener('click', function (e) {
        var t = e.target && e.target.closest ? e.target.closest('[data-lx-panel]') : null;
        if (!t || !plainClick(e)) return;
        var id = t.getAttribute('data-lx-panel');
        if (!PANELS[id]) return;
        e.preventDefault();
        if (cur === id) { closePanel({ focus: false }); return; }
        openPanel(id, { opener: isShown(t) && !(sheet && sheet.contains(t)) ? t : sheetBtn });
    });
    // On the map, "Map" closes the panel instead of reloading the page.
    Array.prototype.forEach.call(shell.querySelectorAll('a[aria-current="page"]'), function (a) {
        if (a.hasAttribute('data-lx-panel')) return;
        var path;
        try { path = new URL(a.href, location.href).pathname; } catch (e) { return; }
        if (path !== location.pathname) return;
        a.addEventListener('click', function (e) {
            if (!plainClick(e) || !cur) return;
            e.preventDefault();
            closePanel({ focus: false });
        });
    });

    if (closeBtn) closeBtn.addEventListener('click', function () { closePanel({ focus: true }); });
    if (fullLink) fullLink.addEventListener('click', function () { fullLink.setAttribute('href', fullHref()); });
    if (msgLink) msgLink.addEventListener('click', function () { msgLink.setAttribute('href', fullHref()); });

    window.addEventListener('message', function (e) {
        if (e.origin !== location.origin || !cur || !active) return;
        var d = e.data;
        if (!d || typeof d !== 'object' || d.rlyPanel !== 'close') return;
        var src = null;
        try { src = active.el.contentWindow; } catch (err) { src = null; }
        if (!src || e.source !== src) return;
        closePanel({ focus: true });
    });

    // ── Desktop width ──
    var wanted = parseInt(lsGet(LS_WIDTH), 10);
    if (!(wanted >= W_MIN && wanted <= W_MAX)) wanted = W_DEFAULT;
    var width = wanted;
    function maxWidth() { return Math.max(W_MIN, Math.min(W_MAX, window.innerWidth - RAIL - 24 - 240)); }
    function clampWidth(w) { return Math.round(Math.min(maxWidth(), Math.max(W_MIN, w))); }
    function applyWidth(w) {
        width = w;
        de.style.setProperty('--lx-at-panel-w', w + 'px');
        if (resizer) {
            resizer.setAttribute('aria-valuenow', String(w));
            resizer.setAttribute('aria-valuemax', String(maxWidth()));
        }
    }
    function saveWidth() { wanted = width; lsSet(LS_WIDTH, String(width)); }
    applyWidth(clampWidth(wanted));
    if (resizer) {
        resizer.addEventListener('pointerdown', function (e) {
            if (e.button !== 0 || phoneMq.matches) return;
            e.preventDefault();
            var startX = e.clientX, startW = width;
            try { resizer.setPointerCapture(e.pointerId); } catch (err) { /* capture unsupported */ }
            panel.classList.add('is-resizing');
            function move(ev) { applyWidth(clampWidth(startW + ev.clientX - startX)); }
            function end(ev) {
                resizer.removeEventListener('pointermove', move);
                resizer.removeEventListener('pointerup', end);
                resizer.removeEventListener('pointercancel', end);
                try { resizer.releasePointerCapture(ev.pointerId); } catch (err) { /* already released */ }
                panel.classList.remove('is-resizing');
                saveWidth();
            }
            resizer.addEventListener('pointermove', move);
            resizer.addEventListener('pointerup', end);
            resizer.addEventListener('pointercancel', end);
        });
        resizer.addEventListener('keydown', function (e) {
            var w = null;
            if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') w = width - 16;
            else if (e.key === 'ArrowRight' || e.key === 'ArrowUp') w = width + 16;
            else if (e.key === 'Home') w = W_MIN;
            else if (e.key === 'End') w = maxWidth();
            if (w === null) return;
            e.preventDefault();
            applyWidth(clampWidth(w));
            saveWidth();
        });
        resizer.addEventListener('dblclick', function () { applyWidth(clampWidth(W_DEFAULT)); saveWidth(); });
    }

    // ── Phone sheet: peek / half / full ──
    var SNAPS = ['peek', 'half', 'full'];
    var SNAP_NAMES = { peek: 'small', half: 'half', full: 'full' };
    function sheetMetrics() {
        var full = panel.offsetHeight || Math.round(window.innerHeight * 0.9);
        var vh = window.innerHeight || full;
        return { full: full, peek: Math.min(full, Math.round(vh * 0.30)), half: Math.min(full, Math.round(vh * 0.60)) };
    }
    function setSnap(name, animate) {
        if (SNAPS.indexOf(name) === -1) name = 'half';
        snap = name;
        var m = sheetMetrics();
        var vis = m[name];
        var prevVis = parseFloat(panel.style.getPropertyValue('--lx-at-sheet-vis')) || 0;
        panel.style.setProperty('--lx-at-sheet-y', (m.full - vis) + 'px');
        clearTimeout(visTimer);
        // Growing: size the page at once (it is revealed as the sheet rises).
        // Shrinking: wait for the slide, so no empty strip shows on the way down.
        if (animate && vis < prevVis && !(window.RoamlyStyle && typeof window.RoamlyStyle.reducedMotion === 'function' && window.RoamlyStyle.reducedMotion())) {
            visTimer = setTimeout(function () { panel.style.setProperty('--lx-at-sheet-vis', vis + 'px'); }, 270);
        } else {
            panel.style.setProperty('--lx-at-sheet-vis', vis + 'px');
        }
        if (grip) grip.setAttribute('aria-label', 'Panel height: ' + SNAP_NAMES[name] + '. Change height');
    }
    if (grip) {
        grip.addEventListener('click', function () {
            if (!cur || Date.now() - dragEndAt < 350) return;
            setSnap(SNAPS[(SNAPS.indexOf(snap) + 1) % SNAPS.length], true);
        });
    }
    head.addEventListener('pointerdown', function (e) {
        if (!phoneMq.matches || !cur || e.button !== 0) return;
        if (e.target.closest && e.target.closest('a, button:not(.lx-at-grip)')) return;
        var m = sheetMetrics();
        var startY = e.clientY, startVis = m[snap], lastVis = startVis, moved = false;
        try { head.setPointerCapture(e.pointerId); } catch (err) { /* capture unsupported */ }
        function move(ev) {
            var dy = ev.clientY - startY;
            if (!moved && Math.abs(dy) < 4) return;
            if (!moved) { moved = true; panel.classList.add('is-dragging'); clearTimeout(visTimer); }
            lastVis = Math.max(0, Math.min(m.full, startVis - dy));
            panel.style.setProperty('--lx-at-sheet-y', (m.full - lastVis) + 'px');
        }
        function end(ev) {
            head.removeEventListener('pointermove', move);
            head.removeEventListener('pointerup', end);
            head.removeEventListener('pointercancel', end);
            try { head.releasePointerCapture(ev.pointerId); } catch (err) { /* already released */ }
            if (!moved) return;
            dragEndAt = Date.now();
            panel.classList.remove('is-dragging');
            if (ev.type === 'pointercancel') { setSnap(snap, true); return; }
            if (lastVis < m.peek * 0.6) { closePanel({ focus: false }); return; }
            var best = snap, bestD = Infinity;
            SNAPS.forEach(function (k) {
                var d = Math.abs(m[k] - lastVis);
                if (d < bestD) { bestD = d; best = k; }
            });
            setSnap(best, true);
        }
        head.addEventListener('pointermove', move);
        head.addEventListener('pointerup', end);
        head.addEventListener('pointercancel', end);
    });

    var resizeRaf = 0;
    window.addEventListener('resize', function () {
        if (resizeRaf) return;
        resizeRaf = requestAnimationFrame(function () {
            resizeRaf = 0;
            applyWidth(clampWidth(wanted));
            if (cur && phoneMq.matches) setSnap(snap, false);
        });
    });

    // Deep link / reload: /map/?panel=journals reopens it, without a new
    // history entry (Back leaves the page as it would have).
    var initial = panelFromUrl();
    if (initial) openPanel(initial, { history: false, instant: true, focus: false });
})();
