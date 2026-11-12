/* Pebble — behaviour: one lighter stone slides under the active nav item
   (and follows the pointer across the nav), the same stone slides between
   segmented tabs, and cards rise into place on load. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-style') !== 'pebble') return;
    root.classList.add('pb-js');
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    // A thumb inside `host` that sits under whichever `item` it's aimed at.
    function slider(host, items, current) {
        if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
        var t = document.createElement('span');
        t.className = 'pb-thumb';
        t.setAttribute('aria-hidden', 'true');
        host.insertBefore(t, host.firstChild);
        function aim(el, instant) {
            if (!el) { t.style.opacity = '0'; return; }
            var hr = host.getBoundingClientRect(), r = el.getBoundingClientRect();
            if (instant) t.style.transition = 'none';
            t.style.width = r.width + 'px';
            t.style.transform = 'translateX(' + (r.left - hr.left) + 'px)';
            t.style.opacity = '1';
            if (instant) { void t.offsetWidth; t.style.transition = ''; }
        }
        aim(current(), true);
        return aim;
    }

    ready(function () {
        // nav
        var navHost = document.querySelector('.navbar-center');
        var items = Array.from(document.querySelectorAll('.navbar-nav > li > a, .navbar-nav > li > .navbar-nav-toggle'));
        if (navHost && items.length) {
            var activeOf = function () { return items.find(function (a) { return a.classList.contains('active'); }); };
            var aim = slider(navHost, items, activeOf);
            items.forEach(function (a) { a.addEventListener('pointerenter', function () { aim(a); }); });
            navHost.addEventListener('pointerleave', function () { aim(activeOf()); });
            addEventListener('resize', function () { aim(activeOf(), true); });
            document.fonts && document.fonts.ready.then(function () { aim(activeOf(), true); });
        }

        // segmented tabs (any .pill-tabs; tabs switch by class, so watch it)
        document.querySelectorAll('.pill-tabs').forEach(function (group) {
            var tabs = function () { return Array.from(group.querySelectorAll('.tab-btn')); };
            var act = function () { return tabs().find(function (b) { return b.classList.contains('active'); }); };
            var aimTab = slider(group, tabs(), act);
            new MutationObserver(function () { aimTab(act()); }).observe(group, { subtree: true, attributes: true, attributeFilter: ['class'] });
            addEventListener('resize', function () { aimTab(act(), true); });
            document.fonts && document.fonts.ready.then(function () { aimTab(act(), true); });
        });

        if (reduced) return;
        // the light slides across whichever stone the pointer is over
        document.addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            var c = e.target.closest && e.target.closest('.card');
            if (!c) return;
            var r = c.getBoundingClientRect();
            c.style.setProperty('--pb-x', (e.clientX - r.left) + 'px');
            c.style.setProperty('--pb-y', (e.clientY - r.top) + 'px');
        }, { passive: true });
        var cards = Array.from(document.querySelectorAll('main .card')).filter(function (c) {
            var r = c.getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0;
        }).slice(0, 16);
        cards.forEach(function (c, i) {
            c.style.animationDelay = (i * 40) + 'ms';
            c.classList.add('pb-in');
            c.addEventListener('animationend', function () { c.classList.remove('pb-in'); c.style.animationDelay = ''; }, { once: true });
        });
    });
})();
