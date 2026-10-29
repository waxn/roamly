/* Trip blog page — header, map, feed, comments, reactions; plus the composer and
   the manage drawer on the member page. Config comes from #tb-cfg (json_script).
   No framework: small render functions over innerHTML with everything escaped. */
(function () {
    'use strict';
    const CFG = JSON.parse(document.getElementById('tb-cfg').textContent);
    const MEMBER = CFG.mode === 'member';
    const API = CFG.api;
    const $ = (id) => document.getElementById(id);
    const PALETTE = ['#e8763d', '#7ba3b8', '#7f9b6d', '#cf9a44', '#a888a0', '#d0694a', '#6b7fa3', '#4f8a7b'];
    const CHUNK = 8 * 1024 * 1024;

    // ── utilities ───────────────────────────────────────────────────────────
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g,
        (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
    function linkify(text) {
        return esc(text).replace(/\bhttps?:\/\/[^\s<]+[^\s<.,;:!?)\]'"]/g,
            (u) => `<a href="${u}" target="_blank" rel="noopener nofollow ugc">${u}</a>`);
    }
    const store = {
        get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
        set(k, v) { try { localStorage.setItem(k, v); } catch (_) {} },
    };
    function csrf() {
        if (CFG.csrf) return CFG.csrf;
        const m = document.cookie.match(/(?:^|;\s*)csrftoken=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }
    function headers(json) {
        const h = {};
        if (json) h['Content-Type'] = 'application/json';
        if (MEMBER) h['X-CSRFToken'] = csrf();
        return h;
    }
    async function api(path, opts) {
        opts = opts || {};
        const init = {method: opts.method || 'GET', credentials: 'same-origin', headers: headers(opts.body !== undefined)};
        if (opts.body !== undefined) init.body = JSON.stringify(opts.body);
        if (opts.form) { init.body = opts.form; init.method = 'POST'; }
        const r = await fetch((opts.abs ? '' : API) + path, init);
        const d = await r.json().catch(() => ({}));
        if (!r.ok) {
            const e = new Error(d.error || `Request failed (${r.status})`);
            e.status = r.status; e.data = d;
            throw e;
        }
        return d;
    }
    const memberApi = (path, opts) => api(path, Object.assign({abs: true}, opts));
    function toast(msg, type) {
        if (window.roamlyToast) return window.roamlyToast(msg, {type: type || 'success'});
        let el = $('tb-toast');
        if (!el) {
            el = document.createElement('div'); el.id = 'tb-toast';
            el.style.cssText = 'position:fixed;left:50%;bottom:24px;transform:translateX(-50%);z-index:3000;background:var(--bg-card);border:1px solid var(--border-strong);padding:10px 16px;font-size:.9rem;max-width:90vw';
            document.body.appendChild(el);
        }
        el.textContent = msg; el.style.borderColor = type === 'error' ? 'var(--danger)' : 'var(--border-strong)';
        el.hidden = false; clearTimeout(el._t); el._t = setTimeout(() => { el.hidden = true; }, 3500);
    }
    async function confirmIt(msg, danger) {
        if (window.roamlyConfirm) return window.roamlyConfirm(msg, {danger: !!danger});
        return true;
    }
    function ago(iso) {
        const s = (Date.now() - new Date(iso).getTime()) / 1000;
        if (s < 90) return 'just now';
        if (s < 3600) return `${Math.round(s / 60)} min ago`;
        if (s < 86400) return `${Math.round(s / 3600)} h ago`;
        const d = Math.round(s / 86400);
        return d === 1 ? 'yesterday' : `${d} days ago`;
    }
    function dayLabel(iso) {
        const [y, m, d] = iso.split('-').map(Number);
        return new Date(y, m - 1, d).toLocaleDateString(undefined, {weekday: 'short', month: 'short', day: 'numeric'});
    }
    function wallTime(local) {
        // "2026-03-04T10:30" is the clock where it happened — format without shifting.
        const [dp, tp] = local.split('T');
        const [y, m, d] = dp.split('-').map(Number);
        const [hh, mm] = tp.split(':').map(Number);
        return new Date(y, m - 1, d, hh, mm).toLocaleTimeString(undefined, {hour: 'numeric', minute: '2-digit'});
    }
    const colorFor = (id) => PALETTE[Math.abs(Number(id) || 0) % PALETTE.length];
    const initial = (name) => (name || '?').trim().charAt(0).toUpperCase();
    function avatar(a) {
        if (a.avatar) return `<span class="tb-av"><img src="${esc(a.avatar)}" alt=""></span>`;
        return `<span class="tb-av" style="background:${colorFor(a.id)}">${esc(initial(a.name))}</span>`;
    }
    const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2));
    const ICON_PIN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>';

    // ── state ───────────────────────────────────────────────────────────────
    let status = null;
    let nextBefore = null;
    let loadingFeed = false;
    const posts = new Map();        // id → payload
    let guestName = store.get('roamly_trip_name') || CFG.guest_name || '';

    // ── header ──────────────────────────────────────────────────────────────
    function renderHeader() {
        const s = status, b = s.blog;
        const now = $('tb-now'), who = $('tb-who');
        if (b.cover) $('tb-cover').style.backgroundImage = `url('${b.cover}')`;
        $('tb-name').textContent = b.name;
        let label, big;
        if (s.stats.upcoming) {
            label = 'Starts ' + dayLabel(b.start_date); big = b.name;
        } else if (!b.live) {
            label = `Trip ended ${dayLabel(b.end_date)}`; big = b.name;
        } else if (s.home) {
            label = 'Staying in'; big = s.home.city + (s.home.country ? `, ${s.home.country}` : '');
        } else if (s.people.length) {
            const fresh = s.people.filter((p) => !p.stale)[0] || s.people[0];
            label = 'Now in'; big = fresh.city || b.name;
        } else {
            label = dayLabel(b.start_date) + (b.end_date ? ' – ' + dayLabel(b.end_date) : ''); big = b.name;
        }
        now.innerHTML = `<span class="tb-now-label">${esc(label)}</span>${esc(big)}`;
        const lines = [];
        if (s.together) lines.push(`<span class="tb-dot"></span>${esc(s.together)}`);
        const stale = s.people.filter((p) => p.stale);
        stale.forEach((p) => lines.push(`${esc(p.name)} · last seen ${esc(p.city || 'somewhere')} ${esc(ago(p.seen))}`));
        who.innerHTML = lines.join('<br>');
        const st = s.stats;
        const tiles = [];
        if (!st.upcoming) tiles.push([st.day, b.live ? 'Day' : 'Days']);
        tiles.push([st.countries, st.countries === 1 ? 'Country' : 'Countries']);
        tiles.push([st.cities, st.cities === 1 ? 'Place' : 'Places']);
        tiles.push([st.posts, st.posts === 1 ? 'Post' : 'Posts']);
        $('tb-stats').innerHTML = tiles.map(([v, l]) => `<div class="tb-stat"><b>${esc(v)}</b><span>${esc(l)}</span></div>`).join('');
        document.title = `${b.name} — Roamly`;
    }

    // ── map ─────────────────────────────────────────────────────────────────
    let map = null, mapReady = false, personMarkers = [], didFit = false;
    const light = document.documentElement.dataset.theme === 'light';
    function baseStyle() {
        const tile = light ? 'light_all' : 'dark_all';
        return {
            version: 8,
            sources: {base: {type: 'raster', tileSize: 256, maxzoom: 19,
                tiles: [`https://basemaps.cartocdn.com/${tile}/{z}/{x}/{y}.png${CFG.carto_qs || ''}`],
                attribution: '© OpenStreetMap © CARTO'}},
            layers: [{id: 'base', type: 'raster', source: 'base'}],
        };
    }
    function circlePoly(lat, lon, r) {
        const pts = [], n = 48;
        const dLat = r / 111320, dLon = r / (111320 * Math.max(0.05, Math.cos(lat * Math.PI / 180)));
        for (let i = 0; i <= n; i++) {
            const a = (i / n) * 2 * Math.PI;
            pts.push([lon + dLon * Math.sin(a), lat + dLat * Math.cos(a)]);
        }
        return pts;
    }
    function initMap() {
        if (!window.maplibregl || map) return;
        map = new maplibregl.Map({container: 'tb-map', style: baseStyle(), center: [10, 25], zoom: 1.2,
            attributionControl: {compact: true}, dragRotate: false, cooperativeGestures: window.innerWidth < 960});
        map.touchZoomRotate.disableRotation();
        map.addControl(new maplibregl.NavigationControl({showCompass: false}), 'bottom-right');
        map.on('load', async () => {
            mapReady = true;
            try {
                const world = await fetch(CFG.countries_url).then((r) => r.json());
                map.addSource('countries', {type: 'geojson', data: world});
                map.addLayer({id: 'unvisited', type: 'fill', source: 'countries',
                    paint: {'fill-color': light ? '#8b8b86' : '#5c5c57', 'fill-opacity': light ? 0.45 : 0.6},
                    filter: ['literal', false]});
            } catch (_) {}
            map.addSource('spots', {type: 'geojson', data: {type: 'FeatureCollection', features: []}});
            map.addLayer({id: 'spots-fill', type: 'fill', source: 'spots', paint: {'fill-color': ['get', 'c'], 'fill-opacity': 0.18}});
            map.addLayer({id: 'spots-line', type: 'line', source: 'spots', paint: {'line-color': ['get', 'c'], 'line-width': 1.5, 'line-opacity': 0.7}});
            map.addSource('pins', {type: 'geojson', data: {type: 'FeatureCollection', features: []},
                cluster: true, clusterRadius: 34, clusterMaxZoom: 13});
            map.addLayer({id: 'pin-clusters', type: 'circle', source: 'pins', filter: ['has', 'point_count'],
                paint: {'circle-color': '#e8763d', 'circle-opacity': 0.85, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2,
                    'circle-radius': ['step', ['get', 'point_count'], 9, 5, 12, 20, 16, 60, 20]}});
            map.addLayer({id: 'pins', type: 'circle', source: 'pins', filter: ['!', ['has', 'point_count']],
                paint: {'circle-color': '#e8763d', 'circle-radius': 6, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2}});
            map.addSource('hl', {type: 'geojson', data: {type: 'FeatureCollection', features: []}});
            map.addLayer({id: 'hl', type: 'circle', source: 'hl',
                paint: {'circle-color': 'rgba(0,0,0,0)', 'circle-radius': 13, 'circle-stroke-color': '#e8763d', 'circle-stroke-width': 3}});
            map.on('click', 'pins', (e) => { const id = e.features[0].properties.id; jumpToPost(id); });
            map.on('click', 'pin-clusters', (e) => {
                const f = e.features[0];
                map.getSource('pins').getClusterExpansionZoom(f.properties.cluster_id).then((z) => {
                    map.easeTo({center: f.geometry.coordinates, zoom: z + 0.5});
                }).catch(() => {});
            });
            ['pins', 'pin-clusters'].forEach((l) => {
                map.on('mouseenter', l, () => { map.getCanvas().style.cursor = 'pointer'; });
                map.on('mouseleave', l, () => { map.getCanvas().style.cursor = ''; });
            });
            if (status) paintMap();
        });
    }
    function paintMap() {
        if (!map || !mapReady || !status) return;
        const s = status;
        if (map.getLayer('unvisited')) {
            map.setFilter('unvisited', s.countries.length
                ? ['!', ['in', ['get', 'cc'], ['literal', s.countries]]] : ['literal', true]);
        }
        map.getSource('pins').setData({type: 'FeatureCollection', features: s.pins.map((p) => ({
            type: 'Feature', geometry: {type: 'Point', coordinates: [p.lon, p.lat]}, properties: {id: p.id}}))});
        map.getSource('spots').setData({type: 'FeatureCollection', features: s.people.map((p) => ({
            type: 'Feature', properties: {c: colorFor(p.id)},
            geometry: {type: 'Polygon', coordinates: [circlePoly(p.spot.lat, p.spot.lon, p.spot.radius_m)]}}))});
        personMarkers.forEach((m) => m.remove());
        personMarkers = s.people.map((p) => {
            const el = document.createElement('div');
            el.className = 'tb-person' + (p.stale ? ' stale' : '');
            el.innerHTML = `<span class="ini" style="background:${colorFor(p.id)}">${esc(initial(p.name))}</span><span class="nm">${esc(p.name)}</span>`;
            const pop = new maplibregl.Popup({offset: 18, closeButton: false})
                .setHTML(`<b>${esc(p.name)}</b><br>${esc(p.city || '')}${p.country ? ', ' + esc(p.country) : ''}<br><span class="dim">${esc(ago(p.seen))} · within ~${(p.spot.radius_m / 1000).toFixed(1)} km</span>`);
            return new maplibregl.Marker({element: el, anchor: 'center'}).setLngLat([p.spot.lon, p.spot.lat]).setPopup(pop).addTo(map);
        });
        if (!didFit) { fitAll(); didFit = true; }
    }
    function fitAll() {
        if (!map || !status) return;
        const pts = status.pins.map((p) => [p.lon, p.lat]).concat(status.people.map((p) => [p.spot.lon, p.spot.lat]));
        if (!pts.length) return;
        if (pts.length === 1) { map.jumpTo({center: pts[0], zoom: 10}); return; }
        const b = new maplibregl.LngLatBounds(pts[0], pts[0]);
        pts.forEach((p) => b.extend(p));
        map.fitBounds(b, {padding: 50, maxZoom: 12, duration: 0});
    }
    function highlightPin(p) {
        if (!map || !mapReady) return;
        map.getSource('hl').setData({type: 'FeatureCollection', features: p ? [{type: 'Feature',
            geometry: {type: 'Point', coordinates: [p.lon, p.lat]}, properties: {}}] : []});
    }
    function flyTo(place) {
        if (!map || !place) return;
        if (window.innerWidth < 960) $('tb-mapbox').scrollIntoView({behavior: 'smooth', block: 'center'});
        map.flyTo({center: [place.lon, place.lat], zoom: Math.max(map.getZoom(), 12)});
        highlightPin(place);
    }

    // ── feed ────────────────────────────────────────────────────────────────
    function mediaHtml(p) {
        const m = p.media;
        if (!m.length) return '';
        const shown = m.slice(0, 4);
        const n = m.length === 1 ? 1 : m.length === 2 ? 2 : m.length === 3 ? 3 : 4;
        return `<div class="tb-media n${n}">` + shown.map((x, i) => {
            if (x.type === 'video') {
                if (!x.url) return `<div class="tb-mi tb-processing"><span>Video is processing…</span><span class="dim mono">it will appear here shortly</span></div>`;
                return `<div class="tb-mi" style="cursor:default"><video controls playsinline preload="none" ${x.poster ? `poster="${esc(x.poster)}"` : ''} src="${esc(x.url)}"></video></div>`;
            }
            const more = (i === 3 && m.length > 4) ? `<span class="more">+${m.length - 4}</span>` : '';
            return `<button type="button" class="tb-mi" data-lb="${p.id}" data-i="${i}" aria-label="Open photo"><img loading="lazy" src="${esc(n === 1 ? x.url : x.thumb)}" alt="">${more}</button>`;
        }).join('') + '</div>';
    }
    function reactionsHtml(p) {
        return CFG.emoji.map((e) => {
            const n = (p.reactions || {})[e] || 0;
            const on = (p.my_reactions || []).includes(e);
            return `<button type="button" class="tb-react${on ? ' on' : ''}${n ? '' : ' zero'}" data-react="${p.id}" data-e="${esc(e)}" aria-pressed="${on}">${e}<b>${n}</b></button>`;
        }).join('');
    }
    function commentHtml(c) {
        return `<div class="tb-c" data-cid="${c.id}"><b class="${c.traveller ? 'trav' : ''}">${esc(c.name)}</b><span class="t">${esc(c.text)}</span>
            <div class="m">${esc(ago(c.at))}${c.can_delete ? `<button type="button" class="del" data-cdel="${c.id}">delete</button>` : ''}</div></div>`;
    }
    function commentsHtml(p) {
        const more = p.comment_count > p.comments.length
            ? `<button type="button" class="tb-cmore" data-call="${p.id}">View all ${p.comment_count} comments</button>` : '';
        const nameField = (!MEMBER && !guestName)
            ? `<input class="tb-in" name="name" placeholder="Your name" maxlength="60" style="flex:0 0 32%" required>` : '';
        return `<div class="tb-comments" id="tb-cm-${p.id}">${more}<div class="tb-clist">${p.comments.map(commentHtml).join('')}</div>
            <form class="tb-cform" data-cform="${p.id}" autocomplete="off">
                <input class="tb-hp" name="website" tabindex="-1" autocomplete="off" aria-hidden="true">
                ${nameField}<input class="tb-in" name="text" placeholder="Write a comment…" maxlength="2000" required>
                <button class="tb-btn" type="submit">Send</button></form></div>`;
    }
    function postHtml(p) {
        const place = p.place ? `<button type="button" class="tb-place" data-fly="${p.id}">${ICON_PIN}${esc(p.place.city || p.place.name || 'On the map')}${p.place.country ? ', ' + esc(p.place.country) : ''}</button>` : '';
        const menu = p.mine ? `<div class="tb-menu"><button type="button" class="tb-btn icon sm" data-pmenu="${p.id}" aria-label="Post options">⋯</button></div>` : '';
        return `<article class="tb-post" id="post-${p.id}" data-pid="${p.id}">
            <div class="tb-post-h">${avatar(p.author)}<div class="tb-post-who"><b>${esc(p.author.name)}</b>
                <div>${esc(wallTime(p.local_time))}${p.edited ? ' · edited' : ''}</div></div>${place}${menu}</div>
            ${p.text ? `<div class="tb-post-text">${linkify(p.text)}</div>` : ''}
            ${mediaHtml(p)}
            <div class="tb-post-f">${reactionsHtml(p)}</div>
            ${commentsHtml(p)}
        </article>`;
    }
    function dayHtml(d) {
        return `<section class="tb-day" data-date="${d.date}"><div class="tb-day-h"><b>Day ${esc(d.day)}</b>
            <span>${esc(dayLabel(d.date))}${d.city ? ' · ' + esc(d.city) : ''}</span>
            <em>${d.posts.length} post${d.posts.length === 1 ? '' : 's'}</em></div>
            ${d.posts.map(postHtml).join('')}</section>`;
    }
    async function loadFeed(reset) {
        if (loadingFeed) return;
        loadingFeed = true;
        const feed = $('tb-feed');
        try {
            const q = (!reset && nextBefore) ? `?before=${nextBefore}` : '';
            const d = await api('/feed/' + q);
            if (reset) { feed.innerHTML = ''; posts.clear(); }
            d.days.forEach((day) => {
                day.posts.forEach((p) => posts.set(p.id, p));
                const existing = feed.querySelector(`.tb-day[data-date="${day.date}"]`);
                const html = dayHtml(day);
                if (existing) existing.outerHTML = html; else feed.insertAdjacentHTML('beforeend', html);
            });
            nextBefore = d.next_before;
            $('tb-more').hidden = !nextBefore;
            if (!feed.querySelector('.tb-day')) {
                feed.innerHTML = MEMBER
                    ? `<div class="tb-empty"><h3>No posts yet</h3>Write the first one above — add photos, a video, and where you are.</div>`
                    : `<div class="tb-empty"><h3>No posts yet</h3>Check back soon — this page fills in as the trip happens.</div>`;
            }
        } catch (e) {
            if (reset) feed.innerHTML = `<div class="tb-empty">${esc(e.message)}</div>`;
        } finally { loadingFeed = false; }
    }
    async function jumpToPost(id) {
        id = Number(id);
        for (let i = 0; i < 40 && !$('post-' + id); i++) {
            if (!nextBefore) break;
            await loadFeed(false);
        }
        const el = $('post-' + id);
        if (!el) return;
        el.scrollIntoView({behavior: 'smooth', block: 'start'});
        el.classList.add('hl'); setTimeout(() => el.classList.remove('hl'), 2200);
        const p = posts.get(id);
        if (p && p.place) highlightPin(p.place);
    }
    function refreshPost(p) {
        posts.set(p.id, p);
        const el = $('post-' + p.id);
        if (el) el.outerHTML = postHtml(p);
    }

    // ── lightbox ────────────────────────────────────────────────────────────
    function lightbox(pid, idx) {
        const imgs = posts.get(Number(pid)).media.filter((m) => m.type === 'image');
        const all = posts.get(Number(pid)).media;
        let i = imgs.indexOf(all[idx]); if (i < 0) i = 0;
        const box = document.createElement('div');
        box.className = 'tb-lb'; box.setAttribute('role', 'dialog');
        box.innerHTML = `<img alt=""><button class="c" aria-label="Close">×</button>${imgs.length > 1 ? '<button class="p" aria-label="Previous">‹</button><button class="n" aria-label="Next">›</button>' : ''}<span class="k"></span>`;
        const img = box.querySelector('img');
        const show = () => { img.src = imgs[i].url; box.querySelector('.k').textContent = imgs.length > 1 ? `${i + 1} / ${imgs.length}` : ''; };
        const close = () => { box.remove(); document.removeEventListener('keydown', key); };
        const step = (d) => { i = (i + d + imgs.length) % imgs.length; show(); };
        const key = (e) => { if (e.key === 'Escape') close(); else if (e.key === 'ArrowLeft') step(-1); else if (e.key === 'ArrowRight') step(1); };
        box.addEventListener('click', (e) => {
            if (e.target.classList.contains('p')) step(-1);
            else if (e.target.classList.contains('n')) step(1);
            else if (e.target === box || e.target.classList.contains('c')) close();
        });
        let sx = null;
        box.addEventListener('touchstart', (e) => { sx = e.touches[0].clientX; }, {passive: true});
        box.addEventListener('touchend', (e) => { if (sx == null) return; const dx = e.changedTouches[0].clientX - sx; if (Math.abs(dx) > 50) step(dx < 0 ? 1 : -1); sx = null; });
        document.addEventListener('keydown', key);
        document.body.appendChild(box); show();
    }

    // ── feed interactions ───────────────────────────────────────────────────
    $('tb-feed').addEventListener('click', async (e) => {
        const t = e.target.closest('button');
        if (!t) return;
        if (t.dataset.lb) return lightbox(t.dataset.lb, Number(t.dataset.i));
        if (t.dataset.fly) { const p = posts.get(Number(t.dataset.fly)); return flyTo(p && p.place); }
        if (t.dataset.react) {
            try {
                const d = await api('/react/', {method: 'POST', body: {post: Number(t.dataset.react), emoji: t.dataset.e}});
                const p = posts.get(Number(t.dataset.react));
                p.reactions = d.reactions;
                p.my_reactions = d.on ? [...new Set([...(p.my_reactions || []), t.dataset.e])] : (p.my_reactions || []).filter((x) => x !== t.dataset.e);
                t.parentElement.innerHTML = reactionsHtml(p);
            } catch (err) { toast(err.message, 'error'); }
            return;
        }
        if (t.dataset.call) {
            const pid = Number(t.dataset.call);
            try {
                const d = await api(`/comments/${pid}/`);
                const p = posts.get(pid);
                p.comments = d.comments; p.comment_count = d.comments.length;
                $('tb-cm-' + pid).outerHTML = commentsHtml(p);
            } catch (err) { toast(err.message, 'error'); }
            return;
        }
        if (t.dataset.cdel) {
            if (!t.dataset.sure && !MEMBER) { t.dataset.sure = '1'; t.textContent = 'delete — sure?'; return; }
            if (MEMBER && !(await confirmIt('Delete this comment?', true))) return;
            try {
                await api(`/comment/${t.dataset.cdel}/delete/`, {method: 'POST', body: {}});
                t.closest('.tb-c').remove();
            } catch (err) { toast(err.message, 'error'); }
            return;
        }
        if (t.dataset.pmenu && MEMBER) return postMenu(t, Number(t.dataset.pmenu));
    });
    $('tb-feed').addEventListener('submit', async (e) => {
        const f = e.target.closest('form[data-cform]');
        if (!f) return;
        e.preventDefault();
        const pid = Number(f.dataset.cform);
        const body = {post: pid, text: f.text.value.trim(), website: f.website.value};
        if (!body.text) return;
        if (!MEMBER) {
            const n = (f.name && f.name.value.trim()) || guestName;
            if (!n) { f.name && f.name.focus(); return; }
            body.name = n;
        }
        const btn = f.querySelector('button[type=submit]'); btn.disabled = true;
        try {
            const d = await api('/comment/', {method: 'POST', body});
            if (!MEMBER && body.name !== guestName) { guestName = body.name; store.set('roamly_trip_name', guestName); }
            const p = posts.get(pid);
            p.comments.push(d.comment); p.comment_count += 1;
            $('tb-cm-' + pid).outerHTML = commentsHtml(p);
        } catch (err) { toast(err.message, 'error'); btn.disabled = false; }
    });
    $('tb-feed').addEventListener('mouseover', (e) => {
        const el = e.target.closest('.tb-post');
        if (!el || el._hov) return;
        document.querySelectorAll('.tb-post').forEach((x) => { x._hov = false; });
        el._hov = true;
        const p = posts.get(Number(el.dataset.pid));
        highlightPin(p && p.place);
    });
    $('tb-more').addEventListener('click', () => loadFeed(false));
    if ('IntersectionObserver' in window) {
        new IntersectionObserver((ents) => { if (ents[0].isIntersecting && nextBefore) loadFeed(false); },
            {rootMargin: '600px'}).observe($('tb-more'));
    }
    $('tb-map-fit').addEventListener('click', fitAll);
    $('tb-map-expand').addEventListener('click', () => {
        $('tb-mapbox').classList.toggle('tall');
        setTimeout(() => map && map.resize(), 50);
    });

    // ── subscribe (viewer) ──────────────────────────────────────────────────
    const subForm = $('tb-sub-form');
    if (subForm) {
        subForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            const msg = $('tb-sub-msg');
            try {
                const d = await api('/subscribe/', {method: 'POST', body: {email: subForm.email.value, website: subForm.website.value}});
                msg.textContent = d.status === 'already' ? 'You are already subscribed.' : 'Check your inbox to confirm.';
                subForm.reset();
            } catch (err) { msg.textContent = err.message; }
        });
    }

    // ── status polling ──────────────────────────────────────────────────────
    async function loadStatus() {
        try {
            status = await api('/status/');
            renderHeader();
            paintMap();
        } catch (e) {
            if (e.status === 403 && !MEMBER) location.reload();
        }
    }

    // ═══════════════════════ member-only below ═════════════════════════════
    const comp = {draftId: null, clientId: uuid(), items: [], loc: null, time: null, busy: false};

    function memberUrl(path) { return `/api/tripblog/${CFG.blog_id}${path}`; }

    function renderComposerMeta() {
        const meta = $('tb-c-meta'); if (!meta) return;
        const chips = [];
        if (comp.loc) chips.push(`<button type="button" class="tb-chip on" data-cx="loc">${ICON_PIN.replace('<svg', '<svg width="13" height="13"')}${esc(comp.loc.label)}<span class="x">×</span></button>`);
        if (comp.time) {
            // The ISO carries the photo's own offset; its first 16 chars are the wall clock there.
            const wc = comp.time.iso.slice(0, 16);
            chips.push(`<button type="button" class="tb-chip on" data-cx="time">🕒 ${esc(dayLabel(wc.slice(0, 10)))}, ${esc(wallTime(wc))} · ${esc(comp.time.src)}<span class="x">×</span></button>`);
        }
        meta.innerHTML = chips.join('');
        meta.hidden = !chips.length;
        const up = comp.items.filter((x) => x.state === 'uploading').length;
        const btn = $('tb-c-post');
        btn.disabled = comp.busy || up > 0;
        btn.textContent = up ? `Uploading ${up}…` : 'Post';
        $('tb-c-note').textContent = comp.items.some((x) => x.kind === 'video')
            ? 'Videos are converted after posting so they play everywhere — this can take a few minutes.' : '';
    }
    function renderComposerMedia() {
        const box = $('tb-c-media');
        box.innerHTML = comp.items.map((it, i) => `<div class="tb-cm${it.state === 'error' ? ' err' : ''}">
            ${it.kind === 'video' ? `<video src="${it.preview}" muted playsinline></video>` : `<img src="${it.preview}" alt="">`}
            ${it.state === 'uploading' ? `<span class="bar" style="width:${Math.round(it.pct)}%"></span>` : ''}
            ${it.state === 'error' ? `<span class="st">${esc(it.error || 'failed')}</span>` : it.kind === 'video' ? '<span class="st">video</span>' : ''}
            <button type="button" class="rm" data-rm="${i}" aria-label="Remove">×</button></div>`).join('');
        renderComposerMeta();
    }
    async function ensureDraft() {
        if (comp.draftId) return comp.draftId;
        const d = await memberApi(memberUrl('/posts/'), {method: 'POST', body: {client_id: comp.clientId, publish: false}});
        comp.draftId = d.id;
        return d.id;
    }
    function xhrUpload(url, form, onPct) {
        return new Promise((resolve, reject) => {
            const x = new XMLHttpRequest();
            x.open('POST', url);
            x.setRequestHeader('X-CSRFToken', csrf());
            x.upload.onprogress = (e) => { if (e.lengthComputable) onPct(e.loaded / e.total); };
            x.onload = () => {
                let d = {}; try { d = JSON.parse(x.responseText); } catch (_) {}
                if (x.status >= 200 && x.status < 300) resolve(d);
                else { const e = new Error(d.error || `Upload failed (${x.status})`); e.status = x.status; e.data = d; reject(e); }
            };
            x.onerror = () => reject(new Error('Connection problem'));
            x.send(form);
        });
    }
    async function uploadPhoto(it, pid) {
        const fd = new FormData(); fd.append('file', it.file); fd.append('order', String(it.order));
        const d = await xhrUpload(memberUrl(`/posts/${pid}/photo/`), fd, (f) => { it.pct = f * 100; renderComposerMedia(); });
        it.mediaId = d.media.id;
        const ex = d.exif || {};
        if (!comp.loc && ex.lat != null) comp.loc = {lat: ex.lat, lon: ex.lon, label: 'Where the photo was taken', src: 'photo'};
        if (!comp.time && ex.taken_at) comp.time = {iso: ex.taken_at, src: 'from photo'};
    }
    async function uploadVideo(it, pid) {
        const total = Math.max(1, Math.ceil(it.file.size / CHUNK));
        let i = 0, mediaId = null, tries = 0;
        while (i < total) {
            const fd = new FormData();
            fd.append('index', String(i)); fd.append('total', String(total));
            fd.append('name', it.file.name); fd.append('order', String(it.order));
            if (mediaId) fd.append('media_id', String(mediaId));
            fd.append('chunk', it.file.slice(i * CHUNK, Math.min(it.file.size, (i + 1) * CHUNK)), 'chunk');
            try {
                const d = await xhrUpload(memberUrl(`/posts/${pid}/video/`), fd,
                    (f) => { it.pct = ((i + f) / total) * 100; renderComposerMedia(); });
                tries = 0;
                if (d.media_id) mediaId = d.media_id;
                if (d.status === 'complete') { it.mediaId = d.media.id; return; }
                i = d.next_chunk;
            } catch (e) {
                if (e.status === 409 && e.data && e.data.next_chunk != null) { i = e.data.next_chunk; continue; }
                if (e.status && e.status < 500) throw e;
                if (++tries > 4) throw e;
                await new Promise((r) => setTimeout(r, 1500 * tries));
            }
        }
    }
    async function addFiles(files) {
        let pid;
        try { pid = await ensureDraft(); } catch (e) { toast(e.message, 'error'); return; }
        for (const file of files) {
            const kind = (file.type || '').startsWith('video/') || /\.(mov|mp4|m4v|webm)$/i.test(file.name) ? 'video' : 'image';
            const it = {file, kind, preview: URL.createObjectURL(file), state: 'uploading', pct: 0, order: comp.items.length};
            comp.items.push(it);
        }
        renderComposerMedia();
        for (const it of comp.items.filter((x) => x.state === 'uploading' && !x.started)) {
            it.started = true;
            try {
                if (it.kind === 'video') await uploadVideo(it, pid); else await uploadPhoto(it, pid);
                it.state = 'done';
            } catch (e) { it.state = 'error'; it.error = e.message; }
            renderComposerMedia();
        }
    }
    async function useMyLocation(target) {
        try {
            const d = await memberApi(memberUrl('/my-location/'));
            if (!d.location) { toast('No recent location from your phone yet.', 'error'); return; }
            const L = d.location;
            target.loc = {lat: L.lat, lon: L.lon, label: (L.city || 'Current location') + (L.age_s > 3600 ? ` (${ago(L.at)})` : ''), src: 'here'};
        } catch (e) { toast(e.message, 'error'); }
    }
    function pickOnMap(start) {
        return new Promise((resolve) => {
            const ov = document.createElement('div'); ov.className = 'tb-overlay';
            const md = document.createElement('div'); md.className = 'tb-modal';
            md.innerHTML = `<div class="tb-drawer-h"><h2>Pick the place</h2><button class="tb-btn icon" data-x aria-label="Close">×</button></div>
                <div class="tb-modal-b"><p class="muted" style="margin:0 0 10px;font-size:.85rem">Tap the map where this happened.</p>
                <div class="tb-pickmap"><div id="tb-pick-map"></div></div>
                <div class="tb-row" style="margin-top:12px;justify-content:flex-end"><button class="tb-btn" data-x>Cancel</button><button class="tb-btn primary" data-ok disabled>Use this spot</button></div></div>`;
            document.body.append(ov, md);
            const centre = start ? [start.lon, start.lat] : (status && status.pins.length ? [status.pins[status.pins.length - 1].lon, status.pins[status.pins.length - 1].lat] : [10, 30]);
            const pm = new maplibregl.Map({container: 'tb-pick-map', style: baseStyle(), center: centre, zoom: start || (status && status.pins.length) ? 11 : 1.5});
            let marker = null, pick = start ? {lat: start.lat, lon: start.lon} : null;
            if (pick) marker = new maplibregl.Marker({color: '#e8763d'}).setLngLat([pick.lon, pick.lat]).addTo(pm);
            if (pick) md.querySelector('[data-ok]').disabled = false;
            pm.on('click', (e) => {
                pick = {lat: e.lngLat.lat, lon: e.lngLat.lng};
                if (!marker) marker = new maplibregl.Marker({color: '#e8763d'}).addTo(pm);
                marker.setLngLat(e.lngLat);
                md.querySelector('[data-ok]').disabled = false;
            });
            const done = (v) => { pm.remove(); ov.remove(); md.remove(); resolve(v); };
            ov.onclick = () => done(null);
            md.addEventListener('click', (e) => {
                if (e.target.closest('[data-x]')) done(null);
                else if (e.target.closest('[data-ok]')) done(pick);
            });
        });
    }
    function resetComposer() {
        comp.items.forEach((it) => URL.revokeObjectURL(it.preview));
        Object.assign(comp, {draftId: null, clientId: uuid(), items: [], loc: null, time: null, busy: false});
        $('tb-c-text').value = '';
        renderComposerMedia();
    }
    async function submitPost() {
        const text = $('tb-c-text').value.trim();
        const good = comp.items.filter((x) => x.state === 'done');
        if (!text && !good.length) { $('tb-c-text').focus(); return; }
        if (comp.items.some((x) => x.state === 'error') &&
            !(await confirmIt('Some files failed to upload. Post without them?'))) return;
        comp.busy = true; renderComposerMeta();
        const body = {text, publish: true, lat: comp.loc ? comp.loc.lat : null, lon: comp.loc ? comp.loc.lon : null};
        if (comp.time) body.taken_at = comp.time.iso;
        try {
            if (comp.draftId) await memberApi(memberUrl(`/posts/${comp.draftId}/`), {method: 'POST', body});
            else await memberApi(memberUrl('/posts/'), {method: 'POST', body: Object.assign({client_id: comp.clientId}, body)});
            resetComposer();
            toast('Posted');
            nextBefore = null;
            await Promise.all([loadFeed(true), loadStatus()]);
        } catch (e) { toast(e.message, 'error'); comp.busy = false; renderComposerMeta(); }
    }
    function initComposer() {
        if (!$('tb-compose')) return;
        $('tb-c-files').addEventListener('change', (e) => { const f = Array.from(e.target.files || []); e.target.value = ''; if (f.length) addFiles(f); });
        $('tb-c-here').addEventListener('click', async () => { await useMyLocation(comp); renderComposerMeta(); });
        $('tb-c-pick').addEventListener('click', async () => {
            const p = await pickOnMap(comp.loc);
            if (p) { comp.loc = {lat: p.lat, lon: p.lon, label: 'Picked on the map', src: 'map'}; renderComposerMeta(); }
        });
        $('tb-c-post').addEventListener('click', submitPost);
        $('tb-c-meta').addEventListener('click', (e) => {
            const c = e.target.closest('[data-cx]'); if (!c) return;
            comp[c.dataset.cx] = null; renderComposerMeta();
        });
        $('tb-c-media').addEventListener('click', async (e) => {
            const b = e.target.closest('[data-rm]'); if (!b) return;
            const it = comp.items[Number(b.dataset.rm)];
            if (it.state === 'uploading') return;
            if (it.mediaId) { try { await memberApi(memberUrl(`/media/${it.mediaId}/delete/`), {method: 'POST', body: {}}); } catch (_) {} }
            URL.revokeObjectURL(it.preview);
            comp.items.splice(Number(b.dataset.rm), 1);
            renderComposerMedia();
        });
        const ta = $('tb-c-text');
        ta.addEventListener('paste', (e) => {
            const files = Array.from((e.clipboardData && e.clipboardData.files) || []).filter((f) => /^(image|video)\//.test(f.type));
            if (files.length) { e.preventDefault(); addFiles(files); }
        });
        const zone = $('tb-compose');
        zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.style.borderColor = 'var(--primary)'; });
        zone.addEventListener('dragleave', () => { zone.style.borderColor = ''; });
        zone.addEventListener('drop', (e) => {
            e.preventDefault(); zone.style.borderColor = '';
            const files = Array.from(e.dataTransfer.files || []).filter((f) => /^(image|video)\//.test(f.type));
            if (files.length) addFiles(files);
        });
        window.addEventListener('beforeunload', (e) => {
            if (comp.items.some((x) => x.state === 'uploading')) { e.preventDefault(); e.returnValue = ''; }
        });
        renderComposerMedia();
    }

    // post edit / delete
    function postMenu(btn, pid) {
        document.querySelectorAll('.tb-menu-list').forEach((x) => x.remove());
        const list = document.createElement('div'); list.className = 'tb-menu-list';
        list.innerHTML = '<button data-a="edit">Edit</button><button data-a="del" style="color:var(--danger)">Delete post</button>';
        btn.parentElement.appendChild(list);
        const off = (e) => { if (!list.contains(e.target)) { list.remove(); document.removeEventListener('click', off, true); } };
        setTimeout(() => document.addEventListener('click', off, true));
        list.addEventListener('click', async (e) => {
            const a = e.target.dataset.a; list.remove();
            if (a === 'del') {
                if (!(await confirmIt('Delete this post and its photos?', true))) return;
                try { await memberApi(memberUrl(`/posts/${pid}/delete/`), {method: 'POST', body: {}}); $('post-' + pid).remove(); loadStatus(); }
                catch (err) { toast(err.message, 'error'); }
            } else if (a === 'edit') editPost(pid);
        });
    }
    function editPost(pid) {
        const p = posts.get(pid);
        const st = {loc: p.place ? {lat: p.place.lat, lon: p.place.lon, label: p.place.city || 'On the map'} : null};
        const ov = document.createElement('div'); ov.className = 'tb-overlay';
        const md = document.createElement('div'); md.className = 'tb-modal';
        const render = () => {
            md.querySelector('[data-loc]').innerHTML = st.loc
                ? `<button type="button" class="tb-chip on" data-clear>${esc(st.loc.label)}<span class="x">×</span></button>` : '<span class="dim" style="font-size:.85rem">No location</span>';
        };
        md.innerHTML = `<div class="tb-drawer-h"><h2>Edit post</h2><button class="tb-btn icon" data-x aria-label="Close">×</button></div>
            <div class="tb-modal-b"><textarea class="tb-ta" rows="6" maxlength="10000">${esc(p.text)}</textarea>
            <div class="tb-row" style="margin-top:10px"><span data-loc class="grow"></span>
              <button class="tb-btn icon" data-here title="Use my current location">⌖</button>
              <button class="tb-btn icon" data-pick title="Pick on map">${ICON_PIN}</button></div>
            <div class="tb-row" style="margin-top:14px;justify-content:flex-end"><button class="tb-btn" data-x>Cancel</button><button class="tb-btn primary" data-save>Save</button></div></div>`;
        document.body.append(ov, md);
        render();
        const close = () => { ov.remove(); md.remove(); };
        ov.onclick = close;
        md.addEventListener('click', async (e) => {
            if (e.target.closest('[data-x]')) return close();
            if (e.target.closest('[data-clear]')) { st.loc = null; return render(); }
            if (e.target.closest('[data-here]')) { await useMyLocation(st); return render(); }
            if (e.target.closest('[data-pick]')) { const r = await pickOnMap(st.loc); if (r) { st.loc = {lat: r.lat, lon: r.lon, label: 'Picked on the map'}; render(); } return; }
            if (e.target.closest('[data-save]')) {
                try {
                    const d = await memberApi(memberUrl(`/posts/${pid}/`), {method: 'POST', body: {
                        text: md.querySelector('textarea').value, lat: st.loc ? st.loc.lat : null, lon: st.loc ? st.loc.lon : null}});
                    close(); refreshPost(d); loadStatus();
                } catch (err) { toast(err.message, 'error'); }
            }
        });
    }

    // ── manage drawer ───────────────────────────────────────────────────────
    let drawerTab = 'share';
    function openDrawer(tab) {
        drawerTab = tab || drawerTab;
        const ov = document.createElement('div'); ov.className = 'tb-overlay';
        const dr = document.createElement('aside'); dr.className = 'tb-drawer'; dr.setAttribute('aria-label', 'Share and manage');
        dr.innerHTML = `<div class="tb-drawer-h"><h2>Share &amp; manage</h2><button class="tb-btn icon" data-close aria-label="Close">×</button></div>
            <div class="tb-tabs">${[['share', 'Share links'], ['viewers', 'Who viewed'], ['people', 'Travellers'], ['trip', 'Trip']]
                .map(([k, l]) => `<button data-tab="${k}">${l}</button>`).join('')}</div><div class="tb-drawer-b" id="tb-dr-b"></div>`;
        document.body.append(ov, dr);
        const close = () => { ov.remove(); dr.remove(); document.removeEventListener('keydown', esck); };
        const esck = (e) => { if (e.key === 'Escape' && !document.querySelector('.tb-modal')) close(); };
        document.addEventListener('keydown', esck);
        ov.onclick = close;
        dr.addEventListener('click', (e) => {
            if (e.target.closest('[data-close]')) close();
            const t = e.target.closest('[data-tab]');
            if (t) { drawerTab = t.dataset.tab; showTab(); }
        });
        showTab();
    }
    function showTab() {
        document.querySelectorAll('.tb-tabs button').forEach((b) => b.classList.toggle('on', b.dataset.tab === drawerTab));
        const body = $('tb-dr-b'); body.innerHTML = '<div class="tb-loading">Loading…</div>';
        ({share: tabShare, viewers: tabViewers, people: tabPeople, trip: tabTrip})[drawerTab](body);
    }
    async function copy(text) {
        try { await navigator.clipboard.writeText(text); toast('Link copied'); }
        catch (_) { window.prompt('Copy this link', text); }
    }
    function linkCard(l) {
        const bits = [];
        bits.push(l.max_uses ? `${l.use_count}/${l.max_uses} uses` : `${l.use_count} ${l.use_count === 1 ? 'person' : 'people'}`);
        bits.push(l.expires_at ? `expires ${new Date(l.expires_at).toLocaleDateString()}` : 'never expires');
        if (l.has_pin) bits.push('PIN');
        if (l.revoked) bits.push('revoked'); else if (!l.active) bits.push('expired');
        return `<div class="tb-card${l.active ? '' : ' off'}" data-link="${l.id}"><div class="tb-row"><div class="grow"><div class="tb-card-t">${esc(l.label || 'Share link')}</div>
            <div class="tb-card-m">${esc(bits.join(' · '))}</div></div>
            ${l.active ? `<button class="tb-btn sm" data-copy="${esc(l.url)}">Copy</button>` : ''}
            ${navigator.share && l.active ? `<button class="tb-btn sm" data-share="${esc(l.url)}">Share</button>` : ''}</div>
            ${l.active ? `<div class="tb-url">${esc(l.url)}</div>` : ''}
            <div class="tb-row">${l.revoked ? `<button class="tb-btn sm" data-restore="${l.id}">Restore</button>` : `<button class="tb-btn sm danger" data-revoke="${l.id}">Revoke</button>`}
            <button class="tb-btn sm danger" data-ldel="${l.id}">Delete</button></div></div>`;
    }
    async function tabShare(body) {
        let links = [];
        try { links = (await memberApi(memberUrl('/links/'))).links; } catch (e) { body.innerHTML = esc(e.message); return; }
        body.innerHTML = `<div class="tb-sec"><h3>New link</h3>
            <div class="tb-field"><label>Who is it for?</label><input class="tb-in" id="nl-label" placeholder="e.g. Grandparents, Book club" maxlength="120"></div>
            <div class="tb-row"><div class="tb-field grow"><label>How many people</label><select class="tb-sel rly-native" id="nl-uses"><option value="1">1 person</option><option value="5">5 people</option><option value="25">25 people</option><option value="" selected>Anyone with the link</option></select></div>
            <div class="tb-field grow"><label>Works for</label><select class="tb-sel rly-native" id="nl-exp"><option value="1">1 day</option><option value="7">1 week</option><option value="30">1 month</option><option value="" selected>Forever</option></select></div></div>
            <div class="tb-field"><label>PIN (optional)</label><input class="tb-in" id="nl-pin" inputmode="numeric" pattern="\\d{4}" maxlength="4" placeholder="4 digits" style="max-width:140px">
            <div class="hint">"People" means devices: each phone or computer that opens the link uses one. They keep access after that.</div></div>
            <button class="tb-btn primary" id="nl-go">Create link</button></div>
            <div class="tb-sec"><h3>Links</h3>${links.length ? links.map(linkCard).join('') : '<p class="muted" style="font-size:.88rem">No links yet. Create one above and send it to family and friends.</p>'}</div>`;
        $('nl-go').onclick = async () => {
            try {
                await memberApi(memberUrl('/links/'), {method: 'POST', body: {label: $('nl-label').value, max_uses: $('nl-uses').value || null,
                    expires_in_days: $('nl-exp').value || null, pin: $('nl-pin').value}});
                tabShare(body);
            } catch (e) { toast(e.message, 'error'); }
        };
        body.onclick = async (e) => {
            const b = e.target.closest('button'); if (!b) return;
            try {
                if (b.dataset.copy) return copy(b.dataset.copy);
                if (b.dataset.share) return navigator.share({title: status ? status.blog.name : 'Our trip', url: b.dataset.share}).catch(() => {});
                if (b.dataset.revoke) { if (!(await confirmIt('Revoke this link? Everyone using it loses access.', true))) return; await memberApi(memberUrl(`/links/${b.dataset.revoke}/`), {method: 'POST', body: {revoke: true}}); }
                if (b.dataset.restore) await memberApi(memberUrl(`/links/${b.dataset.restore}/`), {method: 'POST', body: {restore: true}});
                if (b.dataset.ldel) { if (!(await confirmIt('Delete this link? Its viewer list and digest subscribers go with it.', true))) return; await memberApi(memberUrl(`/links/${b.dataset.ldel}/delete/`), {method: 'POST', body: {}}); }
                if (b.dataset.revoke || b.dataset.restore || b.dataset.ldel) tabShare(body);
            } catch (err) { toast(err.message, 'error'); }
        };
    }
    async function tabViewers(body) {
        let data;
        try { data = await memberApi(memberUrl('/views/')); } catch (e) { body.innerHTML = esc(e.message); return; }
        if (!data.links.length) { body.innerHTML = '<p class="muted">No share links yet.</p>'; return; }
        body.innerHTML = data.links.map((g) => `<div class="tb-sec"><h3>${esc(g.link.label || 'Share link')} · ${g.viewers.length}</h3>
            ${g.viewers.length ? g.viewers.map((v) => `<div class="tb-viewer"><div><b style="font-weight:${v.named ? 600 : 400}">${esc(v.name)}</b>
                <div class="tb-card-m">first ${esc(new Date(v.first_at).toLocaleDateString())} · last ${esc(ago(v.last_at))} · ${v.visits} visit${v.visits === 1 ? '' : 's'}</div></div>
                <button class="tb-btn sm danger" data-vrm="${v.id}" title="Remove this device's access">Remove</button></div>`).join('')
                : '<p class="muted" style="font-size:.85rem">Nobody has opened it yet.</p>'}</div>`).join('')
            + '<p class="dim" style="font-size:.78rem">Names appear once someone comments. No IP addresses are stored.</p>';
        body.onclick = async (e) => {
            const b = e.target.closest('[data-vrm]'); if (!b) return;
            if (!(await confirmIt('Remove this device? It would need the link (and a free use) to get back in.', true))) return;
            try { await memberApi(memberUrl(`/views/${b.dataset.vrm}/remove/`), {method: 'POST', body: {}}); tabViewers(body); }
            catch (err) { toast(err.message, 'error'); }
        };
    }
    async function tabPeople(body) {
        let b;
        try { b = await memberApi(memberUrl('/')); } catch (e) { body.innerHTML = esc(e.message); return; }
        body.innerHTML = `<div class="tb-sec"><h3>You</h3>
            <label class="tb-switch"><input type="checkbox" id="me-share" ${b.me.share_location ? 'checked' : ''}><span><b>Show where I am</b><br><span class="muted">Your city in the header and a rough spot (within about 1 km) on the map. Your track is never shown.</span></span></label>
            <label class="tb-switch" style="margin-top:12px"><input type="checkbox" id="me-feat" ${b.me.featured ? 'checked' : ''}><span><b>Show "Trip" in my menu</b><br><span class="muted">One trip at a time; on the app too.</span></span></label></div>
            <div class="tb-sec"><h3>Travellers</h3>${b.members.map((m) => `<div class="tb-viewer"><div><b>${esc(m.name)}</b> <span class="dim">@${esc(m.username)}</span>
                <div class="tb-card-m">${m.role === 'owner' ? 'owner' : 'traveller'}${m.accepted ? '' : ' · invited'}${m.share_location ? ' · sharing location' : ''}</div></div>
                ${b.is_owner && m.role !== 'owner' ? `<button class="tb-btn sm danger" data-mrm="${m.id}">Remove</button>` : ''}</div>`).join('')}</div>
            <div class="tb-sec"><h3>Invite a traveller</h3><p class="muted" style="font-size:.85rem;margin:0 0 8px">Send this to someone on the trip. They sign in (or make an account) and can post too.</p>
            ${b.invite_url ? `<div class="tb-url">${esc(b.invite_url)}</div>` : ''}
            <div class="tb-row"><button class="tb-btn sm" data-icopy>Copy invite link</button><button class="tb-btn sm" data-irot>New link</button></div></div>
            ${b.is_owner ? '' : '<div class="tb-sec"><button class="tb-btn danger" data-leave>Leave this trip</button></div>'}`;
        $('me-share').onchange = (e) => memberApi(memberUrl('/'), {method: 'POST', body: {share_location: e.target.checked}}).then(() => loadStatus()).catch((err) => toast(err.message, 'error'));
        $('me-feat').onchange = (e) => memberApi(memberUrl('/'), {method: 'POST', body: {featured: e.target.checked}}).then(() => toast('Saved — reload to update the menu')).catch((err) => toast(err.message, 'error'));
        body.onclick = async (e) => {
            const t = e.target.closest('button'); if (!t) return;
            try {
                if (t.hasAttribute('data-icopy')) { const d = await memberApi(memberUrl('/invite/'), {method: 'POST', body: {}}); return copy(d.invite_url); }
                if (t.hasAttribute('data-irot')) { if (!(await confirmIt('Make a new invite link? The old one stops working.'))) return; await memberApi(memberUrl('/invite/'), {method: 'POST', body: {rotate: true}}); return tabPeople(body); }
                if (t.dataset.mrm) { if (!(await confirmIt('Remove this traveller from the trip?', true))) return; await memberApi(memberUrl(`/members/${t.dataset.mrm}/remove/`), {method: 'POST', body: {}}); return tabPeople(body); }
                if (t.hasAttribute('data-leave')) { if (!(await confirmIt('Leave this trip?', true))) return; await memberApi(memberUrl('/leave/'), {method: 'POST', body: {}}); location.href = '/adventures/'; }
            } catch (err) { toast(err.message, 'error'); }
        };
    }
    async function tabTrip(body) {
        let b;
        try { b = await memberApi(memberUrl('/')); } catch (e) { body.innerHTML = esc(e.message); return; }
        body.innerHTML = `<div class="tb-sec"><h3>Trip</h3>
            <div class="tb-field"><label>Name</label><input class="tb-in" id="t-name" maxlength="200" value="${esc(b.name)}"></div>
            <div class="tb-field"><label>Subtitle</label><input class="tb-in" id="t-sub" maxlength="300" value="${esc(b.subtitle)}" placeholder="e.g. 5 of us, 9 months, no plan"></div>
            <div class="tb-row"><div class="tb-field grow"><label>Starts</label><input class="tb-in" type="date" id="t-start" value="${esc(b.start_date)}"></div>
            <div class="tb-field grow"><label>Ends</label><input class="tb-in" type="date" id="t-end" value="${esc(b.end_date || '')}"><div class="hint">After this the page becomes an archive: no live locations.</div></div></div>
            <button class="tb-btn primary" id="t-save">Save</button></div>
            <div class="tb-sec"><h3>Cover photo</h3>${b.cover ? `<img src="${esc(b.cover_thumb || b.cover)}" alt="" style="max-width:100%;max-height:160px;display:block;margin-bottom:8px">` : ''}
            <div class="tb-row"><label class="tb-btn sm">Choose photo<input type="file" accept="image/*" id="t-cover" hidden></label>${b.cover ? '<button class="tb-btn sm danger" id="t-cover-rm">Remove</button>' : ''}</div></div>
            ${b.is_owner ? '<div class="tb-sec"><h3>Danger</h3><button class="tb-btn danger" id="t-del">Delete this trip</button><div class="hint dim" style="font-size:.76rem;margin-top:6px">Deletes every post, photo, video and comment. Share links stop working.</div></div>' : ''}`;
        $('t-save').onclick = async () => {
            try {
                await memberApi(memberUrl('/'), {method: 'POST', body: {name: $('t-name').value, subtitle: $('t-sub').value,
                    start_date: $('t-start').value, end_date: $('t-end').value || null}});
                toast('Saved'); loadStatus();
            } catch (e) { toast(e.message, 'error'); }
        };
        $('t-cover').onchange = async (e) => {
            const f = e.target.files[0]; if (!f) return;
            const fd = new FormData(); fd.append('image', f);
            try { await xhrUpload(memberUrl('/cover/'), fd, () => {}); tabTrip(body); loadStatus(); } catch (err) { toast(err.message, 'error'); }
        };
        if ($('t-cover-rm')) $('t-cover-rm').onclick = async () => {
            const fd = new FormData(); fd.append('remove', '1');
            try { await xhrUpload(memberUrl('/cover/'), fd, () => {}); $('tb-cover').style.backgroundImage = ''; tabTrip(body); loadStatus(); } catch (err) { toast(err.message, 'error'); }
        };
        if ($('t-del')) $('t-del').onclick = async () => {
            if (!(await confirmIt(`Delete "${b.name}" for everyone? This cannot be undone.`, true))) return;
            try { await memberApi(memberUrl('/delete/'), {method: 'POST', body: {}}); location.href = '/adventures/'; } catch (e) { toast(e.message, 'error'); }
        };
    }
    async function preview() {
        try {
            const links = (await memberApi(memberUrl('/links/'))).links.filter((l) => l.active);
            if (!links.length) { toast('Create a share link first'); openDrawer('share'); return; }
            window.open(links[0].url, '_blank', 'noopener');
        } catch (e) { toast(e.message, 'error'); }
    }

    // ── boot ────────────────────────────────────────────────────────────────
    initMap();
    if (MEMBER) {
        initComposer();
        $('tb-manage').addEventListener('click', () => openDrawer());
        $('tb-preview').addEventListener('click', preview);
    }
    loadStatus();
    loadFeed(true).then(() => {
        const m = location.hash.match(/^#post-(\d+)$/);
        if (m) jumpToPost(m[1]);
    });
    setInterval(() => { if (!document.hidden) loadStatus(); }, 60000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) loadStatus(); });
})();
