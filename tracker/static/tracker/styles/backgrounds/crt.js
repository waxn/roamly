/* Background: crt — adds the slow roll bar of an old tube. */
(function () {
    'use strict';
    if (document.documentElement.getAttribute('data-bg') !== 'crt') return;
    if (window.RoamlyStyle && RoamlyStyle.reducedMotion()) return;
    function add() {
        var r = document.createElement('div');
        r.className = 'bgc-roll';
        r.setAttribute('aria-hidden', 'true');
        document.body.appendChild(r);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', add); else add();
})();
