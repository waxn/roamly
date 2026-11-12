/* Background: network — draws the metro-map backdrop in the active
   palette's five data colours (one per line) and sets it as --bgn-map. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-bg') !== 'network') return;
    function hex(name) {
        var e = document.createElement('i');
        e.style.cssText = 'display:none;color:var(' + name + ')';
        document.body.appendChild(e);
        var c = getComputedStyle(e).color; e.remove();
        var m = (c.match(/[\d.]+/g) || [128, 128, 128]).slice(0, 3).map(parseFloat);
        if (c.indexOf('color(') === 0) m = m.map(function (v) { return v * 255; });
        return '#' + m.map(function (v) { return ('0' + Math.round(v).toString(16)).slice(-2); }).join('');
    }
    function draw() {
        var lines = [
            ['M-20 180 H260 L420 340 H700 L800 240 H920', '--clay-coral'],
            ['M120 -20 V300 L300 480 V700 L420 820 V920', '--clay-sky'],
            ['M-20 620 H180 L340 460 H560 L700 600 V920', '--clay-mint'],
            ['M560 -20 V160 L700 300 V520 L860 680 H920', '--clay-amber'],
            ['M-20 820 H240 L380 680 H620 L760 820 H920', '--clay-lavender']
        ];
        var stops = [[420, 340, 11], [300, 480, 11], [700, 300, 11], [340, 460, 9], [620, 680, 9], [120, 180, 9]];
        var svg = '<svg xmlns="http://www.w3.org/2000/svg" width="900" height="900" fill="none" stroke-width="9" stroke-linecap="round" stroke-linejoin="round">' +
            lines.map(function (l) { return '<path d="' + l[0] + '" stroke="' + hex(l[1]) + '"/>'; }).join('') +
            '<g stroke="' + hex('--text') + '" stroke-width="4" fill="' + hex('--bg') + '">' +
            stops.map(function (s) { return '<circle cx="' + s[0] + '" cy="' + s[1] + '" r="' + s[2] + '"/>'; }).join('') + '</g></svg>';
        root.style.setProperty('--bgn-map', 'url("data:image/svg+xml,' + encodeURIComponent(svg) + '")');
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', draw); else draw();
})();
