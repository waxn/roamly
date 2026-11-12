/* Jelly — behaviour.
   1. Raymarched gummy blobs (WebGL, no library) drifting behind the page;
      they bulge away from the pointer and wobble as they go.
   2. A raymarched 3D gummy map pin as the logo, which boings when you
      hover the nav or poke it.
   3. Spring physics: buttons/badges/tabs squash on press and wobble back,
      cards tilt toward the pointer on a spring, cards drop in on load,
      and a goo blob in the navbar chases the pointer between sections.
   Honours prefers-reduced-motion (one still frame, no springs). */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-style') !== 'jelly') return;
    root.classList.add('jl-js');
    var reduced = window.RoamlyStyle && RoamlyStyle.reducedMotion();

    function ready(fn) {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn);
        else fn();
    }

    // ═══════════════════════════ WebGL ═══════════════════════════
    var VERT = 'attribute vec2 p;varying vec2 vUv;void main(){vUv=p;gl_Position=vec4(p,0.,1.);}';
    var FRAG = [
        'precision highp float;',
        'uniform vec2 uRes;uniform float uTime;uniform vec2 uMouse;uniform float uMode;uniform float uJig;uniform float uScroll;uniform float uLight;',
        'varying vec2 vUv;',
        'vec3 gcol;',
        'float smin(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}',
        'vec3 pal(float i){',
        '  if(i<.5) return vec3(1.,.31,.39);',
        '  if(i<1.5) return vec3(1.,.71,.15);',
        '  if(i<2.5) return vec3(.49,.88,.31);',
        '  if(i<3.5) return vec3(.25,.83,.84);',
        '  if(i<4.5) return vec3(1.,.48,.71);',
        '  return vec3(1.,.42,.3);',
        '}',
        'float sdRoundCone(vec3 p,float r1,float r2,float h){',
        '  vec2 q=vec2(length(p.xz),p.y);float b=(r1-r2)/h;float a=sqrt(1.-b*b);float k=dot(q,vec2(-b,a));',
        '  if(k<0.) return length(q)-r1; if(k>a*h) return length(q-vec2(0.,h))-r2; return dot(q,vec2(a,b))-r1;',
        '}',
        'mat2 rot(float a){float c=cos(a),s=sin(a);return mat2(c,-s,s,c);}',
        'float map(vec3 p){',
        '  if(uMode>.5){',
        '    float t=uTime;',
        '    p.y+=.06*sin(t*2.2);',
        '    p.xz*=rot(.55*sin(t*.7)+uJig*1.6);',
        '    p.xy*=rot(.12*sin(t*1.3)+uJig*.35);',
        '    vec3 q=p; float sq=uJig*.5; q.y/=1.+sq*.35; q.xz/=1.-sq*.18;',
        '    float cone=sdRoundCone(q-vec3(0.,-.86,0.),.07,.5,1.0);',
        '    float dimple=length(q-vec3(0.,.16,.46))-.2;',
        '    gcol=vec3(1.,.31,.39);',
        '    return max(cone,-dimple)+.01*sin(q.x*9.+t*3.)*sin(q.y*8.+t*2.);',
        '  }',
        '  float asp=uRes.x/uRes.y; float d=1e3; vec3 acc=vec3(0.); float ws=0.;',
        '  for(int i=0;i<6;i++){',
        '    float fi=float(i);',
        '    vec3 c=vec3(sin(uTime*(.07+.018*fi)+fi*2.1)*asp*.92, cos(uTime*(.06+.015*fi)+fi*1.3)*.86 + mod(uScroll*.35+fi*.7,2.6)-1.3, -.2+.25*sin(fi));',
        '    c.y=mix(c.y,cos(uTime*(.06+.015*fi)+fi*1.3)*.86,.65);',
        '    vec2 dm=c.xy-uMouse; float pull=exp(-dot(dm,dm)*4.);',
        '    c.xy+=normalize(dm+1e-4)*.28*pull;',
        '    float r=.105+.045*sin(fi*1.7+.5)+.018*sin(uTime*1.3+fi);',
        '    vec3 pp=p-c;',
        '    pp.y*=1.+.12*sin(uTime*2.+fi*3.);',
        '    float wob=.03*sin(pp.x*8.+uTime*2.1+fi)*sin(pp.y*7.+uTime*1.7)+.02*pull*sin(uTime*9.+fi);',
        '    float di=length(pp)-r+wob;',
        '    float w=1./(1.+pow(max(di,0.)*9.,2.));',
        '    acc+=pal(fi)*w; ws+=w;',
        '    d=smin(d,di,.12);',
        '  }',
        '  gcol=acc/max(ws,1e-4);',
        '  return d;',
        '}',
        'vec3 nrm(vec3 p){vec2 e=vec2(.0025,-.0025);',
        '  return normalize(e.xyy*map(p+e.xyy)+e.yyx*map(p+e.yyx)+e.yxy*map(p+e.yxy)+e.xxx*map(p+e.xxx));}',
        'void main(){',
        '  vec2 uv=vUv; uv.x*=uRes.x/uRes.y;',
        '  vec3 ro,rd;',
        '  if(uMode>.5){ ro=vec3(0.,0.,3.2); rd=normalize(vec3(uv*.62,-1.75)); }',
        '  else { ro=vec3(uv,2.); rd=vec3(0.,0.,-1.); }',
        '  float t=0.; float hit=0.; float md=1e3; float mt=0.;',
        '  for(int i=0;i<48;i++){ vec3 p=ro+rd*t; float d=map(p); if(d<md){md=d;mt=t;} if(d<.002){hit=1.;break;} t+=d*.9; if(t>5.)break; }',
        '  float px=2./uRes.y;',
        '  float cov=hit>.5?1.:smoothstep(px*1.6,0.,md);',
        '  if(cov<.002){ gl_FragColor=vec4(0.); return; }',
        '  if(hit<.5) t=mt;',
        '  vec3 p=ro+rd*t; map(p); vec3 base=gcol; vec3 n=nrm(p); vec3 v=-rd;',
        '  vec3 l=normalize(vec3(-.5,.8,.6));',
        '  float dif=max(dot(n,l),0.);',
        '  float spec=pow(max(dot(reflect(-l,n),v),0.),56.);',
        '  float strip=smoothstep(.955,.995,dot(n,normalize(vec3(-.42,.62,.66))));',
        '  float fres=pow(1.-max(dot(n,v),0.),2.4);',
        '  float back=pow(max(dot(n,normalize(vec3(.6,-.5,.4))),0.),2.);',
        '  vec3 col=base*(.24+.62*dif)+base*base*.32;',
        '  col+=base*fres*.75;',
        '  col+=mix(base,vec3(1.),.4)*back*.35;',
        '  col=mix(col,base*1.3,smoothstep(.55,1.,n.z)*.22);',
        '  col+=vec3(1.)*(spec*.85+strip*.55);',
        '  float a=uMode>.5?1.:(.8+.2*fres)*uLight;',
        '  a*=cov;',
        '  gl_FragColor=vec4(col*a,a);',
        '}'
    ].join('\n');

    function makeGL(canvas) {
        var gl = canvas.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: false, powerPreference: 'low-power' });
        if (!gl) return null;
        function sh(type, src) {
            var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
            if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) { console.warn('jelly shader', gl.getShaderInfoLog(s)); return null; }
            return s;
        }
        var vs = sh(gl.VERTEX_SHADER, VERT), fs = sh(gl.FRAGMENT_SHADER, FRAG);
        if (!vs || !fs) return null;
        var pr = gl.createProgram(); gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr);
        if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) return null;
        gl.useProgram(pr);
        var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
        var loc = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
        var U = {};
        ['uRes', 'uTime', 'uMouse', 'uMode', 'uJig', 'uScroll', 'uLight'].forEach(function (n) { U[n] = gl.getUniformLocation(pr, n); });
        return {
            gl: gl,
            draw: function (w, h, o) {
                gl.viewport(0, 0, w, h);
                gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
                gl.uniform2f(U.uRes, w, h);
                gl.uniform1f(U.uTime, o.time);
                gl.uniform2f(U.uMouse, o.mx || 0, o.my || 0);
                gl.uniform1f(U.uMode, o.mode);
                gl.uniform1f(U.uJig, o.jig || 0);
                gl.uniform1f(U.uScroll, o.scroll || 0);
                gl.uniform1f(U.uLight, o.light == null ? 1 : o.light);
                gl.drawArrays(gl.TRIANGLES, 0, 3);
            }
        };
    }

    var mouse = { x: 0, y: 0, tx: 0, ty: 0 };
    addEventListener('pointermove', function (e) {
        mouse.tx = (e.clientX / innerWidth * 2 - 1) * (innerWidth / innerHeight);
        mouse.ty = -(e.clientY / innerHeight * 2 - 1);
    }, { passive: true });

    // spring state for the pin
    var pin = { v: 0, vel: 0 };
    function kick(amount) { pin.vel += amount; }

    function startField() {
        var cv = document.createElement('canvas');
        cv.className = 'jl-field';
        cv.setAttribute('aria-hidden', 'true');
        document.body.insertBefore(cv, document.body.firstChild);
        var G = makeGL(cv);
        if (!G) { cv.remove(); return null; }
        var scale = 0.6, slow = 0, frames = 0, last = performance.now(), t0 = last, running = true, hidden = false;
        function size() {
            var w = Math.min(1100, Math.round(innerWidth * scale)), h = Math.round(w * innerHeight / innerWidth);
            if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
        }
        function covered() {
            // a full-page map hides the field; don't spend the GPU on it
            var m = document.querySelector('.maplibregl-map');
            if (!m) return false;
            var r = m.getBoundingClientRect();
            return r.width * r.height > innerWidth * innerHeight * 0.45;
        }
        function frame(now) {
            if (!running) return;
            var dt = now - last; last = now;
            frames++;
            if (dt > 28) slow++;
            if (frames === 90) {
                if (slow > 45 && scale > 0.3) { scale = 0.32; }
                frames = 0; slow = 0;
                hidden = covered();
                cv.style.display = hidden ? 'none' : '';
            }
            mouse.x += (mouse.tx - mouse.x) * 0.06; mouse.y += (mouse.ty - mouse.y) * 0.06;
            if (!hidden && !document.hidden) {
                size();
                G.draw(cv.width, cv.height, {
                    time: (now - t0) / 1000, mode: 0, mx: mouse.x, my: mouse.y,
                    scroll: scrollY / innerHeight,
                    light: root.getAttribute('data-theme') === 'light' ? 0.8 : 1
                });
            }
            requestAnimationFrame(frame);
        }
        size();
        if (reduced) {
            G.draw(cv.width, cv.height, { time: 12, mode: 0, mx: 9, my: 9, scroll: 0 });
            addEventListener('resize', function () { size(); G.draw(cv.width, cv.height, { time: 12, mode: 0, mx: 9, my: 9, scroll: 0 }); });
        } else {
            requestAnimationFrame(frame);
            setTimeout(function () { hidden = covered(); cv.style.display = hidden ? 'none' : ''; }, 1500);
        }
        return cv;
    }

    function startPin() {
        var brand = document.querySelector('.navbar-brand');
        if (!brand) return;
        var cv = document.createElement('canvas');
        cv.className = 'jl-pin';
        cv.setAttribute('aria-hidden', 'true');
        var G = makeGL(cv);
        if (!G) { var mk = brand.querySelector('.navbar-mark'); if (mk) mk.style.display = 'block'; return; }
        brand.insertBefore(cv, brand.firstChild);
        var dpr = Math.min(2, devicePixelRatio || 1);
        function size() {
            var s = Math.round(cv.getBoundingClientRect().width * dpr) || 92;
            if (cv.width !== s) { cv.width = s; cv.height = s; }
        }
        var t0 = performance.now(), last = t0;
        function frame(now) {
            var dt = Math.min(0.05, (now - last) / 1000); last = now;
            // damped spring toward 0
            pin.vel += (-pin.v * 180 - pin.vel * 9) * dt;
            pin.v += pin.vel * dt;
            if (!document.hidden) { size(); G.draw(cv.width, cv.height, { time: (now - t0) / 1000, mode: 1, jig: pin.v }); }
            requestAnimationFrame(frame);
        }
        size();
        if (reduced) { G.draw(cv.width, cv.height, { time: 0.6, mode: 1, jig: 0 }); return; }
        brand.addEventListener('pointerenter', function () { kick(5); });
        cv.addEventListener('pointerdown', function (e) { kick(-9); });
        kick(12);
        requestAnimationFrame(frame);
    }

    // ═══════════════════════════ springs ═══════════════════════════
    // Damped oscillation as WAAPI keyframes: no per-frame JS for squish.
    function wobbleFrames(amp, opts) {
        opts = opts || {};
        var n = opts.n || 14, f = opts.freq || 2.6, k = opts.damp || 4.2, base = opts.base || '';
        var out = [];
        for (var i = 0; i <= n; i++) {
            var t = i / n;
            var a = amp * Math.exp(-k * t) * Math.cos(2 * Math.PI * f * t);
            out.push({ transform: base + 'scale(' + (1 + a).toFixed(4) + ',' + (1 - a).toFixed(4) + ')' });
        }
        out[out.length - 1].transform = base + 'scale(1,1)';
        return out;
    }
    var SQUISH = '.btn, .badge, .pill-tabs .tab-btn, .navbar-nav a, .navbar-nav-toggle, .rly-dialog-btn, .notif-toggle, .navbar-user-toggle, .rly-select-trigger, .rly-date-trigger, .bottom-nav a, .rly-cal-day, .sl-swatch';
    var held = new WeakMap();
    function squishTarget(e) { return e.target.closest && e.target.closest(SQUISH); }

    function initSprings() {
        if (reduced) return;
        document.addEventListener('pointerdown', function (e) {
            var el = squishTarget(e); if (!el || el.disabled) return;
            var big = el.offsetWidth > 260;
            var a = el.animate([{ transform: 'scale(1,1)' }, { transform: big ? 'scale(1.02,0.96)' : 'scale(1.14,0.84) translateY(2px)' }], { duration: 110, easing: 'ease-out', fill: 'forwards' });
            held.set(el, a);
        }, true);
        function release(e) {
            var el = squishTarget(e); if (!el) return;
            var a = held.get(el); if (!a) return;
            held.delete(el);
            a.cancel();
            el.animate(wobbleFrames(el.offsetWidth > 260 ? 0.04 : 0.16, { freq: 2.4, damp: 4.5 }), { duration: 650, easing: 'linear' });
        }
        document.addEventListener('pointerup', release, true);
        document.addEventListener('pointercancel', release, true);
        document.addEventListener('pointerout', function (e) {
            var el = squishTarget(e);
            if (el && held.has(el) && !el.contains(e.relatedTarget)) release(e);
        }, true);
        // a little boing on hover
        document.addEventListener('pointerover', function (e) {
            if (e.pointerType !== 'mouse') return;
            var el = squishTarget(e);
            if (!el || el.contains(e.relatedTarget) || held.has(el) || el.offsetWidth > 260) return;
            if (el._jlBoing && performance.now() - el._jlBoing < 500) return;
            el._jlBoing = performance.now();
            el.animate(wobbleFrames(0.07, { freq: 2, damp: 5, n: 10 }), { duration: 420, easing: 'linear' });
        }, true);
        // titles jiggle when poked
        document.querySelectorAll('.page-header h1, .page-toolbar h1').forEach(function (h) {
            h.addEventListener('pointerenter', function () {
                h.animate(wobbleFrames(0.08, { freq: 2.2, damp: 4 }), { duration: 700 });
            });
        });
    }

    // card tilt: a tiny 2-axis spring per hovered card
    function initTilt() {
        if (reduced || !matchMedia('(hover: hover) and (pointer: fine)').matches) return;
        var active = new Set();
        function stateOf(c) {
            if (!c._jl) c._jl = { rx: 0, ry: 0, vx: 0, vy: 0, tx: 0, ty: 0, z: 0, vz: 0, tz: 0 };
            return c._jl;
        }
        function eligible(c) {
            return c.offsetHeight < 520 && !c.querySelector('.maplibregl-map, canvas, iframe, input, textarea, select, .rly-select');
        }
        var last = performance.now(), loop = false;
        function step(now) {
            var dt = Math.min(0.035, (now - last) / 1000); last = now;
            active.forEach(function (c) {
                var s = stateOf(c);
                s.vx += ((s.tx - s.rx) * 140 - s.vx * 11) * dt; s.rx += s.vx * dt;
                s.vy += ((s.ty - s.ry) * 140 - s.vy * 11) * dt; s.ry += s.vy * dt;
                s.vz += ((s.tz - s.z) * 160 - s.vz * 12) * dt; s.z += s.vz * dt;
                var settled = Math.abs(s.rx) + Math.abs(s.ry) + Math.abs(s.vx) + Math.abs(s.vy) + Math.abs(s.z) + Math.abs(s.vz) < 0.01 && !s.tx && !s.ty && !s.tz;
                if (settled) { c.style.transform = ''; active.delete(c); return; }
                c.style.transform = 'perspective(900px) rotateX(' + s.rx.toFixed(3) + 'deg) rotateY(' + s.ry.toFixed(3) + 'deg) translateY(' + (-s.z).toFixed(2) + 'px)';
            });
            if (active.size) requestAnimationFrame(step); else loop = false;
        }
        function wake() { if (!loop) { loop = true; last = performance.now(); requestAnimationFrame(step); } }
        document.addEventListener('pointermove', function (e) {
            if (e.pointerType !== 'mouse') return;
            var c = e.target.closest && e.target.closest('.card');
            if (!c || !eligible(c)) return;
            var r = c.getBoundingClientRect();
            var px = (e.clientX - r.left) / r.width - 0.5, py = (e.clientY - r.top) / r.height - 0.5;
            var lim = Math.max(1.5, Math.min(6, 1400 / r.width));
            var s = stateOf(c);
            s.tx = -py * lim; s.ty = px * lim; s.tz = 4;
            active.add(c); wake();
        }, { passive: true });
        document.addEventListener('pointerout', function (e) {
            var c = e.target.closest && e.target.closest('.card');
            if (!c || c.contains(e.relatedTarget) || !c._jl) return;
            c._jl.tx = 0; c._jl.ty = 0; c._jl.tz = 0;
            c._jl.vx += (Math.random() - 0.5) * 30; c._jl.vy += (Math.random() - 0.5) * 30;
            active.add(c); wake();
        }, true);
    }

    // goo blob that chases the pointer across the nav
    function initGoo() {
        var nav = document.querySelector('.navbar-nav');
        var host = document.querySelector('.navbar-center');
        if (!nav || !host) return;
        host.style.position = 'relative';
        var goo = document.createElement('span');
        goo.className = 'jl-goo';
        goo.setAttribute('aria-hidden', 'true');
        host.insertBefore(goo, host.firstChild);
        var items = Array.from(nav.querySelectorAll(':scope > li > a, :scope > li > .navbar-nav-toggle'));
        var activeEl = items.find(function (a) { return a.classList.contains('active'); });
        var g = { x: 0, w: 0, vx: 0, vw: 0, tx: 0, tw: 0, o: 0, to: 0 };
        function aim(el, snap) {
            if (!el) { g.to = 0; return; }
            var hr = host.getBoundingClientRect(), r = el.getBoundingClientRect();
            g.tx = r.left - hr.left; g.tw = r.width; g.to = 1;
            if (snap) { g.x = g.tx; g.w = g.tw; }
        }
        aim(activeEl, true);
        if (!activeEl) g.o = 0;
        var last = performance.now(), loop = false;
        function step(now) {
            var dt = Math.min(0.035, (now - last) / 1000); last = now;
            g.vx += ((g.tx - g.x) * 220 - g.vx * 17) * dt; g.x += g.vx * dt;
            g.vw += ((g.tw - g.w) * 260 - g.vw * 16) * dt; g.w += g.vw * dt;
            g.o += (g.to - g.o) * Math.min(1, dt * 12);
            var stretch = Math.min(0.35, Math.abs(g.vx) / 2600);
            goo.style.width = Math.max(10, g.w) + 'px';
            goo.style.transform = 'translateX(' + g.x.toFixed(2) + 'px) scale(' + (1 + stretch).toFixed(3) + ',' + (1 - stretch * 0.6).toFixed(3) + ')';
            goo.style.opacity = g.o.toFixed(3);
            var moving = Math.abs(g.vx) + Math.abs(g.vw) > 0.5 || Math.abs(g.tx - g.x) > 0.5 || Math.abs(g.to - g.o) > 0.01;
            if (moving) requestAnimationFrame(step); else loop = false;
        }
        function wake() { if (!loop) { loop = true; last = performance.now(); requestAnimationFrame(step); } }
        if (reduced) { step(performance.now()); }
        items.forEach(function (a) {
            a.addEventListener('pointerenter', function () {
                aim(a); wake(); kick(1.6);
                items.forEach(function (o) { o.style.color = o === a ? '#fff' : ''; });
            });
        });
        nav.addEventListener('pointerleave', function () {
            aim(activeEl); wake();
            items.forEach(function (o) { o.style.color = ''; });
        });
        addEventListener('resize', function () { aim(activeEl, true); wake(); });
        document.fonts && document.fonts.ready.then(function () { aim(activeEl, true); wake(); });
        wake();
    }

    function initDrops() {
        if (reduced) return;
        var cards = Array.from(document.querySelectorAll('.container .card')).filter(function (c) {
            var r = c.getBoundingClientRect(); return r.top < innerHeight && r.bottom > 0;
        }).slice(0, 14);
        cards.forEach(function (c, i) {
            c.style.animationDelay = (i * 55) + 'ms';
            c.classList.add('jl-in');
            c.addEventListener('animationend', function () { c.classList.remove('jl-in'); c.style.animationDelay = ''; }, { once: true });
        });
    }

    ready(function () {
        startField();
        startPin();
        initSprings();
        initTilt();
        initGoo();
        initDrops();
    });
})();
