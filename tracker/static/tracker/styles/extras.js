/* ════════════════════════════════════════════════════════════════════
   EXTRAS — every small behaviour from the presets, each its own module,
   each switched on by its id in html[data-fx] (Settings → Appearance).
   They work on any palette and components set: colours come from the
   palette tokens, and nothing here assumes a particular look. All honour
   prefers-reduced-motion and the Calm motion setting (RoamlyStyle).
   ════════════════════════════════════════════════════════════════════ */
(function () {
    'use strict';
    var root = document.documentElement;
    var FX = (root.getAttribute('data-fx') || '').split(/\s+/).filter(Boolean);
    if (!FX.length) return;
    var RS = window.RoamlyStyle;
    var reduced = !!(RS && RS.reducedMotion());
    var fine = window.matchMedia && matchMedia('(hover: hover) and (pointer: fine)').matches;
    function on(id) { return FX.indexOf(id) !== -1; }
    function ready(fn) { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn); else fn(); }
    function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; e.setAttribute('aria-hidden', 'true'); return e; }
    function title() { return document.querySelector('.page-header h1, .page-toolbar h1'); }
    function titleText(h) {
        h = h || title(); if (!h) return '';
        var t = ''; h.childNodes.forEach(function (n) { if (n.nodeType === 3) t += n.textContent; });
        return t.trim() || h.textContent.trim();
    }
    function userName() { var u = document.querySelector('.navbar-user-name'); return u ? u.textContent.trim() : ''; }
    function slug(s) { return (s || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''); }
    // Put a line above the page title. In a .page-toolbar the title shares a
    // flex row with the actions, so the title and the line get a wrapper.
    function aboveTitle(node) {
        var h = title(); if (!h) return;
        var host = h.parentNode;
        if (host.classList.contains('page-toolbar')) {
            var wrap = document.createElement('div');
            host.insertBefore(wrap, h);
            wrap.appendChild(node); wrap.appendChild(h);
        } else host.insertBefore(node, h);
    }
    function visibleCards(limit) {
        return Array.from(document.querySelectorAll('main .card')).filter(function (c) {
            var r = c.getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0;
        }).slice(0, limit || 16);
    }
    function tokenRGB(name) {
        var e = document.createElement('i');
        e.style.cssText = 'display:none;color:var(' + name + ')';
        document.body.appendChild(e);
        var c = getComputedStyle(e).color; e.remove();
        var m = c.match(/[\d.]+/g) || [255, 255, 255];
        var srgb = c.indexOf('color(') === 0;
        return [0, 1, 2].map(function (i) { var v = parseFloat(m[i]); return srgb ? v : v / 255; });
    }
    function wobbleFrames(amp, o) {
        o = o || {};
        var n = o.n || 14, f = o.freq || 2.6, k = o.damp || 4.2, out = [];
        for (var i = 0; i <= n; i++) {
            var t = i / n, a = amp * Math.exp(-k * t) * Math.cos(2 * Math.PI * f * t);
            out.push({ transform: 'scale(' + (1 + a).toFixed(4) + ',' + (1 - a).toFixed(4) + ')' });
        }
        out[out.length - 1].transform = 'scale(1,1)';
        return out;
    }
    function entrance(cls, step, limit) {
        if (reduced) return;
        visibleCards(limit).forEach(function (c, i) {
            c.style.animationDelay = (i * step) + 'ms';
            c.classList.add(cls);
            c.addEventListener('animationend', function () { c.classList.remove(cls); c.style.animationDelay = ''; }, { once: true });
        });
    }
    // A thumb under whichever item it's aimed at (stone, goo).
    function slider(host, cls) {
        host.classList.add(cls + '-host');
        var t = el('span', cls);
        host.insertBefore(t, host.firstChild);
        return {
            el: t,
            aim: function (item, instant) {
                if (!item) { t.style.opacity = '0'; return; }
                var hr = host.getBoundingClientRect(), r = item.getBoundingClientRect();
                if (instant) t.style.transition = 'none';
                t.style.width = r.width + 'px';
                t.style.transform = 'translateX(' + (r.left - hr.left) + 'px)';
                t.style.opacity = '1';
                if (instant) { void t.offsetWidth; t.style.transition = ''; }
            }
        };
    }
    var navItems = function () { return Array.from(document.querySelectorAll('.navbar-nav > li > a, .navbar-nav > li > .navbar-nav-toggle')); };
    var navActive = function () { return navItems().find(function (a) { return a.classList.contains('active'); }); };
    // Numbers: both flap and countup rewrite stat values as pages fill them in.
    function watchStats(fn) {
        var self = false;
        var run = function () { self = true; document.querySelectorAll('.stat-value').forEach(fn); self = false; };
        run();
        var pending = false;
        new MutationObserver(function (muts) {
            if (self) return;
            for (var i = 0; i < muts.length; i++) {
                var n = muts[i].target.nodeType === 1 ? muts[i].target : muts[i].target.parentElement;
                if (n && (n.closest('.stat-value') || (n.querySelector && n.querySelector('.stat-value')))) {
                    if (!pending) { pending = true; requestAnimationFrame(function () { pending = false; run(); }); }
                    return;
                }
            }
        }).observe(document.body, { childList: true, subtree: true, characterData: true });
    }

    var M = {};

    /* ── Lantern ── */
    M.lamp = function () {
        var lamp = el('div', 'fx-lamp'); document.body.appendChild(lamp);
        if (reduced) return;
        var tx = innerWidth * 0.22, ty = innerHeight * 0.08, x = tx, y = ty, raf = 0;
        function tick() {
            x += (tx - x) * 0.12; y += (ty - y) * 0.12;
            root.style.setProperty('--lx', x.toFixed(1) + 'px'); root.style.setProperty('--ly', y.toFixed(1) + 'px');
            raf = (Math.abs(tx - x) + Math.abs(ty - y) > 0.5) ? requestAnimationFrame(tick) : 0;
        }
        addEventListener('pointermove', function (e) { if (e.pointerType !== 'mouse') return; tx = e.clientX; ty = e.clientY; if (!raf) raf = requestAnimationFrame(tick); }, { passive: true });
    };
    M.compass = function () {
        var big = document.querySelector('.maplibregl-map');
        if (big && !document.querySelector('main .container')) return;
        document.body.appendChild(el('div', 'fx-compass',
            '<svg viewBox="0 0 100 100" width="92" height="92" fill="none" stroke="currentColor">' +
            '<circle cx="50" cy="50" r="46" stroke-width="1.2"/><circle cx="50" cy="50" r="40" stroke-width="0.6" stroke-dasharray="1 3"/>' +
            '<path d="M50 6 L50 14 M50 86 L50 94 M6 50 L14 50 M86 50 L94 50" stroke-width="1.4"/>' +
            '<text x="50" y="25" font-size="9" text-anchor="middle" fill="currentColor" stroke="none" font-family="serif">N</text>' +
            '<g class="fx-needle"><path d="M50 22 L56 50 L50 78 L44 50 Z" stroke-width="1" fill="currentColor" fill-opacity="0.25"/><path d="M50 22 L56 50 L44 50 Z" fill="currentColor" stroke="none"/></g>' +
            '<circle cx="50" cy="50" r="2.5" fill="currentColor" stroke="none"/></svg>'));
    };
    M.kicker = function () {
        if (!title()) return;
        var k = el('div', 'fx-kicker');
        k.textContent = 'Field notes · ' + new Date().toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
        aboveTitle(k);
    };
    M.stamp = function () {
        if (reduced || !('IntersectionObserver' in window)) return;
        var io = new IntersectionObserver(function (es) {
            es.forEach(function (en) { if (en.isIntersecting) { en.target.classList.add('fx-stamped'); io.unobserve(en.target); } });
        }, { threshold: 0.6 });
        var seen = new WeakSet();
        function scan(r) { (r.querySelectorAll ? r : document).querySelectorAll('.badge').forEach(function (b) { if (!seen.has(b)) { seen.add(b); io.observe(b); } }); }
        scan(document);
        new MutationObserver(function (ms) { ms.forEach(function (m) { m.addedNodes.forEach(function (n) { if (n.nodeType === 1) scan(n.parentNode || document); }); }); })
            .observe(document.body, { childList: true, subtree: true });
    };
    M.ink = function () {
        if (reduced) return;
        document.addEventListener('pointerdown', function (e) {
            var b = e.target.closest && e.target.closest('.btn'); if (!b) return;
            var r = b.getBoundingClientRect(), s = el('span', 'fx-ink');
            b.classList.add('fx-ink-host');
            s.style.left = (e.clientX - r.left) + 'px'; s.style.top = (e.clientY - r.top) + 'px';
            b.appendChild(s); setTimeout(function () { s.remove(); }, 600);
        }, true);
    };

    /* ── Transit ── */
    M.flap = function () {
        watchStats(function (v) {
            if (v.querySelector(':not(.fx-flap)')) return;
            var text = v.textContent;
            if (!text || !text.trim() || text.length > 16) return;
            if (v.dataset.fxText === text && v.querySelector('.fx-flap')) return;
            v.dataset.fxText = text;
            var chars = Array.from(text), frag = document.createDocumentFragment(), i = 0;
            chars.forEach(function (ch) {
                var s = document.createElement('span');
                s.className = 'fx-flap' + (/[\s,.:]/.test(ch) ? ' fx-sep' : '');
                s.textContent = ch;
                if (!reduced && !/\s/.test(ch)) { s.classList.add('fx-go'); s.style.animationDelay = (i++ * 45) + 'ms'; }
                frag.appendChild(s);
            });
            v.setAttribute('aria-label', text);
            v.dataset.fxN = chars.length;
            v.style.setProperty('--fx-n', Math.max(4, chars.length));
            v.textContent = ''; v.appendChild(frag);
        });
    };
    M.clock = function () {
        var end = document.querySelector('.navbar-end'); if (!end) return;
        var c = el('span', 'fx-clock'); end.insertBefore(c, end.firstChild);
        var tick = function () { var d = new Date(); c.innerHTML = String(d.getHours()).padStart(2, '0') + '<span class="fx-colon">:</span>' + String(d.getMinutes()).padStart(2, '0'); };
        tick(); setInterval(tick, 10000);
    };
    M.roundel = function () {
        var h = title(); if (!h) return;
        var words = titleText(h).split(/\s+/).filter(Boolean);
        var code = (words.length > 1 ? words.map(function (w) { return w[0]; }).join('') : (words[0] || '')).replace(/[^A-Za-z0-9]/g, '').slice(0, 3).toUpperCase();
        if (!code) return;
        var c = el('span', 'fx-code'); c.textContent = code;
        h.insertBefore(c, h.firstChild);
    };

    /* ── Jelly ── */
    var PIN_FRAG = [
        'precision highp float;uniform vec2 uRes;uniform float uTime;uniform float uJig;uniform vec3 uCol;varying vec2 vUv;',
        'float sdRoundCone(vec3 p,float r1,float r2,float h){vec2 q=vec2(length(p.xz),p.y);float b=(r1-r2)/h;float a=sqrt(1.-b*b);float k=dot(q,vec2(-b,a));',
        ' if(k<0.) return length(q)-r1; if(k>a*h) return length(q-vec2(0.,h))-r2; return dot(q,vec2(a,b))-r1;}',
        'mat2 rot(float a){float c=cos(a),s=sin(a);return mat2(c,-s,s,c);}',
        'float map(vec3 p){ float t=uTime; p.y+=.06*sin(t*2.2); p.xz*=rot(.55*sin(t*.7)+uJig*1.6); p.xy*=rot(.12*sin(t*1.3)+uJig*.35);',
        ' vec3 q=p; float sq=uJig*.5; q.y/=1.+sq*.35; q.xz/=1.-sq*.18;',
        ' float cone=sdRoundCone(q-vec3(0.,-.86,0.),.07,.5,1.0); float dimple=length(q-vec3(0.,.16,.46))-.2;',
        ' return max(cone,-dimple)+.01*sin(q.x*9.+t*3.)*sin(q.y*8.+t*2.); }',
        'vec3 nrm(vec3 p){vec2 e=vec2(.0025,-.0025);return normalize(e.xyy*map(p+e.xyy)+e.yyx*map(p+e.yyx)+e.yxy*map(p+e.yxy)+e.xxx*map(p+e.xxx));}',
        'void main(){ vec2 uv=vUv; vec3 ro=vec3(0.,0.,3.2), rd=normalize(vec3(uv*.62,-1.75)); float t=0., hit=0., md=1e3, mt=0.;',
        ' for(int i=0;i<48;i++){ vec3 p=ro+rd*t; float d=map(p); if(d<md){md=d;mt=t;} if(d<.002){hit=1.;break;} t+=d*.9; if(t>5.)break; }',
        ' float cov=hit>.5?1.:smoothstep(.02,0.,md); if(cov<.002){gl_FragColor=vec4(0.);return;} if(hit<.5) t=mt;',
        ' vec3 p=ro+rd*t; vec3 n=nrm(p); vec3 v=-rd; vec3 l=normalize(vec3(-.5,.8,.6)); vec3 base=uCol;',
        ' float dif=max(dot(n,l),0.), spec=pow(max(dot(reflect(-l,n),v),0.),56.), fres=pow(1.-max(dot(n,v),0.),2.4);',
        ' float strip=smoothstep(.955,.995,dot(n,normalize(vec3(-.42,.62,.66))));',
        ' vec3 col=base*(.24+.62*dif)+base*base*.32+base*fres*.75+vec3(1.)*(spec*.85+strip*.55);',
        ' gl_FragColor=vec4(col*cov,cov); }'
    ].join('\n');
    var pin = { v: 0, vel: 0 };
    function kick(a) { pin.vel += a; }
    M.pin = function () {
        var brand = document.querySelector('.navbar-brand'); if (!brand) return;
        var cv = el('canvas', 'fx-pin');
        var gl = cv.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: true });
        if (!gl) return;
        function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return gl.getShaderParameter(s, gl.COMPILE_STATUS) ? s : null; }
        var vs = sh(gl.VERTEX_SHADER, 'attribute vec2 p;varying vec2 vUv;void main(){vUv=p;gl_Position=vec4(p,0.,1.);}'), fs = sh(gl.FRAGMENT_SHADER, PIN_FRAG);
        if (!vs || !fs) return;
        var pr = gl.createProgram(); gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr); gl.useProgram(pr);
        var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
        var loc = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
        var uRes = gl.getUniformLocation(pr, 'uRes'), uTime = gl.getUniformLocation(pr, 'uTime'), uJig = gl.getUniformLocation(pr, 'uJig'), uCol = gl.getUniformLocation(pr, 'uCol');
        brand.insertBefore(cv, brand.firstChild);
        root.classList.add('fx-pin-on');
        gl.uniform3fv(uCol, new Float32Array(tokenRGB('--primary')));
        var dpr = Math.min(2, devicePixelRatio || 1);
        function draw(time) {
            var s = Math.round(cv.getBoundingClientRect().width * dpr) || 92;
            if (cv.width !== s) { cv.width = s; cv.height = s; }
            gl.viewport(0, 0, s, s); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
            gl.uniform2f(uRes, s, s); gl.uniform1f(uTime, time); gl.uniform1f(uJig, pin.v);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        if (reduced) { draw(0.6); return; }
        var t0 = performance.now(), last = t0;
        brand.addEventListener('pointerenter', function () { kick(5); });
        cv.addEventListener('pointerdown', function () { kick(-9); });
        kick(12);
        (function frame(now) {
            var dt = Math.min(0.05, (now - last) / 1000); last = now;
            pin.vel += (-pin.v * 180 - pin.vel * 9) * dt; pin.v += pin.vel * dt;
            if (!document.hidden) draw((now - t0) / 1000);
            requestAnimationFrame(frame);
        })(t0);
    };
    M.squish = function () {
        if (reduced) return;
        var SQ = '.btn, .badge, .pill-tabs .tab-btn, .navbar-nav a, .navbar-nav-toggle, .rly-dialog-btn, .notif-toggle, .navbar-user-toggle, .rly-select-trigger, .rly-date-trigger, .bottom-nav a, .rly-cal-day, .tool-btn, .style-btn';
        var held = new WeakMap();
        var target = function (e) { return e.target.closest && e.target.closest(SQ); };
        document.addEventListener('pointerdown', function (e) {
            var t = target(e); if (!t || t.disabled) return;
            var big = t.offsetWidth > 260;
            held.set(t, t.animate([{ transform: 'scale(1,1)' }, { transform: big ? 'scale(1.02,0.96)' : 'scale(1.14,0.84) translateY(2px)' }], { duration: 110, easing: 'ease-out', fill: 'forwards' }));
        }, true);
        function release(e) {
            var t = target(e); if (!t) return;
            var a = held.get(t); if (!a) return;
            held.delete(t); a.cancel();
            t.animate(wobbleFrames(t.offsetWidth > 260 ? 0.04 : 0.16, { freq: 2.4, damp: 4.5 }), { duration: 650, easing: 'linear' });
        }
        document.addEventListener('pointerup', release, true);
        document.addEventListener('pointercancel', release, true);
        document.addEventListener('pointerout', function (e) { var t = target(e); if (t && held.has(t) && !t.contains(e.relatedTarget)) release(e); }, true);
        document.addEventListener('pointerover', function (e) {
            if (e.pointerType !== 'mouse') return;
            var t = target(e);
            if (!t || t.contains(e.relatedTarget) || held.has(t) || t.offsetWidth > 260) return;
            if (t._fxBoing && performance.now() - t._fxBoing < 500) return;
            t._fxBoing = performance.now();
            t.animate(wobbleFrames(0.07, { freq: 2, damp: 5, n: 10 }), { duration: 420, easing: 'linear' });
        }, true);
        document.querySelectorAll('.page-header h1, .page-toolbar h1').forEach(function (h) {
            h.style.display = h.style.display || 'inline-block';
            h.addEventListener('pointerenter', function () { h.animate(wobbleFrames(0.08, { freq: 2.2, damp: 4 }), { duration: 700 }); });
        });
    };
    M.tilt = function () {
        if (reduced || !fine) return;
        var active = new Set();
        var S = function (c) { return c._fxT || (c._fxT = { rx: 0, ry: 0, vx: 0, vy: 0, tx: 0, ty: 0, z: 0, vz: 0, tz: 0 }); };
        var ok = function (c) { return c.offsetHeight < 520 && !c.querySelector('.maplibregl-map, canvas, iframe, input, textarea, select, .rly-select, [class*="modal"], [class*="overlay"]'); };
        var last = performance.now(), loop = false;
        function step(now) {
            var dt = Math.min(0.035, (now - last) / 1000); last = now;
            active.forEach(function (c) {
                var s = S(c);
                s.vx += ((s.tx - s.rx) * 140 - s.vx * 11) * dt; s.rx += s.vx * dt;
                s.vy += ((s.ty - s.ry) * 140 - s.vy * 11) * dt; s.ry += s.vy * dt;
                s.vz += ((s.tz - s.z) * 160 - s.vz * 12) * dt; s.z += s.vz * dt;
                if (Math.abs(s.rx) + Math.abs(s.ry) + Math.abs(s.vx) + Math.abs(s.vy) + Math.abs(s.z) + Math.abs(s.vz) < 0.01 && !s.tx && !s.ty && !s.tz) { c.style.transform = ''; active.delete(c); return; }
                c.style.transform = 'perspective(900px) rotateX(' + s.rx.toFixed(3) + 'deg) rotateY(' + s.ry.toFixed(3) + 'deg) translateY(' + (-s.z).toFixed(2) + 'px)';
            });
            if (active.size) requestAnimationFrame(step); else loop = false;
        }
        function wake() { if (!loop) { loop = true; last = performance.now(); requestAnimationFrame(step); } }
        document.addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            var c = e.target.closest && e.target.closest('.card'); if (!c || !ok(c)) return;
            var r = c.getBoundingClientRect(), px = (e.clientX - r.left) / r.width - 0.5, py = (e.clientY - r.top) / r.height - 0.5;
            var lim = Math.max(1.5, Math.min(6, 1400 / r.width)), s = S(c);
            s.tx = -py * lim; s.ty = px * lim; s.tz = 4; active.add(c); wake();
        }, { passive: true });
        document.addEventListener('pointerout', function (e) {
            var c = e.target.closest && e.target.closest('.card');
            if (!c || c.contains(e.relatedTarget) || !c._fxT) return;
            c._fxT.tx = 0; c._fxT.ty = 0; c._fxT.tz = 0;
            c._fxT.vx += (Math.random() - 0.5) * 30; c._fxT.vy += (Math.random() - 0.5) * 30;
            active.add(c); wake();
        }, true);
    };
    M.goo = function () {
        var host = document.querySelector('.navbar-center'), items = navItems();
        if (!host || !items.length) return;
        var s = slider(host, 'fx-goo'), goo = s.el;
        var g = { x: 0, w: 0, vx: 0, vw: 0, tx: 0, tw: 0, o: 0, to: 0 };
        function aim(item, snap) {
            if (!item) { g.to = 0; return; }
            var hr = host.getBoundingClientRect(), r = item.getBoundingClientRect();
            g.tx = r.left - hr.left; g.tw = r.width; g.to = 1;
            if (snap) { g.x = g.tx; g.w = g.tw; }
        }
        var act = navActive(); aim(act, true);
        var last = performance.now(), loop = false;
        function step(now) {
            var dt = Math.min(0.035, (now - last) / 1000); last = now;
            g.vx += ((g.tx - g.x) * 220 - g.vx * 17) * dt; g.x += g.vx * dt;
            g.vw += ((g.tw - g.w) * 260 - g.vw * 16) * dt; g.w += g.vw * dt;
            g.o += (g.to - g.o) * Math.min(1, dt * 12);
            var st = reduced ? 0 : Math.min(0.35, Math.abs(g.vx) / 2600);
            goo.style.width = Math.max(10, g.w) + 'px';
            goo.style.transform = 'translateX(' + g.x.toFixed(2) + 'px) scale(' + (1 + st).toFixed(3) + ',' + (1 - st * 0.6).toFixed(3) + ')';
            goo.style.opacity = g.o.toFixed(3);
            if (Math.abs(g.vx) + Math.abs(g.vw) > 0.5 || Math.abs(g.tx - g.x) > 0.5 || Math.abs(g.to - g.o) > 0.01) requestAnimationFrame(step); else loop = false;
        }
        function wake() { if (reduced) { g.x = g.tx; g.w = g.tw; g.o = g.to; step(performance.now()); return; } if (!loop) { loop = true; last = performance.now(); requestAnimationFrame(step); } }
        var ink = getComputedStyle(root).getPropertyValue('--on-primary').trim() || '#fff';
        function paint(target) { items.forEach(function (o) { o.style.color = o === target ? ink : ''; }); }
        items.forEach(function (a) { a.addEventListener('pointerenter', function () { aim(a); wake(); paint(a); kick(1.6); }); });
        host.addEventListener('pointerleave', function () { aim(act); wake(); paint(act); });
        addEventListener('resize', function () { aim(act, true); wake(); });
        document.fonts && document.fonts.ready.then(function () { aim(act, true); wake(); });
        paint(act); wake();
    };
    M.drop = function () { entrance('fx-drop', 55, 14); };

    /* ── Riso ── */
    M.marker = function () {
        document.querySelectorAll('.navbar-nav a.active, .navbar-nav-toggle.active').forEach(function (a) {
            var r = function (n) { return (Math.random() * 2 - 1) * n; }, pts = [];
            for (var i = 0; i <= 14; i++) { var t = i / 14 * Math.PI * 2.18 - 0.4; pts.push((50 + Math.cos(t) * (47 + r(3))).toFixed(1) + ' ' + (50 + Math.sin(t) * (40 + r(4))).toFixed(1)); }
            if (getComputedStyle(a).position === 'static') a.style.position = 'relative';
            a.appendChild(el('span', '', '<svg class="fx-circle" viewBox="0 0 100 100" preserveAspectRatio="none"><path vector-effect="non-scaling-stroke" d="M' + pts.join(' L') + '"/></svg>'));
        });
    };
    M.slip = function () {
        if (reduced) return;
        var lastY = scrollY, mis = 0, raf = 0, lastT = performance.now();
        function step(now) {
            var dt = Math.max(1, now - lastT); lastT = now;
            var v = Math.abs(scrollY - lastY) / dt; lastY = scrollY;
            mis = Math.min(7, mis * 0.86 + v * 2.2);
            root.style.setProperty('--mis', mis.toFixed(2) + 'px');
            raf = mis > 0.05 ? requestAnimationFrame(step) : 0;
            if (!raf) root.style.setProperty('--mis', '0px');
        }
        addEventListener('scroll', function () { if (!raf) { lastT = performance.now(); raf = requestAnimationFrame(step); } }, { passive: true });
    };

    /* ── Postmark ── */
    function waves(x0, x1, rows, y0, gap) {
        var d = '';
        for (var r = 0; r < rows; r++) { d += 'M' + x0 + ' ' + (y0 + r * gap); for (var x = x0; x < x1; x += 12) d += ' q3 -4 6 0 t6 0'; }
        return d;
    }
    M.postmark = function () {
        var h = title(); if (!h) return;
        var d = new Date(), id = 'fx-ring-' + Math.random().toString(36).slice(2, 7);
        var l1 = String(d.getDate()).padStart(2, '0') + ' ' + d.toLocaleDateString(undefined, { month: 'short' }).toUpperCase();
        h.closest('.page-header, .page-toolbar').appendChild(el('div', 'fx-postmark',
            '<svg viewBox="0 0 168 96" fill="none" stroke="currentColor"><path d="' + waves(2, 82, 5, 26, 11) + '" stroke-width="2.2"/>' +
            '<circle cx="124" cy="48" r="40" stroke-width="2.4"/><circle cx="124" cy="48" r="27" stroke-width="1.4"/>' +
            '<defs><path id="' + id + '" d="M124 48 m-33 0 a33 33 0 1 1 66 0 a33 33 0 1 1 -66 0"/></defs>' +
            '<text font-family="IBM Plex Mono, monospace" font-size="8.5" font-weight="600" letter-spacing="2.2" fill="currentColor" stroke="none"><textPath href="#' + id + '">ROAMLY POST · PAR AVION ·</textPath></text>' +
            '<text x="124" y="46" text-anchor="middle" font-family="IBM Plex Mono, monospace" font-size="10" font-weight="600" fill="currentColor" stroke="none">' + l1 + '</text>' +
            '<text x="124" y="58" text-anchor="middle" font-family="IBM Plex Mono, monospace" font-size="9" fill="currentColor" stroke="none">' + d.getFullYear() + '</text></svg>'));
    };
    M.cancel = function () {
        var seed = location.pathname.length * 7 + 3;
        document.querySelectorAll('.card.stat-card, .grid > .card').forEach(function (c, i) {
            seed = (seed * 9301 + 49297) % 233280;
            if (seed / 233280 > 0.5) return;
            if (getComputedStyle(c).position === 'static') c.classList.add('fx-rel');
            var m = el('div', 'fx-cancel', '<svg viewBox="0 0 150 70" fill="none" stroke="currentColor" stroke-width="2.4"><path d="' + waves(0, 90, 4, 16, 11) + '"/><circle cx="118" cy="34" r="27"/><circle cx="118" cy="34" r="19" stroke-width="1.2"/></svg>');
            m.style.setProperty('--fx-rot', (-18 + (seed % 30)) + 'deg');
            m.style.animationDelay = (200 + i * 70) + 'ms';
            c.appendChild(m);
        });
    };

    /* ── Broadsheet ── */
    M.byline = function () {
        var h = title(); if (!h) return;
        var text = titleText(h);
        var k = el('div', 'fx-section'); k.textContent = 'Section ' + String.fromCharCode(65 + (text.length % 6)) + ' · ' + text;
        aboveTitle(k);
        var by = el('div', 'fx-byline', 'By <b></b> · Filed ' + new Date().toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }));
        by.querySelector('b').textContent = userName() || 'Staff';
        var lead = h.parentNode.querySelector('.page-lead');
        (lead || h).insertAdjacentElement('afterend', by);
    };

    /* ── Blueprint ── */
    M.dims = function () {
        var h = title(); if (!h) return;
        var d = el('span', 'fx-dim', '<i></i>'); h.appendChild(d);
        var lab = d.querySelector('i'), measure = function () { lab.textContent = Math.round(h.getBoundingClientRect().width) + ' px'; };
        measure(); if ('ResizeObserver' in window) new ResizeObserver(measure).observe(h);
    };
    M.callout = function () {
        if (!fine) return;
        var cur = null, co = null;
        document.addEventListener('pointerover', function (e) {
            var c = e.target.closest && e.target.closest('.card');
            if (c === cur) return;
            if (co) { co.remove(); co = null; }
            cur = c; if (!c) return;
            if (getComputedStyle(c).position === 'static') c.classList.add('fx-rel');
            var r = c.getBoundingClientRect();
            co = el('div', 'fx-callout', '<span></span>');
            co.firstChild.textContent = Math.round(r.width) + ' × ' + Math.round(r.height);
            c.appendChild(co);
        }, true);
    };
    M.crosshair = function () {
        if (!fine || reduced) return;
        var xh = el('div', 'fx-xh'), yh = el('div', 'fx-yh'), rd = el('div', 'fx-coords');
        [xh, yh, rd].forEach(function (n) { n.style.display = 'none'; document.body.appendChild(n); });
        var mx = 0, my = 0, raf = 0;
        function paint() {
            raf = 0;
            xh.style.transform = 'translateY(' + my + 'px)'; yh.style.transform = 'translateX(' + mx + 'px)';
            rd.style.transform = 'translate(' + (mx + 10) + 'px,' + (my + 8) + 'px)';
            rd.textContent = 'X ' + String(mx).padStart(4, '0') + '  Y ' + String(my + Math.round(scrollY)).padStart(4, '0');
        }
        addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            mx = Math.round(e.clientX); my = Math.round(e.clientY);
            var show = e.target.closest && e.target.closest('.maplibregl-map') ? 'none' : '';
            xh.style.display = yh.style.display = rd.style.display = show;
            if (!raf) raf = requestAnimationFrame(paint);
        }, { passive: true });
        document.addEventListener('pointerleave', function () { xh.style.display = yh.style.display = rd.style.display = 'none'; });
    };
    M.titleblock = function () {
        var m = document.querySelector('.maplibregl-map');
        if (!document.querySelector('main .container') || (m && m.getBoundingClientRect().height > innerHeight * 0.5)) return;
        var rows = [['Project', 'Roamly'], ['Drawing', (titleText(title()) || document.title).slice(0, 22)], ['Drawn by', userName() || '—'], ['Date', new Date().toISOString().slice(0, 10)], ['Scale', '1 : 1']];
        var tb = el('div', 'fx-titleblock', '<div class="fx-tb-name">ROAMLY</div>' + rows.map(function () { return '<div></div><div></div>'; }).join(''));
        var cells = tb.querySelectorAll('div:not(.fx-tb-name)');
        rows.forEach(function (r, i) { cells[i * 2].textContent = r[0]; cells[i * 2 + 1].textContent = r[1]; });
        document.body.appendChild(tb);
    };

    /* ── Basecamp ── */
    M.boot = function () {
        var seen = false;
        try { seen = sessionStorage.getItem('roamly_fx_booted') === '1'; sessionStorage.setItem('roamly_fx_booted', '1'); } catch (e) { seen = true; }
        if (seen || reduced) return;
        var b = el('div', 'fx-boot'); document.body.appendChild(b);
        var lines = ['ROAMLY FIELD TERMINAL  (C) ' + new Date().getFullYear(), 'BIOS 1.99 ............................ OK', 'MEMORY 640K .......................... OK',
            'GNSS RECEIVER ........................ LOCK', 'TRACK STORE .......................... MOUNTED', 'MAPS ................................. READY', '', 'WELCOME BACK.'];
        var i = 0, done = false;
        function finish() { if (done) return; done = true; b.classList.add('done'); setTimeout(function () { b.remove(); }, 400); removeEventListener('keydown', finish); }
        b.addEventListener('click', finish); addEventListener('keydown', finish);
        (function next() { if (done) return; if (i >= lines.length) { setTimeout(finish, 260); return; } b.textContent += lines[i++] + '\n'; setTimeout(next, 85); })();
    };
    M.prompt = function () {
        var h = title(); if (!h) return;
        var cmd = 'open ~/' + (slug(titleText(h)) || 'home');
        var p = el('div', 'fx-prompt', '<b></b>:~$ <span></span>');
        p.querySelector('b').textContent = (slug(userName()) || 'user') + '@roamly';
        aboveTitle(p);
        var out = p.querySelector('span');
        if (reduced) { out.textContent = cmd; return; }
        var n = 0;
        (function type() { out.textContent = cmd.slice(0, ++n); if (n < cmd.length) setTimeout(type, 28 + Math.random() * 40); })();
    };

    /* ── Pebble ── */
    M.stone = function () {
        var host = document.querySelector('.navbar-center'), items = navItems();
        if (host && items.length) {
            var s = slider(host, 'fx-thumb');
            s.aim(navActive(), true);
            items.forEach(function (a) { a.addEventListener('pointerenter', function () { s.aim(a); }); });
            host.addEventListener('pointerleave', function () { s.aim(navActive()); });
            addEventListener('resize', function () { s.aim(navActive(), true); });
            document.fonts && document.fonts.ready.then(function () { s.aim(navActive(), true); });
        }
        document.querySelectorAll('.pill-tabs').forEach(function (g) {
            var act = function () { return g.querySelector('.tab-btn.active'); };
            var t = slider(g, 'fx-thumb');
            t.aim(act(), true);
            new MutationObserver(function () { t.aim(act()); }).observe(g, { subtree: true, attributes: true, attributeFilter: ['class'] });
            addEventListener('resize', function () { t.aim(act(), true); });
            document.fonts && document.fonts.ready.then(function () { t.aim(act(), true); });
        });
    };
    M.sheen = function () {
        if (!fine || reduced) return;
        var cur = null, sh = null;
        document.addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            var c = e.target.closest && e.target.closest('.card');
            if (c !== cur) {
                if (sh) { sh.remove(); sh = null; }
                cur = c;
                if (c) { if (getComputedStyle(c).position === 'static') c.classList.add('fx-rel'); sh = el('span', 'fx-sheen'); c.insertBefore(sh, c.firstChild); }
            }
            if (!c || !sh) return;
            var r = c.getBoundingClientRect();
            sh.style.setProperty('--fx-x', (e.clientX - r.left) + 'px');
            sh.style.setProperty('--fx-y', (e.clientY - r.top) + 'px');
        }, { passive: true });
    };
    M.rise = function () { entrance('fx-rise', 40, 16); };

    /* ── Tidepool ── */
    M.splash = function () {
        if (reduced) return;
        document.addEventListener('pointerdown', function (e) {
            var b = e.target.closest && e.target.closest('.btn, .rly-dialog-btn'); if (!b) return;
            var r = b.getBoundingClientRect(), d = Math.max(r.width, r.height) * 2.2, s = el('span', 'fx-ripple');
            b.classList.add('fx-ripple-host');
            s.style.width = s.style.height = d + 'px';
            s.style.left = (e.clientX - r.left - d / 2) + 'px'; s.style.top = (e.clientY - r.top - d / 2) + 'px';
            b.appendChild(s); setTimeout(function () { s.remove(); }, 750);
        }, true);
    };

    /* ── Bento ── */
    M.countup = function () {
        var NUM = /^(\D*?)(-?\d{1,3}(?:,\d{3})+|-?\d+)(\.\d+)?(\D*)$/;
        watchStats(function (v) {
            var text = v.textContent;
            if (v._fxSelf === text || v.children.length) return;
            var m = NUM.exec(text.trim()); if (!m) return;
            var val = parseFloat(m[2].replace(/,/g, '') + (m[3] || ''));
            if (!isFinite(val) || Math.abs(val) > 1e12) return;
            var dec = m[3] ? m[3].length - 1 : 0, comma = m[2].indexOf(',') !== -1;
            var from = v._fxLast != null ? v._fxLast : 0;
            v._fxLast = val;
            if (reduced || from === val) return;
            var fmt = function (x) { var s = x.toFixed(dec); if (comma) { var p = s.split('.'); p[0] = p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ','); s = p.join('.'); } return m[1] + s + m[4]; };
            if (v._fxRaf) cancelAnimationFrame(v._fxRaf);
            var t0 = performance.now();
            v.setAttribute('aria-label', text);
            (function step(now) {
                var k = Math.min(1, (now - t0) / 900), e = 1 - Math.pow(2, -10 * k);
                var out = k >= 1 ? text : fmt(from + (val - from) * e);
                v._fxSelf = out; v.textContent = out;
                if (k < 1) v._fxRaf = requestAnimationFrame(step); else { v._fxRaf = 0; v.removeAttribute('aria-label'); }
            })(t0);
        });
    };
    M.pop = function () { entrance('fx-pop', 35, 18); };

    // Run in a fixed order so title ornaments stack predictably.
    var ORDER = ['boot', 'lamp', 'compass', 'kicker', 'byline', 'prompt', 'roundel', 'dims', 'postmark', 'clock', 'pin', 'goo', 'stone', 'marker',
        'stamp', 'ink', 'splash', 'squish', 'tilt', 'sheen', 'callout', 'crosshair', 'titleblock', 'slip', 'flap', 'countup', 'cancel', 'drop', 'rise', 'pop'];
    ready(function () {
        ORDER.forEach(function (id) {
            if (!on(id) || !M[id]) return;
            try { M[id](); } catch (e) { if (window.console) console.warn('appearance extra ' + id + ' failed', e); }
        });
    });
})();
