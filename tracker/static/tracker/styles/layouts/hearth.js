/*
 * Hearth layout shell behaviour (loaded `defer` by _style_layer.html on every
 * page while the Hearth layout is active; markup in layouts/hearth/shell.html).
 *
 *   - sidebar collapse to a 64px rail, remembered in localStorage
 *     `roamly_hearth_collapsed` ('1' rail, '0' full). Applied before first paint
 *     by the shell's inline script; this file only toggles and saves it.
 *   - phone drawer (<=768px): the same <aside>, opened from the header's menu
 *     button or the tab bar's "More". Modal while open: focus is trapped,
 *     Escape / the scrim / a link close it, focus returns to the opener.
 *   - account menu (menu button pattern: Enter/Space/ArrowUp opens, arrows move,
 *     Escape closes and returns focus, outside click closes).
 *   - keeps the notification bell (#notif, wired by base.html by id) in the
 *     header on phones and in the sidebar foot otherwise.
 *
 * Public: window.RoamlyHearth = { collapse(bool), isCollapsed(), openDrawer(), closeDrawer() }
 */
(function () {
    'use strict';
    var de = document.documentElement;
    if (de.getAttribute('data-layout') !== 'hearth') return;

    var KEY = 'roamly_hearth_collapsed';
    var side = document.getElementById('lx-he-side');
    if (!side) return;
    var collapseBtn = document.getElementById('lx-he-collapse');
    var closeBtn = document.getElementById('lx-he-close');
    var menuBtn = document.getElementById('lx-he-menu-btn');
    var moreBtn = document.getElementById('lx-he-more');
    var scrim = document.getElementById('lx-he-scrim');
    var acct = document.getElementById('lx-he-acct');
    var acctMenu = document.getElementById('lx-he-acct-menu');
    var phoneMq = window.matchMedia('(max-width: 768px)');

    function isPhone() { return phoneMq.matches; }

    /* ── Collapse ──────────────────────────────────────────────────────── */
    function isCollapsed() { return de.hasAttribute('data-hearth-collapsed'); }
    function syncCollapseBtn() {
        if (!collapseBtn) return;
        var c = isCollapsed();
        collapseBtn.setAttribute('aria-expanded', c ? 'false' : 'true');
        var label = c ? 'Expand sidebar' : 'Collapse sidebar';
        collapseBtn.setAttribute('aria-label', label);
        collapseBtn.setAttribute('title', label);
    }
    function collapse(on) {
        on = !!on;
        if (on === isCollapsed()) return;
        if (on) de.setAttribute('data-hearth-collapsed', '');
        else de.removeAttribute('data-hearth-collapsed');
        try { localStorage.setItem(KEY, on ? '1' : '0'); } catch (e) {}
        syncCollapseBtn();
        hideTip();
        closeMenu(false);
        // The content column changed width without a window resize; maps and
        // charts that size themselves on `resize` need to hear about it.
        try { window.dispatchEvent(new Event('resize')); } catch (e) {}
    }
    syncCollapseBtn();
    if (collapseBtn) collapseBtn.addEventListener('click', function () { collapse(!isCollapsed()); });

    /* ── Rail tooltips ─────────────────────────────────────────────────── */
    // A fixed element on <body>, not a ::after: the nav list scrolls, and a
    // scroll container clips anything that pokes out sideways.
    var tip = null, tipTimer = null;
    function hideTip() {
        clearTimeout(tipTimer);
        if (tip) tip.hidden = true;
    }
    function showTip(el, delay) {
        clearTimeout(tipTimer);
        if (!isCollapsed() || isPhone() || menuOpen()) { hideTip(); return; }
        var text = el.getAttribute('data-tip');
        if (!text) return;
        tipTimer = setTimeout(function () {
            if (!tip) {
                tip = document.createElement('div');
                tip.className = 'lx-he-tip';
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
    side.addEventListener('mouseover', function (e) {
        var el = e.target.closest && e.target.closest('[data-tip]');
        if (el && side.contains(el)) showTip(el, 250); else hideTip();
    });
    side.addEventListener('mouseleave', hideTip);
    side.addEventListener('focusin', function (e) {
        var el = e.target.closest && e.target.closest('[data-tip]');
        if (el && e.target.matches && e.target.matches(':focus-visible')) showTip(el, 0); else hideTip();
    });
    side.addEventListener('focusout', hideTip);
    var nav = side.querySelector('.lx-he-nav');
    if (nav) nav.addEventListener('scroll', hideTip, { passive: true });

    /* ── Phone drawer ──────────────────────────────────────────────────── */
    var opener = null;
    function drawerOpen() { return de.hasAttribute('data-hearth-drawer'); }
    function focusables() {
        return Array.prototype.filter.call(
            side.querySelectorAll('a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
            function (el) { return el.offsetParent !== null || el === document.activeElement; });
    }
    function setExpanded(v) {
        if (menuBtn) menuBtn.setAttribute('aria-expanded', v ? 'true' : 'false');
        if (moreBtn) moreBtn.setAttribute('aria-expanded', v ? 'true' : 'false');
    }
    function openDrawer(from) {
        if (!isPhone() || drawerOpen()) return;
        opener = from || document.activeElement;
        de.setAttribute('data-hearth-drawer', '');
        side.setAttribute('role', 'dialog');
        side.setAttribute('aria-modal', 'true');
        side.setAttribute('aria-label', 'Menu');
        if (scrim) scrim.hidden = false;
        setExpanded(true);
        var target = side.querySelector('.lx-he-link.is-active') || closeBtn || focusables()[0];
        if (target) { try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); } }
    }
    function closeDrawer(restore) {
        if (!drawerOpen()) return;
        de.removeAttribute('data-hearth-drawer');
        side.removeAttribute('role');
        side.removeAttribute('aria-modal');
        side.setAttribute('aria-label', 'Main');
        if (scrim) scrim.hidden = true;
        setExpanded(false);
        closeMenu(false);
        if (restore !== false && opener && typeof opener.focus === 'function' && document.contains(opener)) {
            try { opener.focus({ preventScroll: true }); } catch (e) { opener.focus(); }
        }
        opener = null;
    }
    if (menuBtn) menuBtn.addEventListener('click', function () { drawerOpen() ? closeDrawer() : openDrawer(menuBtn); });
    if (moreBtn) moreBtn.addEventListener('click', function () { drawerOpen() ? closeDrawer() : openDrawer(moreBtn); });
    if (closeBtn) closeBtn.addEventListener('click', function () { closeDrawer(); });
    if (scrim) scrim.addEventListener('click', function () { closeDrawer(); });
    // Following a link closes the drawer, so Back never returns to an open one.
    side.addEventListener('click', function (e) {
        var a = e.target.closest && e.target.closest('a[href]');
        if (a && drawerOpen()) closeDrawer(false);
    });
    side.addEventListener('keydown', function (e) {
        if (!drawerOpen() || e.key !== 'Tab') return;
        var f = focusables();
        if (!f.length) return;
        var first = f[0], last = f[f.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    });
    // Focus that escapes the open drawer (a screen-reader jump, a click on the
    // header) is pulled back inside, which is what makes it modal.
    document.addEventListener('focusin', function (e) {
        if (drawerOpen() && !side.contains(e.target) && !(e.target.closest && e.target.closest('.rly-dialog-overlay, .rly-toast'))) {
            var f = focusables();
            if (f.length) f[0].focus();
        }
    });

    /* ── Account menu ──────────────────────────────────────────────────── */
    function menuItems() { return acctMenu ? Array.prototype.slice.call(acctMenu.querySelectorAll('[role="menuitem"]')) : []; }
    function menuOpen() { return !!acctMenu && !acctMenu.hidden; }
    function openMenu(focusLast) {
        if (!acct || !acctMenu) return;
        acctMenu.hidden = false;
        acct.setAttribute('aria-expanded', 'true');
        var items = menuItems();
        var target = focusLast ? items[items.length - 1] : items[0];
        if (target) target.focus();
    }
    function closeMenu(restore) {
        if (!menuOpen()) return;
        acctMenu.hidden = true;
        acct.setAttribute('aria-expanded', 'false');
        if (restore) acct.focus();
    }
    if (acct && acctMenu) {
        acct.addEventListener('click', function (e) {
            e.stopPropagation();
            menuOpen() ? closeMenu(false) : openMenu(false);
        });
        acct.addEventListener('keydown', function (e) {
            if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                e.preventDefault();
                openMenu(e.key === 'ArrowUp');
            }
        });
        acctMenu.addEventListener('keydown', function (e) {
            var items = menuItems();
            var i = items.indexOf(document.activeElement);
            if (e.key === 'ArrowDown') { e.preventDefault(); (items[i + 1] || items[0]).focus(); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); (items[i - 1] || items[items.length - 1]).focus(); }
            else if (e.key === 'Home') { e.preventDefault(); items[0].focus(); }
            else if (e.key === 'End') { e.preventDefault(); items[items.length - 1].focus(); }
            else if (e.key === 'Tab') { closeMenu(false); }
        });
        document.addEventListener('click', function (e) {
            if (menuOpen() && !e.target.closest('.lx-he-acct-wrap')) closeMenu(false);
        });
    }

    document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape') return;
        // Innermost first: an open dialog handles its own Escape.
        if (document.querySelector('.rly-dialog-overlay.open')) return;
        if (menuOpen()) { e.preventDefault(); closeMenu(true); return; }
        var notifPanel = document.getElementById('notif-panel');
        if (notifPanel && !notifPanel.hidden) return;  // base.html closes the bell first
        if (drawerOpen()) { e.preventDefault(); closeDrawer(); }
    });

    /* ── Bell placement + breakpoint changes ──────────────────────────── */
    function placeBell() {
        var bell = document.getElementById('notif');
        var slot = document.getElementById(isPhone() ? 'lx-he-bell-top' : 'lx-he-bell-side');
        if (bell && slot && bell.parentNode !== slot) slot.appendChild(bell);
    }
    placeBell();
    function onBreakpoint() {
        if (!isPhone()) closeDrawer(false);
        placeBell();
    }
    if (phoneMq.addEventListener) phoneMq.addEventListener('change', onBreakpoint);
    else if (phoneMq.addListener) phoneMq.addListener(onBreakpoint);

    window.RoamlyHearth = {
        collapse: collapse,
        isCollapsed: isCollapsed,
        openDrawer: function () { openDrawer(null); },
        closeDrawer: function () { closeDrawer(); }
    };
})();
