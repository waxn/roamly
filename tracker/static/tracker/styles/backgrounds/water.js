/* Background: water — generates the caustic light texture once (the CSS
   drifts it) and runs a canvas of bioluminescent plankton in the palette's
   colours that drift up, scatter from the pointer and get pushed by a
   ripple wherever you click. Pauses when hidden or under a full-page map. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-bg') !== 'water') return;
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function tokenRGB(name) {
        var e = document.createElement('i');
        e.style.cssText = 'display:none;color:var(' + name + ')';
        document.body.appendChild(e);
        var c = getComputedStyle(e).color; e.remove();
        var m = c.match(/[\d.]+/g) || [255, 255, 255];
        var srgb = c.indexOf('color(') === 0;
        return [0, 1, 2].map(function (i) { var v = parseFloat(m[i]); return Math.round(srgb ? v * 255 : v); });
    }

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    // ── caustics: bright contour webs of a smooth, periodic wave field.
    // Integer frequencies over the tile make it wrap seamlessly; the light
    // gathers where the field crosses zero, which reads as water caustics.
    function caustic(w, h, seed) {
        var c = document.createElement('canvas'); c.width = w; c.height = h;
        var tint = tokenRGB('--primary').map(function (v) { return Math.round(v + (255 - v) * 0.5); });
        var g = c.getContext('2d'), img = g.createImageData(w, h), d = img.data;
        var rnd = function () { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
        function field() {
            var waves = [];
            for (var i = 0; i < 6; i++) waves.push([1 + Math.floor(rnd() * 3), Math.floor(rnd() * 5) - 2, rnd() * 6.283, 0.5 + rnd() * 0.6]);
            return waves;
        }
        var A = field(), B = field(), TAU = Math.PI * 2;
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) {
            var u = x / w, v = y / h, f1 = 0, f2 = 0, k;
            for (k = 0; k < 6; k++) {
                f1 += A[k][3] * Math.cos(TAU * (A[k][0] * u + A[k][1] * v) + A[k][2]);
                f2 += B[k][3] * Math.cos(TAU * (B[k][1] * u + B[k][0] * v) + B[k][2]);
            }
            var I = Math.exp(-Math.abs(f1) * 5.5) + 0.7 * Math.exp(-Math.abs(f2) * 7);
            var a = Math.min(255, I * 150), o = (y * w + x) * 4;
            d[o] = tint[0]; d[o + 1] = tint[1]; d[o + 2] = tint[2]; d[o + 3] = a;
        }
        g.putImageData(img, 0, 0);
        return c.toDataURL('image/png');
    }

    ready(function () {
        try { root.style.setProperty('--bgw-caustic', 'url(' + caustic(260, 170, 1234567) + ')'); } catch (e) {}

        // ── plankton ──
        var cv = document.createElement('canvas');
        cv.className = 'bgw-plankton';
        cv.setAttribute('aria-hidden', 'true');
        document.body.insertBefore(cv, document.body.firstChild);
        var ctx = cv.getContext('2d');
        if (!ctx) return;
        var dpr = Math.min(1.5, devicePixelRatio || 1), W = 0, H = 0;
        function size() { W = innerWidth; H = innerHeight; cv.width = W * dpr; cv.height = H * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0); }
        size(); addEventListener('resize', size);

        var cols = ['--primary', '--primary', '--primary', '--primary-hover', '--clay-coral', '--clay-amber', '--clay-sky'].map(function (t) { return tokenRGB(t).join(','); });
        var ring = tokenRGB('--primary').join(',');
        function sprite(rgb) {
            var s = document.createElement('canvas'); s.width = s.height = 32;
            var g = s.getContext('2d'), gr = g.createRadialGradient(16, 16, 0, 16, 16, 16);
            gr.addColorStop(0, 'rgba(' + rgb + ',1)'); gr.addColorStop(0.25, 'rgba(' + rgb + ',0.55)'); gr.addColorStop(1, 'rgba(' + rgb + ',0)');
            g.fillStyle = gr; g.fillRect(0, 0, 32, 32); return s;
        }
        var sprites = {}; cols.forEach(function (c) { if (!sprites[c]) sprites[c] = sprite(c); });
        var N = Math.round(Math.min(90, Math.max(35, W * H / 22000)));
        var P = [];
        for (var i = 0; i < N; i++) P.push({
            x: Math.random() * W, y: Math.random() * H, vx: 0, vy: 0,
            r: 2 + Math.random() * 7, up: 0.08 + Math.random() * 0.3, ph: Math.random() * 6.28,
            c: cols[Math.floor(Math.random() * cols.length)], tw: 0.4 + Math.random() * 0.6
        });
        var mx = -999, my = -999, rings = [];
        addEventListener('pointermove', function (e) { mx = e.clientX; my = e.clientY; }, { passive: true });
        document.addEventListener('pointerleave', function () { mx = my = -999; });
        addEventListener('pointerdown', function (e) {
            if (e.target.closest && e.target.closest('.maplibregl-map')) return;
            rings.push({ x: e.clientX, y: e.clientY, t: 0 });
            P.forEach(function (p) {
                var dx = p.x - e.clientX, dy = p.y - e.clientY, d2 = dx * dx + dy * dy;
                if (d2 < 60000) { var d = Math.sqrt(d2) + 1, f = (1 - d / 245) * 6; p.vx += dx / d * f; p.vy += dy / d * f; }
            });
        }, { passive: true });

        var light = function () { return root.getAttribute('data-theme') === 'light'; };
        function draw(t) {
            ctx.clearRect(0, 0, W, H);
            ctx.globalCompositeOperation = light() ? 'source-over' : 'lighter';
            for (var i = 0; i < P.length; i++) {
                var p = P[i];
                var dx = p.x - mx, dy = p.y - my, d2 = dx * dx + dy * dy, near = 0;
                if (d2 < 16000) { var d = Math.sqrt(d2) + 1; near = 1 - d / 126; p.vx += dx / d * near * 0.35; p.vy += dy / d * near * 0.35; }
                p.vx *= 0.94; p.vy *= 0.94;
                p.x += p.vx + Math.sin(t / 1400 + p.ph) * 0.18;
                p.y += p.vy - p.up;
                if (p.y < -20) { p.y = H + 20; p.x = Math.random() * W; }
                if (p.x < -20) p.x = W + 20; else if (p.x > W + 20) p.x = -20;
                var a = (0.35 + 0.35 * Math.sin(t / 900 * p.tw + p.ph) + near * 0.8) * (light() ? 0.5 : 1);
                ctx.globalAlpha = Math.max(0, Math.min(1, a));
                var s = p.r * (1 + near * 0.8) * 2;
                ctx.drawImage(sprites[p.c], p.x - s / 2, p.y - s / 2, s, s);
            }
            ctx.globalAlpha = 1;
            ctx.globalCompositeOperation = 'source-over';
            for (var j = rings.length - 1; j >= 0; j--) {
                var g = rings[j]; g.t += 1 / 60;
                if (g.t > 1.2) { rings.splice(j, 1); continue; }
                for (var k = 0; k < 3; k++) {
                    var tt = g.t - k * 0.14; if (tt <= 0) continue;
                    ctx.beginPath();
                    ctx.arc(g.x, g.y, tt * 220, 0, Math.PI * 2);
                    ctx.strokeStyle = 'rgba(' + ring + ',' + (Math.max(0, 0.45 - tt * 0.4) * (1 - k * 0.3)).toFixed(3) + ')';
                    ctx.lineWidth = 1.5;
                    ctx.stroke();
                }
            }
        }
        function covered() {
            var m = document.querySelector('.maplibregl-map');
            if (!m) return false;
            var r = m.getBoundingClientRect();
            return r.width * r.height > W * H * 0.45;
        }
        if (reduced) { draw(0); addEventListener('resize', function () { draw(0); }); return; }
        var hidden = false, frames = 0;
        (function loop(t) {
            if (++frames % 120 === 1) { hidden = covered(); cv.style.display = hidden ? 'none' : ''; }
            if (!document.hidden && !hidden) draw(t);
            requestAnimationFrame(loop);
        })(0);
    });
})();
