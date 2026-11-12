/* Basecamp — behaviour: a short boot log on the first page of a session
   (click or any key to skip), a shell prompt typed out above each page
   title, and the slow roll bar of an old tube. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-style') !== 'basecamp') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }
    function slug(s) { return (s || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''); }

    function boot() {
        var seen = false;
        try { seen = sessionStorage.getItem('roamly_bc_booted') === '1'; sessionStorage.setItem('roamly_bc_booted', '1'); } catch (e) { seen = true; }
        if (seen || reduced) return;
        var el = document.createElement('div');
        el.className = 'bc-boot';
        el.setAttribute('aria-hidden', 'true');
        document.body.appendChild(el);
        var lines = [
            'ROAMLY FIELD TERMINAL  (C) ' + new Date().getFullYear(),
            'BIOS 1.99 ............................ OK',
            'MEMORY 640K .......................... OK',
            'GNSS RECEIVER ........................ LOCK',
            'TRACK STORE .......................... MOUNTED',
            'MAPS ................................. READY',
            '',
            'WELCOME BACK.'
        ];
        var i = 0, done = false;
        function finish() {
            if (done) return; done = true;
            el.classList.add('done');
            setTimeout(function () { el.remove(); }, 400);
            removeEventListener('keydown', finish);
        }
        el.addEventListener('click', finish);
        addEventListener('keydown', finish);
        (function next() {
            if (done) return;
            if (i >= lines.length) { setTimeout(finish, 260); return; }
            el.textContent += lines[i++] + '\n';
            setTimeout(next, 85);
        })();
    }

    ready(function () {
        boot();
        if (!reduced) {
            var roll = document.createElement('div');
            roll.className = 'bc-roll';
            roll.setAttribute('aria-hidden', 'true');
            document.body.appendChild(roll);
        }
        var h = document.querySelector('.page-header h1, .page-toolbar h1');
        if (!h) return;
        var who = document.querySelector('.navbar-user-name');
        var user = slug(who ? who.textContent : 'user') || 'user';
        var cmd = 'open ~/' + (slug(h.textContent) || 'home');
        var p = document.createElement('div');
        p.className = 'bc-prompt';
        p.setAttribute('aria-hidden', 'true');
        p.innerHTML = '<b></b>:~$ <span></span>';
        p.querySelector('b').textContent = user + '@roamly';
        var out = p.querySelector('span');
        var host = h.parentNode;
        if (host.classList.contains('page-toolbar')) {
            var wrap = document.createElement('div');
            host.insertBefore(wrap, h);
            wrap.appendChild(p); wrap.appendChild(h);
        } else {
            host.insertBefore(p, h);
        }
        if (reduced) { out.textContent = cmd; return; }
        var n = 0;
        (function type() {
            out.textContent = cmd.slice(0, ++n);
            if (n < cmd.length) setTimeout(type, 28 + Math.random() * 40);
        })();
    });
})();
