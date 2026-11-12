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
        'uniform vec2 uRes;uniform float uTime;uniform vec2 uMouse;uniform float uScroll;uniform float uLight;uniform vec3 uPal[6];',
        'varying vec2 vUv;',
        'vec3 gcol;',
        'float smin(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}',
        'vec3 pal(int i){ for(int k=0;k<6;k++){ if(k==i) return uPal[k]; } return uPal[0]; }',
        'float map(vec3 p){',
        '  float asp=uRes.x/uRes.y; float sz=clamp(asp*.75,.45,1.); float d=1e3; vec3 acc=vec3(0.); float ws=0.;',
        '  for(int i=0;i<6;i++){',
        '    float fi=float(i);',
        '    vec3 c=vec3(sin(uTime*(.07+.018*fi)+fi*2.1)*asp*.92, cos(uTime*(.06+.015*fi)+fi*1.3)*.86, -.2+.25*sin(fi));',
        '    c.y+=sin(uScroll*.6+fi)*.12;',
        '    vec2 dm=c.xy-uMouse; float pull=exp(-dot(dm,dm)*4.);',
        '    c.xy+=normalize(dm+1e-4)*.28*pull;',
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

        var mouse = { x: 9, y: 9, tx: 9, ty: 9 };
        addEventListener('pointermove', function (e) {
            mouse.tx = (e.clientX / innerWidth * 2 - 1) * (innerWidth / innerHeight);
            mouse.ty = -(e.clientY / innerHeight * 2 - 1);
        }, { passive: true });
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
            gl.uniform2f(U.uMouse, mouse.x, mouse.y);
            gl.uniform1f(U.uScroll, scrollY / innerHeight);
            gl.uniform1f(U.uLight, root.getAttribute('data-theme') === 'light' ? 0.8 : 1);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
        }
        if (reduced) { draw(12); addEventListener('resize', function () { draw(12); }); return; }
        (function frame(now) {
            var dt = now - last; last = now;
            if (++frames % 90 === 0) {
                if (slow > 45 && scale > 0.3) scale = 0.32;
                slow = 0; hidden = covered(); cv.style.display = hidden ? 'none' : '';
            }
            if (dt > 28) slow++;
            mouse.x += (mouse.tx - mouse.x) * 0.06; mouse.y += (mouse.ty - mouse.y) * 0.06;
            if (!hidden && !document.hidden) draw((now - t0) / 1000);
            requestAnimationFrame(frame);
        })(last);
        setTimeout(function () { hidden = covered(); cv.style.display = hidden ? 'none' : ''; }, 1500);
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
