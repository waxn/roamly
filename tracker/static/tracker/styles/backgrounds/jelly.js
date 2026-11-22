/* Background: jelly — raymarched gummy blobs (WebGL, no library) drifting
   behind the page in the active palette's colours. They bulge away from the
   pointer and wobble as they go. Pauses when hidden or under a full-page
   map; one still frame with reduced motion. */
(function () {
    'use strict';
    var root = document.documentElement;
    if (root.getAttribute('data-bg') !== 'jelly') return;
    var RS = window.RoamlyStyle;
    var reduced = RS && RS.reducedMotion();

    var VERT = 'attribute vec2 p;varying vec2 vUv;void main(){vUv=p;gl_Position=vec4(p,0.,1.);}';
    var FRAG = [
        'precision highp float;',
        'uniform vec2 uRes;uniform float uTime;uniform float uScroll;uniform float uLight;uniform vec3 uPal[6];uniform vec3 uC[6];uniform float uSq[6];',
        'varying vec2 vUv;',
        'vec3 gcol;',
        'float smin(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}',
        'vec3 pal(int i){ for(int k=0;k<6;k++){ if(k==i) return uPal[k]; } return uPal[0]; }',
        'float map(vec3 p){',
        '  float asp=uRes.x/uRes.y; float sz=clamp(asp*.75,.45,1.); float d=1e3; vec3 acc=vec3(0.); float ws=0.;',
        '  for(int i=0;i<6;i++){',
        '    float fi=float(i);',
        '    vec3 c=uC[i]; float pull=uSq[i];',
        '    float r=(.105+.045*sin(fi*1.7+.5)+.018*sin(uTime*1.3+fi))*sz;',
        '    vec3 pp=p-c; pp.y*=1.+.12*sin(uTime*2.+fi*3.);',
        '    float wob=.03*sin(pp.x*8.+uTime*2.1+fi)*sin(pp.y*7.+uTime*1.7)+.02*pull*sin(uTime*9.+fi);',
        '    float di=length(pp)-r+wob;',
        '    float w=1./(1.+pow(max(di,0.)*9.,2.));',
        '    acc+=pal(i)*w; ws+=w;',
        '    d=smin(d,di,.12);',
        '  }',
        '  gcol=acc/max(ws,1e-4);',
        '  return d;',
        '}',
        'vec3 nrm(vec3 p){vec2 e=vec2(.0025,-.0025);',
        '  return normalize(e.xyy*map(p+e.xyy)+e.yyx*map(p+e.yyx)+e.yxy*map(p+e.yxy)+e.xxx*map(p+e.xxx));}',
        'void main(){',
        '  vec2 uv=vUv; uv.x*=uRes.x/uRes.y;',
        '  vec3 ro=vec3(uv,2.), rd=vec3(0.,0.,-1.);',
        '  float t=0., hit=0., md=1e3, mt=0.;',
        '  for(int i=0;i<48;i++){ vec3 p=ro+rd*t; float d=map(p); if(d<md){md=d;mt=t;} if(d<.002){hit=1.;break;} t+=d*.9; if(t>5.)break; }',
        '  float cov=hit>.5?1.:smoothstep(3.2/uRes.y,0.,md);',
        '  if(cov<.002){ gl_FragColor=vec4(0.); return; }',
        '  if(hit<.5) t=mt;',
        '  vec3 p=ro+rd*t; map(p); vec3 base=gcol; vec3 n=nrm(p); vec3 v=-rd;',
        '  vec3 l=normalize(vec3(-.5,.8,.6));',
        '  float dif=max(dot(n,l),0.), spec=pow(max(dot(reflect(-l,n),v),0.),56.);',
        '  float strip=smoothstep(.955,.995,dot(n,normalize(vec3(-.42,.62,.66))));',
        '  float fres=pow(1.-max(dot(n,v),0.),2.4), back=pow(max(dot(n,normalize(vec3(.6,-.5,.4))),0.),2.);',
        '  vec3 col=base*(.24+.62*dif)+base*base*.32+base*fres*.75+mix(base,vec3(1.),.4)*back*.35;',
        '  col=mix(col,base*1.3,smoothstep(.55,1.,n.z)*.22)+vec3(1.)*(spec*.85+strip*.55);',
        '  float a=(.8+.2*fres)*uLight*cov;',
        '  gl_FragColor=vec4(col*a,a);',
        '}'
    ].join('\n');

    function tokenRGB(name) {
        var e = document.createElement('i');
        e.style.cssText = 'display:none;color:var(' + name + ')';
        document.body.appendChild(e);
        var c = getComputedStyle(e).color; e.remove();
        var m = c.match(/[\d.]+/g) || [255, 255, 255];
        var srgb = c.indexOf('color(') === 0;
        return [0, 1, 2].map(function (i) { var v = parseFloat(m[i]); return srgb ? v : v / 255; });
    }

    function start() {
        var cv = document.createElement('canvas');
        cv.className = 'bgj-field';
        cv.setAttribute('aria-hidden', 'true');
        document.body.insertBefore(cv, document.body.firstChild);
        var gl = cv.getContext('webgl', { premultipliedAlpha: true, alpha: true, antialias: false, powerPreference: 'low-power' });
        if (!gl) { cv.remove(); return; }
        function sh(type, src) { var s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return gl.getShaderParameter(s, gl.COMPILE_STATUS) ? s : null; }
        var vs = sh(gl.VERTEX_SHADER, VERT), fs = sh(gl.FRAGMENT_SHADER, FRAG);
        if (!vs || !fs) { cv.remove(); return; }
        var pr = gl.createProgram(); gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr); gl.useProgram(pr);
        var b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
        var loc = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
        var U = {};
        ['uRes', 'uTime', 'uMouse', 'uScroll', 'uLight', 'uPal'].forEach(function (n) { U[n] = gl.getUniformLocation(pr, n); });
        var pal = [];
        ['--clay-coral', '--clay-amber', '--clay-mint', '--clay-sky', '--clay-lavender', '--primary'].forEach(function (t) { pal = pal.concat(tokenRGB(t)); });
        gl.uniform3fv(U.uPal, new Float32Array(pal));
        U.uC = gl.getUniformLocation(pr, 'uC'); U.uSq = gl.getUniformLocation(pr, 'uSq');
        var N = 6, bl = [], cArr = new Float32Array(N * 3), sArr = new Float32Array(N);
        for (var bi = 0; bi < N; bi++) bl.push({ ox: 0, oy: 0, vx: 0, vy: 0 });
        function radius(i) { var asp = innerWidth / innerHeight, sz = Math.max(.45, Math.min(1, asp * .75)); return (.105 + .045 * Math.sin(i * 1.7 + .5)) * sz * 1.5; }
        function home(i, time) {
            var asp = innerWidth / innerHeight;
            return [Math.sin(time * (.07 + .018 * i) + i * 2.1) * asp * .92,
                    Math.cos(time * (.06 + .015 * i) + i * 1.3) * .86 + Math.sin(scrollY / innerHeight * .6 + i) * .12,
                    -.2 + .25 * Math.sin(i)];
        }
        // Physics: the pointer shoves blobs (and hands them its own velocity), a weak spring
        // slowly draws them back to their drifting orbit, walls bounce them.
        function step(time, dt) {
            var asp = innerWidth / innerHeight;
            for (var i = 0; i < N; i++) {
                var b = bl[i], h = home(i, time), r = radius(i);
                var cx = h[0] + b.ox, cy = h[1] + b.oy;
                var dx = cx - mouse.tx, dy = cy - mouse.ty, d = Math.sqrt(dx * dx + dy * dy) + 1e-4, R = .3 + r;
                var f = 0;
                if (d < R) {
                    f = 1 - d / R;
                    b.vx += dx / d * f * f * 26 * dt + mouse.vx * f * Math.min(1, dt * 14) * .55;
                    b.vy += dy / d * f * f * 26 * dt + mouse.vy * f * Math.min(1, dt * 14) * .55;
                }
                b.vx -= b.ox * .28 * dt; b.vy -= b.oy * .28 * dt;
                var damp = Math.exp(-.7 * dt); b.vx *= damp; b.vy *= damp;
                b.ox += b.vx * dt; b.oy += b.vy * dt;
                cx = h[0] + b.ox; cy = h[1] + b.oy;
                var lx = asp - r * .4, ly = 1 - r * .4;
                if (cx > lx) { b.ox -= cx - lx; b.vx = -Math.abs(b.vx) * .6; } else if (cx < -lx) { b.ox -= cx + lx; b.vx = Math.abs(b.vx) * .6; }
                if (cy > ly) { b.oy -= cy - ly; b.vy = -Math.abs(b.vy) * .6; } else if (cy < -ly) { b.oy -= cy + ly; b.vy = Math.abs(b.vy) * .6; }
                cArr[i * 3] = h[0] + b.ox; cArr[i * 3 + 1] = h[1] + b.oy; cArr[i * 3 + 2] = h[2];
                sArr[i] = Math.min(1, f + Math.sqrt(b.vx * b.vx + b.vy * b.vy) * .5);
            }
        }

        var mouse = { tx: 9, ty: 9, vx: 0, vy: 0, px: 9, py: 9 };
        addEventListener('pointermove', function (e) {
            mouse.tx = (e.clientX / innerWidth * 2 - 1) * (innerWidth / innerHeight);
            mouse.ty = -(e.clientY / innerHeight * 2 - 1);
        }, { passive: true });
        document.addEventListener('pointerleave', function () { mouse.tx = mouse.ty = 9; });
        var scale = 0.6, frames = 0, slow = 0, last = performance.now(), t0 = last, hidden = false;
        function size() {
            var w = Math.min(1100, Math.round(innerWidth * scale)), h = Math.round(w * innerHeight / innerWidth);
            if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
        }
        function covered() {
            var m = document.querySelector('.maplibregl-map');
            if (!m) return false;
            var r = m.getBoundingClientRect();
            return r.width * r.height > innerWidth * innerHeight * 0.45;
        }
        function draw(time) {
            size();
            gl.viewport(0, 0, cv.width, cv.height);
            gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
            gl.uniform2f(U.uRes, cv.width, cv.height);
            gl.uniform1f(U.uTime, time);
            gl.uniform3fv(U.uC, cArr); gl.uniform1fv(U.uSq, sArr);
            gl.uniform1f(U.uScroll, scrollY / innerHeight);
            gl.uniform1f(U.uLight, root.getAttribute('data-theme') === 'light' ? 0.8 : 1);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        if (reduced) { step(12, 0); draw(12); addEventListener('resize', function () { step(12, 0); draw(12); }); return; }
        (function frame(now) {
            var dt = now - last; last = now;
            if (++frames % 90 === 0) {
                if (slow > 45 && scale > 0.3) scale = 0.32;
                slow = 0; hidden = covered(); cv.style.display = hidden ? 'none' : '';
            }
            if (dt > 28) slow++;
            var sdt = Math.min(.033, Math.max(.001, dt / 1000));
            mouse.vx = mouse.px > 8 || mouse.tx > 8 ? 0 : (mouse.tx - mouse.px) / sdt * .5 + mouse.vx * .5;
            mouse.vy = mouse.py > 8 || mouse.tx > 8 ? 0 : (mouse.ty - mouse.py) / sdt * .5 + mouse.vy * .5;
            mouse.px = mouse.tx; mouse.py = mouse.ty;
            if (!hidden && !document.hidden) { step((now - t0) / 1000, sdt); draw((now - t0) / 1000); }
            requestAnimationFrame(frame);
        })(last);
        setTimeout(function () { hidden = covered(); cv.style.display = hidden ? 'none' : ''; }, 1500);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
