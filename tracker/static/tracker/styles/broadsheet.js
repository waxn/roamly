/* Broadsheet — behaviour: a dateline and edition in the masthead, and a
   kicker + byline around each page headline. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'broadsheet') return;

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    ready(function () {
        var now = new Date();
        var hour = now.getHours();
        var edition = hour < 5 ? 'Late City Edition' : hour < 12 ? 'Morning Edition' : hour < 18 ? 'Afternoon Edition' : 'Night Edition';
        var nav = document.querySelector('.navbar');
        if (nav) {
            var dl = document.createElement('div');
            dl.className = 'bs-dateline';
            dl.setAttribute('aria-hidden', 'true');
            var b = document.createElement('b');
            b.textContent = now.toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });
            dl.appendChild(b);
            dl.appendChild(document.createTextNode(edition + ' · Vol. ' + (now.getFullYear() - 2023) + ' · No. ' + (Math.floor((now - new Date(now.getFullYear(), 0, 0)) / 864e5))));
            nav.insertBefore(dl, nav.firstChild);
        }

        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (!h) return;
        var section = (h.textContent || '').trim();
        var k = document.createElement('div');
        k.className = 'bs-kicker';
        k.setAttribute('aria-hidden', 'true');
        k.textContent = 'Section ' + String.fromCharCode(65 + (section.length % 6)) + ' · ' + section;
        var host = h.parentNode;
        if (host.classList.contains('page-toolbar')) {
            var wrap = document.createElement('div');
            host.insertBefore(wrap, h);
            wrap.appendChild(k); wrap.appendChild(h);
            host = wrap;
        } else {
            host.insertBefore(k, h);
        }
        var who = document.querySelector('.navbar-user-name');
        var by = document.createElement('div');
        by.className = 'bs-byline';
        by.setAttribute('aria-hidden', 'true');
        by.innerHTML = 'By <b></b> · Filed ' + now.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
        by.querySelector('b').textContent = who ? who.textContent.trim() : 'Staff';
        var lead = host.querySelector('.page-lead');
        (lead || h).insertAdjacentElement('afterend', by);
    });
})();
