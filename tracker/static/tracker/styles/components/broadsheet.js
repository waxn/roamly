/* Components: broadsheet — the masthead's dateline and edition, which only
   make sense in Broadsheet's two-row masthead navbar. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'broadsheet') return;
    function ready(fn) { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn); else fn(); }
    ready(function () {
        var nav = document.querySelector('.navbar');
        if (!nav) return;
        var now = new Date(), hour = now.getHours();
        var edition = hour < 5 ? 'Late City Edition' : hour < 12 ? 'Morning Edition' : hour < 18 ? 'Afternoon Edition' : 'Night Edition';
        var dl = document.createElement('div');
        dl.className = 'bs-dateline';
        dl.setAttribute('aria-hidden', 'true');
        var b = document.createElement('b');
        b.textContent = now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
        dl.appendChild(b);
        dl.appendChild(document.createTextNode(edition + ' · Vol. ' + (now.getFullYear() - 2023) + ' · No. ' + Math.floor((now - new Date(now.getFullYear(), 0, 0)) / 864e5)));
        nav.insertBefore(dl, nav.firstChild);
    });
})();
