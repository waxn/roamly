/* Roamly shared map engine.
 *
 * Everything both the main map (/map/) and the map editor (/editor/) need in
 * order to look and behave identically: basemaps, device colours, the speed
 * gradient, the filter toolbar, the decimated track layer, the accumulated
 * detail-point engine, and road snapping.
 *
 * Loaded as a classic script, so its top-level `const`/`let` bindings are
 * visible to the page script that runs after it — `map`, `accMap`, `snapCache`
 * and friends are shared exactly as they were when this all lived in map.html.
 *
 * The page must define `window.ROAMLY_MAP_CFG` BEFORE loading this file, and
 * must provide these element ids: map, map-info, map-loading-bar,
 * map-detail-controls, map-detail-hint, map-load-more-btn, time-range,
 * device-filter, start-date, end-date, date-reset, date-hint. Page-specific
 * features (globe, scratch, places, fog, replay, editing tools) stay in the
 * page and simply register their own `map.on(...)` listeners.
 */
window.ROAMLY_MAP_CFG = window.ROAMLY_MAP_CFG || {};
window.ROAMLY_MAP_HOOKS = window.ROAMLY_MAP_HOOKS || {};

// Basemaps come from the server: tracker/basemaps.py builds the list (built-in
// CARTO/Esri, the free extras, and the Mapbox/MapTiler/Thunderforest/Stadia
// ones that have a key — the instance's, or the user's own Mapbox token),
// filtered to what an admin switched on in Admin Panel -> Basemaps, with every
// key and the CARTO instance key already in the URLs. The page renders it with json_script as #roamly-basemaps. Each
// entry's `style` is a MapLibre style object or a style URL.
//
// Entry ids are what roamly_map_tile_style stores, and the original six
// ('Streets', 'Dark', 'Satellite', 'Mapbox Streets/Outdoors/Satellite') kept
// their names, so a saved choice survives this change.
const _basemapList = (() => {
    try {
        const el = document.getElementById('roamly-basemaps');
        const list = el ? JSON.parse(el.textContent) : null;
        if (Array.isArray(list) && list.length) return list;
    } catch (e) { console.warn('basemap list unreadable', e); }
    // A page that never rendered the list still gets a working map.
    return [{ id: 'Streets', group: 'built-in', label: 'streets', name: 'Streets', style: {
        version: 8,
        sources: { osm: { type: 'raster', tileSize: 256,
            tiles: ['https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png' + (ROAMLY_MAP_CFG.cartoQs || '')],
            attribution: '&copy; OpenStreetMap contributors &copy; CARTO' } },
        layers: [{ id: 'osm', type: 'raster', source: 'osm' }]
    } }];
})();
const mapStyles = {};
_basemapList.forEach(b => { mapStyles[b.id] = b.style; });

// Build the picker: one group per provider, each after the first under its
// own sublabel. A shared grid crushed long names into slivers in the 220px
// sidebar, which is why Mapbox already had its own group.
//
// Each group is a 6-column grid where a button normally spans 2 (three per
// row), and the last row is widened so it always fills the width: a lone
// button spans all 6, a pair spans 3 each, and a group that would leave one
// button alone (4, 7, …) ends in two rows of two instead.
function _basemapSpans(n) {
    if (n === 1) return [6];
    const spans = new Array(n).fill(2);
    const rem = n % 3;
    if (rem === 2) { spans[n - 1] = 3; spans[n - 2] = 3; }
    else if (rem === 1) { for (let i = n - 4; i < n; i++) spans[i] = 3; }
    return spans;
}
(function buildBasemapPicker() {
    const section = document.getElementById('basemap-section');
    if (!section) return;
    section.querySelectorAll('.sidebar-btn-group, .sidebar-sublabel').forEach(el => el.remove());
    const groupNames = { 'built-in': '', free: 'free', mapbox: 'mapbox', maptiler: 'maptiler',
                         thunderforest: 'thunderforest', stadia: 'stadia', custom: 'custom' };
    const groups = [];
    _basemapList.forEach(b => {
        const last = groups[groups.length - 1];
        if (last && last.id === b.group) last.items.push(b);
        else groups.push({ id: b.group, items: [b] });
    });
    groups.forEach((g, gi) => {
        const name = groupNames[g.id] !== undefined ? groupNames[g.id] : g.id;
        if (gi > 0 && name) {
            const lbl = document.createElement('div');
            lbl.className = 'sidebar-sublabel';
            lbl.textContent = name;
            section.appendChild(lbl);
        }
        const grid = document.createElement('div');
        grid.className = 'sidebar-btn-group';
        grid.style.gridTemplateColumns = 'repeat(6, minmax(0, 1fr))';
        const spans = _basemapSpans(g.items.length);
        g.items.forEach((b, i) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'style-btn';
            btn.dataset.style = b.id;
            btn.textContent = b.label;
            btn.title = b.name;
            btn.style.gridColumn = 'span ' + spans[i];
            grid.appendChild(btn);
        });
        section.appendChild(grid);
    });
})();

function showMapLoading(label) {
    document.getElementById('map-loading-bar').classList.add('active');
    document.getElementById('map-info').textContent = label || 'loading...';
}
function hideMapLoading() {
    document.getElementById('map-loading-bar').classList.remove('active');
}

const csrfToken = ROAMLY_MAP_CFG.csrfToken;
const deviceColors = ['#e8763d', '#7f9b6d', '#7ba3b8', '#cf9a44', '#a888a0', '#c0553f', '#6b7fa3', '#4f8a7b'];

function getDeviceColors() {
    try { return JSON.parse(localStorage.getItem('roamly_device_colors') || '{}'); } catch { return {}; }
}
const _customDeviceColors = getDeviceColors();
function deviceColor(id) {
    if (_customDeviceColors[id]) return _customDeviceColors[id];
    let hash = 0;
    for (let i = 0; i < id.length; i++) hash = ((hash << 5) - hash + id.charCodeAt(i)) | 0;
    return deviceColors[Math.abs(hash) % deviceColors.length];
}

const speedGradient = localStorage.getItem('roamly_speed_gradient') || 'on';

// Returns a MapLibre expression that colors by speed when speedGradient is on.
// fallback: a static color string or MapLibre expression used when stationary (speed <= 1 m/s).
function speedColorExpr(fallback) {
    return [
        'case',
        ['<=', ['coalesce', ['get', 'speed'], 0], 1.0],
        fallback,
        ['interpolate', ['linear'], ['get', 'speed'],
            1.0, '#3b82f6',
            5.0, '#22c55e',
            15.0, '#f59e0b',
            30.0, '#ef4444'
        ]
    ];
}

// Apply default time range from preferences
const defaultTimeRange = localStorage.getItem('roamly_default_time_range') || '24';
document.getElementById('time-range').value = defaultTimeRange;

// Fall back to the first offered basemap if the saved one is unavailable
// (switched off in Admin Panel -> Basemaps, or its key/token was removed).
const _rawSavedTileStyle = localStorage.getItem('roamly_map_tile_style') || 'Streets';
const _savedTileStyle = mapStyles[_rawSavedTileStyle] ? _rawSavedTileStyle : _basemapList[0].id;

const map = new maplibregl.Map({
    container: 'map',
    style: mapStyles[_savedTileStyle],
    center: [0, 20],
    zoom: 2,
});

map.addControl(new maplibregl.NavigationControl(), 'bottom-right');

// Surface keyed-basemap tile failures instead of showing a silent blank map.
// A correct key returns tiles; a 401/403 here almost always means the key is
// wrong or has URL/domain restrictions that exclude this site.
const _keyedSources = {
    mapbox: 'Mapbox', maptiler: 'MapTiler', thunderforest: 'Thunderforest', stadia: 'Stadia Maps',
};
let _mapboxErrorShown = false;
map.on('error', (e) => {
    const url = e && e.error && (e.error.url || '');
    const status = e && e.error && e.error.status;
    let provider = e && _keyedSources[e.sourceId];
    if (!provider && typeof url === 'string') {
        if (url.indexOf('api.mapbox.com') !== -1) provider = 'Mapbox';
        else if (url.indexOf('api.maptiler.com') !== -1) provider = 'MapTiler';
        else if (url.indexOf('thunderforest.com') !== -1) provider = 'Thunderforest';
        else if (url.indexOf('stadiamaps.com') !== -1) provider = 'Stadia Maps';
    }
    if (!provider) return;
    console.warn(provider + ' tile error', status || '', url || '', e && e.error);
    if (!_mapboxErrorShown) {
        _mapboxErrorShown = true;
        document.getElementById('map-info').textContent =
            provider + ' tiles failed to load — the key may be wrong or restricted (Admin Panel → Basemaps).';
    }
});

// Restore active button for saved style
document.querySelectorAll('.style-btn').forEach(b => {
    b.classList.toggle('active', b.dataset.style === _savedTileStyle);
});

// Style switcher — re-render track after style swap
document.querySelectorAll('.style-btn').forEach(btn => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.style-btn').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        localStorage.setItem('roamly_map_tile_style', btn.dataset.style);
        _mapboxErrorShown = false;  // allow the diagnostic to show again for the new basemap
        map.setStyle(mapStyles[btn.dataset.style]);
        map.once('styledata', () => {
            if (overviewData) {
                renderOverview(overviewData, false);
                return;
            }
            if (TRIP_LINES_ENABLED && connectLines === 'on') {
                if (tripLineData) renderTripLines(tripLineData, false);
                return;
            }
            renderTrack(lastTrackData, false);
            if (accMap.size > 0) initAccLayers();
        });
    });
});

// ── Popup builder ──
function formatSpeed(mps) {
    if (mps == null || mps < 0) return null;
    const unit = localStorage.getItem('roamly_speed_unit') || 'kmh';
    if (unit === 'mph') return `${(mps * 2.23694).toFixed(1)} mph`;
    return `${(mps * 3.6).toFixed(1)} km/h`;
}

// One popup for the whole page. Layer click handlers get re-registered whenever
// a layer is rebuilt (a filter change, a basemap swap), and the decimated track
// and the detail dots draw the same fix above DETAIL_MIN_ZOOM — so a single
// click could fire several handlers and stack identical popups, where closing
// one only revealed the next. Replacing a shared instance makes that harmless.
let _pointPopup = null;
function openPointPopup(coords, html) {
    // A page with its own click semantics (the editor's placing tools) can veto.
    const veto = ROAMLY_MAP_HOOKS.suppressPopup;
    if (typeof veto === 'function' && veto()) return;
    if (_pointPopup) _pointPopup.remove();
    _pointPopup = new maplibregl.Popup({ offset: 10, maxWidth: '280px' })
        .setLngLat(coords).setHTML(html).addTo(map);
    _pointPopup.on('close', () => { _pointPopup = null; });
}

function escHtml(v) {
    return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function showPointPopup(pt, coords) {
    const ts = new Date(pt.ts * 1000);
    let html = '';
    // The store or address, when the point was matched to one — the first
    // thing you want when zoomed in on somewhere you went.
    const place = pt.poi_name || pt.place_name;
    if (place) html += `<strong>${escHtml(place)}</strong><br>`;
    html += `<strong>${pt.device_name || 'Unknown'}</strong><br>`;
    html += `<span style="color:var(--text-muted)">${ts.toLocaleString()}</span>`;
    if (pt.city)    html += `<br>${pt.city}`;
    if (pt.state)   html += `, ${pt.state}`;
    if (pt.country) html += `<br>${pt.country}`;
    const spd = formatSpeed(pt.speed);
    if (spd) html += `<br><span style="color:var(--text-muted)">${spd}</span>`;
    openPointPopup(coords, html);
}

// ── Filter params ──
// Place filter ({city, state, country}) from the URL — what a click on a Visits
// row opens. Narrows every map request to points geocoded to that place.
let placeFilter = {};
const PLACE_FIELDS = ['city', 'state', 'country'];
function appendPlaceParams(params) {
    PLACE_FIELDS.forEach(f => { if (placeFilter[f]) params.set(f, placeFilter[f]); });
}

function renderPlaceFilter() {
    const box = document.getElementById('place-filter');
    if (!box) return;
    const label = PLACE_FIELDS.map(f => placeFilter[f]).filter(Boolean).join(', ');
    box.hidden = !label;
    const text = document.getElementById('place-filter-label');
    if (text) text.textContent = label;
}

function clearPlaceFilter() {
    placeFilter = {};
    const url = new URL(window.location.href);
    PLACE_FIELDS.forEach(f => url.searchParams.delete(f));
    history.replaceState(null, '', url.pathname + url.search + url.hash);
    renderPlaceFilter();
    clearAccumulated();
    loadCurrentMode(true);
}

function buildFilterParams() {
    const timeRange = document.getElementById('time-range').value;
    const deviceId  = document.getElementById('device-filter').value;
    const startDate = document.getElementById('start-date').value;
    const endDate   = document.getElementById('end-date').value;
    const params = new URLSearchParams();
    if (startDate && endDate) {
        params.set('start_date', toLocalDateTimeParam(startDate, 'start'));
        params.set('end_date', toLocalDateTimeParam(endDate, 'end'));
    } else if (startDate) {
        // Anchored mode: show the selected time range starting from this day.
        params.set('start_date', toLocalDateTimeParam(startDate, 'start'));
        params.set('end_date', toLocalDateTimeParam(anchoredEndDate(startDate, timeRange), 'end'));
    } else if (timeRange === 'all') {
        params.set('all', '1');
    } else {
        params.set('hours', timeRange);
    }
    if (deviceId) params.set('device_id', deviceId);
    appendPlaceParams(params);
    return params.toString();
}

// Given a start day (YYYY-MM-DD) and the time-range dropdown value, return the
// inclusive end day (YYYY-MM-DD) that the time range spans forward from it.
// Sub-day ranges collapse to the single start day; 'all' runs through today.
function anchoredEndDate(startStr, timeRange) {
    const [y, m, d] = startStr.split('-').map(Number);
    const start = new Date(y, m - 1, d);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    let end;
    if (timeRange === 'all') {
        end = today > start ? today : start;
    } else {
        const days = Math.max(1, Math.round(parseInt(timeRange, 10) / 24));
        end = new Date(start);
        end.setDate(end.getDate() + days - 1);
        if (end > today) end = today;
    }
    const pad = n => String(n).padStart(2, '0');
    return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}`;
}

// Toggle the reset button + active-window hint based on the date inputs.
function updateDateFilterUI() {
    const s = document.getElementById('start-date').value;
    const e = document.getElementById('end-date').value;
    const btn = document.getElementById('date-reset');
    const hint = document.getElementById('date-hint');
    btn.hidden = !(s || e);
    if (s && !e) {
        const endStr = anchoredEndDate(s, document.getElementById('time-range').value);
        hint.textContent = endStr === s
            ? `showing ${fmtHumanDate(s)}`
            : `showing ${fmtHumanDate(s)} → ${fmtHumanDate(endStr)}`;
        hint.hidden = false;
    } else {
        hint.hidden = true;
        hint.textContent = '';
    }
}

function fmtHumanDate(dateStr) {
    const [y, m, d] = dateStr.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function resetDateFilter() {
    document.getElementById('start-date').value = '';
    document.getElementById('end-date').value = '';
    document.getElementById('time-range').value = defaultTimeRange;
    updateDateFilterUI();
    clearAccumulated();
    loadCurrentMode(true);
}

function toLocalDateTimeParam(dateStr, kind) {
    const [year, month, day] = dateStr.split('-').map(Number);
    const date = new Date(year, month - 1, day, kind === 'end' ? 23 : 0, kind === 'end' ? 59 : 0, kind === 'end' ? 59 : 0);
    const pad = n => String(Math.abs(n)).padStart(2, '0');
    const offset = -date.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const offsetHours = pad(Math.trunc(Math.abs(offset) / 60));
    const offsetMinutes = pad(Math.abs(offset) % 60);
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}${sign}${offsetHours}:${offsetMinutes}`;
}

// ═══════════════════════════════════════════════════════════════════
// Track rendering + accumulated detail points.
// Points fetched for any zoomed viewport accumulate permanently
// until filters change. They contribute to both heatmap and dots.
// ═══════════════════════════════════════════════════════════════════

let activeSources = [];   // decimated track source IDs on the map
const trackFeatures = new Map();  // srcId → its features, so snaps can be re-applied
let lastTrackData = [];   // for style-swap re-render
let pendingFetch = null;  // AbortController for track fetch

const DETAIL_MIN_ZOOM = parseInt(localStorage.getItem('roamly_detail_zoom') || '8', 10);
const AUTO_LOAD_LIMIT = 10000;
const MORE_LOAD_LIMIT = 50000;
const ACC_SOURCE_ID = 'acc-pts';
const ACC_HEAT_LAYER = 'acc-pts-heat';
const ACC_DOTS_LAYER = 'acc-pts-dots';
const ACC_SNAP_RING_LAYER = 'acc-pts-snap-ring';
const ACC_LINES_SOURCE = 'acc-lines';
const ACC_LINES_LAYER  = 'acc-lines-layer';
const connectLines = localStorage.getItem('roamly_connect_lines') || 'off';
const showHeatmap = localStorage.getItem('roamly_show_heatmap') || 'on';
const heatmapIntensity = localStorage.getItem('roamly_heatmap_intensity') || 'medium';
const dotSizePref = localStorage.getItem('roamly_dot_size') || 'medium';
const lineGapMs = parseInt(localStorage.getItem('roamly_line_gap_minutes') || '20', 10) * 60 * 1000;
// Hide points the quality scan flagged as 'suspect'. Default on: a stray fix a kilometre away
// drags the polyline out and back and expands the auto-fit bbox, so one bad point can rescale
// the whole map. The rows stay in the database untouched — distance and stats already give
// them no credit — so this is purely what gets drawn.
let hideSuspect = (localStorage.getItem('roamly_hide_suspect') || 'on') === 'on';
const isSuspect = f => f.properties.flag === 'suspect';
const suspectFilter = () => hideSuspect ? ['!=', ['get', 'flag'], 'suspect'] : ['literal', true];

const DOT_SIZES = {
    small:  [8, 2, 12, 4, 16, 8],
    medium: [8, 3, 12, 6, 16, 12],
    large:  [8, 5, 12, 9, 16, 18],
};
const HEATMAP_INTENSITY = {
    low:    [0, 0.2, 13, 0.7],
    medium: [0, 0.4, 13, 1.4],
    high:   [0, 0.8, 13, 2.5],
};
const dotSizeArgs = DOT_SIZES[dotSizePref] || DOT_SIZES.medium;
const heatIntArgs = HEATMAP_INTENSITY[heatmapIntensity] || HEATMAP_INTENSITY.medium;

// Trip Lines mode — set only by map.html (not the editor), so this whole
// feature is inert there regardless of the connect-lines preference.
const TRIP_LINES_ENABLED = !!ROAMLY_MAP_CFG.tripLinesEnabled;
const dwellBlobsOn = (localStorage.getItem('roamly_dwell_blobs') || 'on') === 'on';
// Dwell sensitivity — default duration floor of 30 min (vs. the 150m/5min the
// server falls back to when a caller sends nothing) so a stock install isn't
// throwing a blob at every red light and parking lot; min_points is an
// independent gate (0 = off) for a stay that clears the time bar on a sparse
// track but still shouldn't be a blob.
const dwellMinMinutes = parseInt(localStorage.getItem('roamly_dwell_min_minutes') || '30', 10);
const dwellMinPoints = parseInt(localStorage.getItem('roamly_dwell_min_points') || '0', 10);
const dwellRadiusM = parseInt(localStorage.getItem('roamly_dwell_radius_m') || '150', 10);

// Dispatch to whichever mode is active for the whole-range load. Points mode
// (loadTrack + the viewport-driven detail layer) is untouched; trip-lines mode
// replaces it entirely rather than layering on top, since the server already
// returns the whole filtered range decimated into segments + dwell blobs, so
// there is nothing left for viewport paging to do.
function loadCurrentMode(autoFit) {
    if (isOverviewMode()) {
        loadAllTimeOverview(autoFit);
        return;
    }
    clearOverviewLayers();
    if (TRIP_LINES_ENABLED && connectLines === 'on') {
        loadTripLines(autoFit);
    } else {
        loadTrack(autoFit);
    }
}

let accMap = new Map();            // "devId:ts" → Feature, deduped accumulated points
const rawCoords = new Map();       // point id → recorded [lng,lat], so snapping is reversible
let fetchedAreas = [];             // {w,s,e,n} bboxes where we have complete data (no limit hit)
let detailLoading = false;
let lastViewportHitLimit = false;  // whether last viewport fetch hit the limit
let moveDebounce = null;

function clearTrackLayers() {
    activeSources.forEach(id => {
        [`${id}-heat`, `${id}-dots`].forEach(lid => { if (map.getLayer(lid)) map.removeLayer(lid); });
        if (map.getSource(id)) map.removeSource(id);
        trackFeatures.delete(id);
    });
    activeSources = [];
}

function getAccFC() {
    return { type: 'FeatureCollection', features: Array.from(accMap.values()) };
}

const LINE_GAP_MS = lineGapMs;

function buildLinesFC() {
    const byDevice = new Map();
    accMap.forEach(f => {
        // Skip suspects entirely rather than breaking the segment at them: the line should
        // run from the point before straight to the point after, not spike out and back.
        if (hideSuspect && isSuspect(f)) return;
        const id = f.properties.device_id;
        if (!byDevice.has(id)) byDevice.set(id, []);
        byDevice.get(id).push(f);
    });
    const features = [];
    byDevice.forEach(pts => {
        if (!pts.length) return;
        pts.sort((a, b) => new Date(a.properties.timestamp) - new Date(b.properties.timestamp));
        const color = pts[0].properties.color;
        let seg = [pts[0].geometry.coordinates];
        for (let i = 1; i < pts.length; i++) {
            const gap = new Date(pts[i].properties.timestamp) - new Date(pts[i - 1].properties.timestamp);
            if (gap > LINE_GAP_MS) {
                if (seg.length >= 2) features.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: seg }, properties: { color } });
                seg = [pts[i].geometry.coordinates];
            } else {
                seg.push(pts[i].geometry.coordinates);
            }
        }
        if (seg.length >= 2) features.push({ type: 'Feature', geometry: { type: 'LineString', coordinates: seg }, properties: { color } });
    });
    return { type: 'FeatureCollection', features };
}

/** Flip the suspect-hiding preference and repaint. Both dot layers carry the filter (the
 *  decimated track and the detail accumulation draw the same fix above DETAIL_MIN_ZOOM, so
 *  filtering only one would leave the outlier visible), and the connecting lines are rebuilt
 *  because they route *around* suspects rather than breaking at them. */
function setHideSuspect(on) {
    hideSuspect = on;
    localStorage.setItem('roamly_hide_suspect', on ? 'on' : 'off');
    if (map.getLayer(ACC_DOTS_LAYER)) map.setFilter(ACC_DOTS_LAYER, suspectFilter());
    if (map.getLayer(ALLTIME_DOTS_LAYER)) map.setFilter(ALLTIME_DOTS_LAYER, suspectFilter());
    activeSources.forEach(id => {
        if (!map.getLayer(`${id}-dots`)) return;
        map.setFilter(`${id}-dots`, on
            ? ['all', ['==', ['geometry-type'], 'Point'], ['!=', ['get', 'flag'], 'suspect']]
            : ['==', ['geometry-type'], 'Point']);
    });
    if (map.getSource(ACC_LINES_SOURCE)) map.getSource(ACC_LINES_SOURCE).setData(buildLinesFC());
}

/** How many accumulated points are currently being hidden — drives the sidebar badge. */
function suspectCount() {
    let n = 0;
    accMap.forEach(f => { if (isSuspect(f)) n++; });
    trackFeatures.forEach(features => {
        features.forEach(f => { if (isSuspect(f)) n++; });
    });
    return n;
}

let _accHandlersBound = false;
function initAccLayers() {
    if (map.getSource(ACC_SOURCE_ID)) return;

    map.addSource(ACC_SOURCE_ID, { type: 'geojson', data: getAccFC() });

    // Heatmap
    if (showHeatmap === 'on') {
        map.addLayer({
            id: ACC_HEAT_LAYER,
            type: 'heatmap',
            source: ACC_SOURCE_ID,
            maxzoom: 13,
            paint: {
                'heatmap-intensity': ['interpolate', ['linear'], ['zoom'], ...heatIntArgs],
                'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 0, 6, 13, 26],
                'heatmap-opacity': ['interpolate', ['linear'], ['zoom'], 0, 0.75, 11, 0.6, 13, 0.1],
                'heatmap-color': [
                    'interpolate', ['linear'], ['heatmap-density'],
                    0, 'rgba(33,102,172,0)',
                    0.2, 'rgb(103,169,207)',
                    0.4, 'rgb(209,229,240)',
                    0.6, 'rgb(253,219,199)',
                    0.8, 'rgb(239,138,98)',
                    1, 'rgb(178,24,43)'
                ]
            }
        });
    }

    // Lines — connect points in chronological order per device (only when setting is on)
    if (connectLines === 'on') {
        map.addSource(ACC_LINES_SOURCE, { type: 'geojson', data: buildLinesFC() });
        map.addLayer({
            id: ACC_LINES_LAYER,
            type: 'line',
            source: ACC_LINES_SOURCE,
            minzoom: DETAIL_MIN_ZOOM,
            paint: {
                'line-color': ['get', 'color'],
                'line-width': 3,
                'line-opacity': 0.75,
            }
        });
    }

    // Snapped-point halo — sits under the dots so it reads as an outer ring.
    // A dashed circle stroke isn't expressible in MapLibre, so the "was moved"
    // cue is a detached mauve ring, distinct from any device colour.
    map.addLayer({
        id: ACC_SNAP_RING_LAYER,
        type: 'circle',
        source: ACC_SOURCE_ID,
        minzoom: DETAIL_MIN_ZOOM,
        filter: ['==', ['get', 'snapped'], true],
        paint: {
            'circle-radius': ['interpolate', ['linear'], ['zoom'],
                8, dotSizeArgs[1] + 1.5, 12, dotSizeArgs[3] + 2, 16, dotSizeArgs[5] + 2.5],
            'circle-color': 'rgba(0,0,0,0)',
            'circle-stroke-width': 1.2,
            'circle-stroke-color': '#a78bda',
            'circle-stroke-opacity': 0.9,
        }
    });

    // Dots
    map.addLayer({
        id: ACC_DOTS_LAYER,
        type: 'circle',
        source: ACC_SOURCE_ID,
        minzoom: DETAIL_MIN_ZOOM,
        filter: suspectFilter(),
        paint: {
            'circle-color': speedGradient === 'on' ? speedColorExpr(['get', 'color']) : ['get', 'color'],
            'circle-radius': ['interpolate', ['linear'], ['zoom'], ...dotSizeArgs],
            'circle-stroke-width': ['case', ['==', ['get', 'snapped'], true], 1.6, 1],
            'circle-stroke-color': ['case',
                // A suspect point still reads as suspect first — that's a data
                // problem, whereas snapping is only a display choice.
                ['==', ['get', 'flag'], 'suspect'], '#ff2020',
                ['==', ['get', 'snapped'], true], '#a78bda',
                'rgba(255,255,255,0.7)'
            ],
        }
    });

    if (_accHandlersBound) return;
    _accHandlersBound = true;
    map.on('click', ACC_DOTS_LAYER, e => {
        const p = e.features[0].properties;
        const ts = p.timestamp ? new Date(p.timestamp) : null;
        let html = `<strong>${p.device_name || p.device_id || 'Unknown'}</strong>`;
        if (ts && !isNaN(ts.getTime())) {
            html += `<br><span style="color:var(--text-muted)">${ts.toLocaleString()}</span>`;
        }
        if (p.city) html += `<br>${p.city}`;
        if (p.state) html += `, ${p.state}`;
        if (p.country) html += `<br>${p.country}`;
        const spd = formatSpeed(p.speed);
        if (spd) html += `<br><span style="color:var(--text-muted)">${spd}</span>`;
        // GeoJSON-source properties survive as real booleans, but be tolerant.
        if (p.snapped === true || p.snapped === 'true') {
            html += '<br><span style="color:#a78bda">snapped to road — display only, '
                 + 'the recorded position is unchanged</span>';
        }
        openPointPopup(e.features[0].geometry.coordinates.slice(), html);
    });
    map.on('mouseenter', ACC_DOTS_LAYER, () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', ACC_DOTS_LAYER, () => map.getCanvas().style.cursor = '');
}

function repaintAcc() {
    (ROAMLY_MAP_HOOKS.onPointsPainted || []).forEach(fn => { try { fn(); } catch (e) { console.error(e); } });
    if (map.getSource(ACC_SOURCE_ID)) {
        map.getSource(ACC_SOURCE_ID).setData(getAccFC());
    }
    if (connectLines === 'on' && map.getSource(ACC_LINES_SOURCE)) {
        map.getSource(ACC_LINES_SOURCE).setData(buildLinesFC());
    }
}

function clearAccumulated() {
    accMap.clear();
    // rawCoords / snapCache survive on purpose — they map an immutable point id
    // to an immutable road position, so a filter change must not force a re-query
    // (and must not let a re-fetched point briefly paint unsnapped).
    fetchedAreas = [];
    lastViewportHitLimit = false;
    if (map.getLayer(ACC_HEAT_LAYER)) map.removeLayer(ACC_HEAT_LAYER);
    if (map.getLayer(ACC_LINES_LAYER)) map.removeLayer(ACC_LINES_LAYER);
    if (map.getLayer(ACC_SNAP_RING_LAYER)) map.removeLayer(ACC_SNAP_RING_LAYER);
    if (map.getLayer(ACC_DOTS_LAYER)) map.removeLayer(ACC_DOTS_LAYER);
    if (map.getSource(ACC_LINES_SOURCE)) map.removeSource(ACC_LINES_SOURCE);
    if (map.getSource(ACC_SOURCE_ID)) map.removeSource(ACC_SOURCE_ID);
    updateDetailControls();
}

function mergeAccFeatures(newFeatures) {
    newFeatures.forEach(f => {
        const key = `${f.properties.device_id}:${f.properties.timestamp}`;
        accMap.set(key, f);
    });
    initAccLayers();
    repaintAcc();
}

function updateDetailControls() {
    const panel = document.getElementById('map-detail-controls');
    const hint = document.getElementById('map-detail-hint');
    const btn = document.getElementById('map-load-more-btn');

    const zoom = map.getZoom();
    // Show the manual override whenever we're zoomed in with data on screen — not
    // only in "dense area" hits. A sparse walk never trips the limit flag, but the
    // user still wants a one-tap way to force every point in view to load.
    const showPanel = zoom >= DETAIL_MIN_ZOOM && lastTrackData.length > 0;
    panel.style.display = showPanel ? 'flex' : 'none';
    if (!showPanel) return;

    if (detailLoading) {
        hint.textContent = 'loading points...';
        btn.disabled = true;
        return;
    }

    btn.disabled = false;
    hint.textContent = lastViewportHitLimit
        ? `${accMap.size.toLocaleString()} pts loaded · dense area`
        : `${accMap.size.toLocaleString()} pts loaded`;
}

function isViewportCovered(b) {
    const w = b.getWest(), s = b.getSouth(), e = b.getEast(), n = b.getNorth();
    return fetchedAreas.some(a => a.w <= w && a.s <= s && a.e >= e && a.n >= n);
}

function loadViewport(limit) {
    limit = limit || AUTO_LOAD_LIMIT;
    if (detailLoading || map.getZoom() < DETAIL_MIN_ZOOM || lastTrackData.length === 0) return;

    const b = map.getBounds();
    const params = new URLSearchParams(buildFilterParams());
    params.set('min_lng', b.getWest());
    params.set('min_lat', b.getSouth());
    params.set('max_lng', b.getEast());
    params.set('max_lat', b.getNorth());
    params.set('limit', limit);
    params.set('offset', '0');

    detailLoading = true;
    updateDetailControls();
    showMapLoading('loading points...');

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 15000);

    fetch(`/api/locations/?${params.toString()}`, { signal: controller.signal })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(data => {
            const features = [];
            (data.devices || []).forEach(device => {
                const color = deviceColor(device.device_id || device.name || 'default');
                (device.locations || []).forEach(loc => {
                    features.push({
                        type: 'Feature',
                        geometry: { type: 'Point', coordinates: [loc.lng, loc.lat] },
                        properties: {
                            id: loc.id,
                            device_id: device.device_id,
                            device_name: device.name,
                            timestamp: loc.timestamp,
                            speed: loc.speed ?? null,
                            city: loc.city,
                            state: loc.state,
                            country: loc.country,
                            flag: loc.flag ?? '',
                            color,
                        }
                    });
                });
            });

            const hitLim = features.length >= limit * 0.95;
            lastViewportHitLimit = hitLim;
            if (!hitLim) {
                fetchedAreas.push({ w: b.getWest(), s: b.getSouth(), e: b.getEast(), n: b.getNorth() });
            }

            // Resolve road positions before anything is drawn, so each point
            // appears once in its final place instead of jumping when a later
            // response lands.
            if (snapOn) showMapLoading('snapping to roads...');
            return fetchSnapsFor(features).then(() => {
                applyKnownSnaps(features);
                mergeAccFeatures(features);
                repaintTrackSnaps();
                updateSnapBadge();
                clearTimeout(timeoutId);
                hideMapLoading();

                const total = lastTrackData.reduce((s, d) => s + d.total, 0);
                document.getElementById('map-info').textContent =
                    `${total.toLocaleString()} pts · ${accMap.size.toLocaleString()} loaded in detail`;
            });
        })
        .catch(err => {
            clearTimeout(timeoutId);
            if (err.name !== 'AbortError') console.error('Failed to load points:', err);
            hideMapLoading();
        })
        .finally(() => {
            detailLoading = false;
            updateDetailControls();
        });
}

function maybeLoadViewport() {
    // Trip-lines mode already has the whole filtered range from one request —
    // there is nothing for viewport-driven detail paging to add.
    if (TRIP_LINES_ENABLED && connectLines === 'on') return;
    // All time: the vector tiles are the detail layer.
    if (overviewData) return;
    if (detailLoading || map.getZoom() < DETAIL_MIN_ZOOM || lastTrackData.length === 0) return;
    const b = map.getBounds();
    if (isViewportCovered(b)) {
        lastViewportHitLimit = false;
        updateDetailControls();
        return;
    }
    loadViewport(AUTO_LOAD_LIMIT);
}

async function fetchAndFocusSelected(id, dateStr) {
    try {
        const params = new URLSearchParams(buildFilterParams());
        if (dateStr) {
            params.set('start_date', dateStr);
            params.set('end_date', dateStr);
            params.set('all', '1');
        }
        params.set('limit', '50000');
        params.set('offset', '0');

        const resp = await fetch(`/api/locations/?${params.toString()}`);
        if (!resp.ok) return;
        const data = await resp.json();

        let found = null;
        if (Array.isArray(data.locations)) {
            found = data.locations.find(l => String(l.id) === String(id));
        }
        if (!found && Array.isArray(data.devices)) {
            for (const dev of data.devices) {
                if (!dev.locations) continue;
                const f = dev.locations.find(l => String(l.id) === String(id));
                if (f) { if (!f.device) f.device = dev.name || dev.device_id; found = f; break; }
            }
        }
        if (!found) return;

        const coords = [found.lng, found.lat];
        const props = {
            ts: Math.floor(new Date(found.timestamp).getTime() / 1000),
            device_name: found.device || found.device_name || '',
            speed: found.speed ?? null,
            city: found.city, state: found.state, country: found.country,
        };
        map.once('idle', () => {
            try { map.flyTo({ center: coords, zoom: 15 }); } catch (e) {}
            showPointPopup(props, coords);
        });
    } catch (err) {
        console.error('fetchAndFocusSelected error', err);
    }
}

// ── Focus a moment: ?at=<ISO instant>&lat=&lng= ──
// What a record's "where" link opens (Achievements' top speed, highest point…):
// the map filtered to that local day, zoomed to the spot, with the recorded
// point nearest that instant selected — popup plus a ring that survives
// basemap swaps (a DOM marker, not a layer).
let focusMoment = null;
let urlCentred = false;   // ?lat=&lng= given: the link chose the camera
let focusMarker = null;

function localDateStr(d) {
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function markSelected(coords) {
    if (focusMarker) focusMarker.remove();
    const el = document.createElement('div');
    el.style.cssText = 'width:22px;height:22px;border-radius:50%;border:3px solid var(--primary,#e8763d);'
        + 'box-shadow:0 0 0 3px rgba(0,0,0,.35);pointer-events:none;';
    focusMarker = new maplibregl.Marker({ element: el }).setLngLat(coords).addTo(map);
}

async function focusOnMoment(f) {
    let best = null;
    try {
        const params = new URLSearchParams(buildFilterParams());
        params.set('limit', '50000');
        params.set('offset', '0');
        const resp = await fetch(`/api/locations/?${params.toString()}`);
        if (resp.ok) {
            const data = await resp.json();
            const all = [];
            (data.locations || []).forEach(l => all.push(l));
            (data.devices || []).forEach(dev => (dev.locations || []).forEach(l => {
                if (!l.device) l.device = dev.name || dev.device_id;
                all.push(l);
            }));
            let bestKey = Infinity;
            for (const l of all) {
                const dt = Math.abs(new Date(l.timestamp).getTime() - f.t);
                // Nearest in time; a near-tie goes to the one at the given spot.
                const dist = (f.lat == null) ? 0
                    : Math.hypot(l.lat - f.lat, (l.lng - f.lng) * Math.cos(f.lat * Math.PI / 180));
                const key = dt + dist * 1e6;
                if (key < bestKey) { bestKey = key; best = l; }
            }
        }
    } catch (err) {
        console.error('focusOnMoment error', err);
    }
    const coords = best ? [best.lng, best.lat] : (f.lat != null ? [f.lng, f.lat] : null);
    if (!coords) return;
    map.flyTo({ center: coords, zoom: f.zoom });
    markSelected(coords);
    if (best) {
        showPointPopup({
            ts: Math.floor(new Date(best.timestamp).getTime() / 1000),
            device_name: best.device || best.device_name || '',
            speed: best.speed ?? null, poi_name: best.poi_name, place_name: best.place_name,
            city: best.city, state: best.state, country: best.country,
        }, coords);
    }
}

const _trackDotHandlers = new Set();
function renderTrack(devices, autoFit) {
    lastTrackData = devices || [];
    clearTrackLayers();
    if (!devices || devices.length === 0) return;

    let minLng = Infinity, maxLng = -Infinity, minLat = Infinity, maxLat = -Infinity;

    devices.forEach((device, i) => {
        const srcId = `track-${i}`;
        const color = deviceColor(device.id);

        const features = [];
        device.points.forEach(p => {
            features.push({
                type: 'Feature',
                geometry: { type: 'Point', coordinates: p.c },
                properties: { ts: p.ts, id: p.id ?? null, speed: p.speed ?? null, flag: p.flag ?? '', color, city: p.city, state: p.state, country: p.country, device_name: device.name }
            });
            // Keep suspects out of the auto-fit bounds: a single fix a long way off
            // would otherwise zoom the whole map out to include it.
            if (hideSuspect && p.flag === 'suspect') return;
            if (p.c[0] < minLng) minLng = p.c[0];
            if (p.c[0] > maxLng) maxLng = p.c[0];
            if (p.c[1] < minLat) minLat = p.c[1];
            if (p.c[1] > maxLat) maxLat = p.c[1];
        });

        // The decimated track and the detail layer both draw above
        // DETAIL_MIN_ZOOM, so they render the same fix twice. That was invisible
        // while both sat on the recorded position — but once the detail dot
        // snaps and this one doesn't, one point becomes two side by side. Apply
        // the same cached corrections here so they stay coincident.
        applyKnownSnaps(features);
        trackFeatures.set(srcId, features);
        map.addSource(srcId, { type: 'geojson', data: { type: 'FeatureCollection', features } });
        activeSources.push(srcId);

        if (showHeatmap === 'on') {
            map.addLayer({
                id: `${srcId}-heat`,
                type: 'heatmap',
                source: srcId,
                maxzoom: 13,
                paint: {
                    'heatmap-intensity': ['interpolate', ['linear'], ['zoom'], ...heatIntArgs],
                    'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 0, 6, 13, 26],
                    'heatmap-opacity': ['interpolate', ['linear'], ['zoom'], 0, 0.75, 11, 0.6, 13, 0.1],
                    'heatmap-color': [
                        'interpolate', ['linear'], ['heatmap-density'],
                        0, 'rgba(33,102,172,0)',
                        0.2, 'rgb(103,169,207)',
                        0.4, 'rgb(209,229,240)',
                        0.6, 'rgb(253,219,199)',
                        0.8, 'rgb(239,138,98)',
                        1, 'rgb(178,24,43)'
                    ]
                }
            });
        }

        map.addLayer({
            id: `${srcId}-dots`,
            type: 'circle',
            source: srcId,
            filter: hideSuspect
                ? ['all', ['==', ['geometry-type'], 'Point'], ['!=', ['get', 'flag'], 'suspect']]
                : ['==', ['geometry-type'], 'Point'],
            minzoom: DETAIL_MIN_ZOOM,
            paint: {
                'circle-color': speedGradient === 'on' ? speedColorExpr(color) : color,
                'circle-radius': ['interpolate', ['linear'], ['zoom'], ...dotSizeArgs],
                'circle-stroke-width': 1,
                'circle-stroke-color': ['case',
                    ['==', ['get', 'flag'], 'suspect'], '#ff2020',
                    'rgba(255,255,255,0.7)'
                ],
            }
        });

        // Delegated layer listeners outlive the layer and don't dedupe, so
        // register once per layer id rather than once per render.
        if (!_trackDotHandlers.has(`${srcId}-dots`)) {
            _trackDotHandlers.add(`${srcId}-dots`);
            map.on('click', `${srcId}-dots`, e => {
                showPointPopup(e.features[0].properties, e.features[0].geometry.coordinates.slice());
            });
            map.on('mouseenter', `${srcId}-dots`, () => map.getCanvas().style.cursor = 'pointer');
            map.on('mouseleave', `${srcId}-dots`, () => map.getCanvas().style.cursor = '');
        }
    });

    if (autoFit && minLng !== Infinity) {
        map.fitBounds([[minLng, minLat], [maxLng, maxLat]], { padding: 60, maxZoom: 15, duration: 800 });
    }

    updateDetailControls();
}

function loadTrack(autoFit) {
    if (pendingFetch) { pendingFetch.abort(); }
    pendingFetch = new AbortController();

    showMapLoading('loading...');
    const qs = buildFilterParams();

    fetch(`/api/track/?${qs}`, { signal: pendingFetch.signal })
        .then(r => r.json())
        .then(data => {
            // Resolve road positions before the first paint, not after it. This
            // layer used to render at recorded coordinates and get corrected a
            // moment later, so every snappable dot visibly jumped on load. The
            // server-side movement gate rejects walking points without touching
            // the road table, so the wait scales with driving points only, and a
            // second visit to an area is served from cache.
            const ids = [];
            (data.devices || []).forEach(d => (d.points || []).forEach(p => {
                if (p.id !== null && p.id !== undefined) ids.push(p.id);
            }));
            if (snapOn && ids.length) showMapLoading('snapping to roads...');
            return fetchSnapsForIds(ids).then(() => data);
        })
        .then(data => {
            renderTrack(data.devices, autoFit);
            hideMapLoading();
            const total = data.devices.reduce((s, d) => s + d.total, 0);
            const shown = data.devices.reduce((s, d) => s + d.points.length, 0);
            const hidden = data.devices.reduce((s, d) => s + (d.hidden || 0), 0);
            const info = total !== shown
                ? `${total.toLocaleString()} pts · ${data.devices.length} device(s) · showing ${shown.toLocaleString()} (${hidden.toLocaleString()} hidden)`
                : `${total.toLocaleString()} points · ${data.devices.length} device(s)`;
            document.getElementById('map-info').textContent = info;
        })
        .catch(err => {
            if (err.name === 'AbortError') return;
            hideMapLoading();
            document.getElementById('map-info').textContent = 'error loading data';
            console.error(err);
        });
}

// ═══════════════════════════════════════════════════════════════════
// All time — instant overview, detail tiles as you zoom.
//
// The old All-time path walked the whole history server-side, thinned it to
// 4000 points a device (dropping exactly the one-off places), and then waited
// on road snapping before drawing anything. Instead:
//   - zoomed out, /api/map/overview/ returns every ~110m cell ever visited with
//     a point count, drawn at once as a heatmap + dots — one fix is enough for a
//     cell to show, so little places are never thinned away;
//   - from DETAIL_MIN_ZOOM, the PostGIS vector tiles (/api/tiles/) take over.
//     MapLibre loads and drops them per viewport by itself, and they are thinned
//     on a grid ~1/4 pixel wide, so every distinct spot is there at every zoom.
// Points sit at their recorded positions (no road snapping): faster, and the
// right thing for "which shop was that". Only on /map/ with PostGIS
// (ROAMLY_MAP_CFG.allTimeOverview); the editor and SQLite keep loadTrack.
// ═══════════════════════════════════════════════════════════════════

const OVERVIEW_ENABLED = !!ROAMLY_MAP_CFG.allTimeOverview;
const OVERVIEW_SRC = 'overview-src';
const OVERVIEW_HEAT_LAYER = 'overview-heat';
const OVERVIEW_DOTS_LAYER = 'overview-dots';
const ALLTIME_TILE_SRC = 'alltime-tiles';
const ALLTIME_DOTS_LAYER = 'alltime-dots';
const TILE_MAX_ZOOM = 16;   // the server draws z16 unthinned; deeper zooms overzoom it
let overviewData = null;    // last /api/map/overview/ response while All time is showing
let overviewFetch = null;

function isOverviewMode() {
    if (!OVERVIEW_ENABLED) return false;
    return new URLSearchParams(buildFilterParams()).get('all') === '1';
}

function overviewTileUrl(data) {
    const p = new URLSearchParams();
    p.set('all', '1');
    const dev = document.getElementById('device-filter').value;
    if (dev) p.set('device_id', dev);
    appendPlaceParams(p);
    // The data version: the URL changes whenever the data does, which is what
    // makes the long browser cache (and so prefetching) safe.
    p.set('v', String(data.version || 0));
    return `${location.origin}/api/tiles/{z}/{x}/{y}.pbf?${p.toString()}`;
}

function clearOverviewLayers() {
    if (!overviewData && !map.getSource(OVERVIEW_SRC) && !map.getSource(ALLTIME_TILE_SRC)) return;
    overviewData = null;
    if (overviewFetch) { overviewFetch.abort(); overviewFetch = null; }
    tilePrefetcher.reset(null);
    [OVERVIEW_HEAT_LAYER, OVERVIEW_DOTS_LAYER, ALLTIME_DOTS_LAYER].forEach(l => {
        if (map.getLayer(l)) map.removeLayer(l);
    });
    [OVERVIEW_SRC, ALLTIME_TILE_SRC].forEach(id => { if (map.getSource(id)) map.removeSource(id); });
}

function loadAllTimeOverview(autoFit) {
    if (pendingFetch) { pendingFetch.abort(); pendingFetch = null; }
    if (overviewFetch) overviewFetch.abort();
    overviewFetch = new AbortController();
    // The point-mode layers would otherwise sit underneath the overview.
    clearTrackLayers();
    clearTripLineLayers();
    lastTrackData = [];
    updateDetailControls();

    showMapLoading('loading...');
    const p = new URLSearchParams();
    const dev = document.getElementById('device-filter').value;
    if (dev) p.set('device_id', dev);
    appendPlaceParams(p);
    fetch(`/api/map/overview/?${p.toString()}`, { signal: overviewFetch.signal })
        .then(r => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
        .then(data => {
            overviewFetch = null;
            hideMapLoading();
            // The range changed while this was in flight.
            if (!isOverviewMode()) return;
            renderOverview(data, autoFit);
        })
        .catch(err => {
            if (err.name === 'AbortError') return;
            hideMapLoading();
            document.getElementById('map-info').textContent = 'error loading data';
            console.error(err);
        });
}

function renderOverview(data, autoFit) {
    overviewData = data;
    [OVERVIEW_HEAT_LAYER, OVERVIEW_DOTS_LAYER, ALLTIME_DOTS_LAYER].forEach(l => {
        if (map.getLayer(l)) map.removeLayer(l);
    });
    [OVERVIEW_SRC, ALLTIME_TILE_SRC].forEach(id => { if (map.getSource(id)) map.removeSource(id); });

    const d = data.per_deg || 1000;
    const cells = data.cells || [];
    const features = [];
    for (let i = 0; i < cells.length; i += 3) {
        features.push({
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [cells[i + 1] / d, cells[i] / d] },
            properties: { n: cells[i + 2] },
        });
    }
    map.addSource(OVERVIEW_SRC, { type: 'geojson', data: { type: 'FeatureCollection', features } });

    const D = DETAIL_MIN_ZOOM;
    if (showHeatmap === 'on') {
        map.addLayer({
            id: OVERVIEW_HEAT_LAYER,
            type: 'heatmap',
            source: OVERVIEW_SRC,
            maxzoom: D + 1,
            paint: {
                // Log weight: home has thousands of fixes, a one-off stop has one,
                // and both should read on the map.
                'heatmap-weight': ['interpolate', ['linear'], ['ln', ['+', 1, ['get', 'n']]], 0, 0.15, 8, 1],
                'heatmap-intensity': ['interpolate', ['linear'], ['zoom'], ...heatIntArgs],
                'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 0, 6, 13, 26],
                'heatmap-opacity': ['interpolate', ['linear'], ['zoom'], 0, 0.75, D - 1, 0.55, D + 0.75, 0],
                'heatmap-color': [
                    'interpolate', ['linear'], ['heatmap-density'],
                    0, 'rgba(33,102,172,0)',
                    0.2, 'rgb(103,169,207)',
                    0.4, 'rgb(209,229,240)',
                    0.6, 'rgb(253,219,199)',
                    0.8, 'rgb(239,138,98)',
                    1, 'rgb(178,24,43)'
                ]
            }
        });
    }
    // A dot for every visited cell, so a place with one fix is still visible
    // under the heatmap. Fades out as the exact tile dots fade in.
    map.addLayer({
        id: OVERVIEW_DOTS_LAYER,
        type: 'circle',
        source: OVERVIEW_SRC,
        maxzoom: D + 1,
        paint: {
            'circle-color': '#e8763d',
            'circle-radius': ['interpolate', ['linear'], ['zoom'], 0, 1.2, 6, 2.2, 10, 3.5],
            'circle-opacity': ['interpolate', ['linear'], ['zoom'], 0, 0.85, D, 0.85, D + 0.75, 0],
            'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 0, 0, 6, 0.5],
            'circle-stroke-color': 'rgba(255,255,255,0.6)',
            'circle-stroke-opacity': ['interpolate', ['linear'], ['zoom'], 0, 0.85, D, 0.85, D + 0.75, 0],
        }
    });

    map.addSource(ALLTIME_TILE_SRC, {
        type: 'vector',
        tiles: [overviewTileUrl(data)],
        minzoom: D,
        maxzoom: TILE_MAX_ZOOM,
    });
    // Per-device colour, matching every other layer on this page.
    const devIds = Array.from(document.getElementById('device-filter').options)
        .map(o => o.value).filter(Boolean);
    const colorExpr = devIds.length
        ? ['match', ['get', 'device_id'], ...devIds.flatMap(id => [id, deviceColor(id)]), deviceColors[0]]
        : deviceColors[0];
    map.addLayer({
        id: ALLTIME_DOTS_LAYER,
        type: 'circle',
        source: ALLTIME_TILE_SRC,
        'source-layer': 'locations',
        minzoom: D,
        filter: suspectFilter(),
        paint: {
            'circle-color': speedGradient === 'on' ? speedColorExpr(colorExpr) : colorExpr,
            'circle-radius': ['interpolate', ['linear'], ['zoom'], ...dotSizeArgs],
            'circle-stroke-width': 1,
            'circle-stroke-color': 'rgba(255,255,255,0.7)',
            'circle-opacity': ['interpolate', ['linear'], ['zoom'], D, 0, D + 0.5, 1],
            'circle-stroke-opacity': ['interpolate', ['linear'], ['zoom'], D, 0, D + 0.5, 1],
        }
    });
    bindOverviewHandlers();
    tilePrefetcher.reset(data);

    const n = features.length;
    document.getElementById('map-info').textContent = n
        ? `${n.toLocaleString()} places · all time · zoom in for every point`
        : 'no points yet';

    if (autoFit && data.bounds) {
        map.fitBounds(data.bounds, { padding: 60, maxZoom: 15, duration: 800 });
    }
}

let _overviewHandlersBound = false;
function bindOverviewHandlers() {
    if (_overviewHandlersBound) return;
    _overviewHandlersBound = true;
    map.on('click', ALLTIME_DOTS_LAYER, e => {
        const f = e.features[0];
        showPointPopup(f.properties, f.geometry.coordinates.slice());
    });
    map.on('mouseenter', ALLTIME_DOTS_LAYER, () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', ALLTIME_DOTS_LAYER, () => map.getCanvas().style.cursor = '');
}

// ── Predictive tile prefetch ──
// Zooming in should find its tiles already fetched. The overview says exactly
// where there is data, so a tile is only ever prefetched if it contains a
// visited cell — never empty ocean or countryside. Prefetched responses land in
// the browser's HTTP cache (private, max-age, versioned URL), so MapLibre's own
// request for the same tile never leaves the browser.
const tilePrefetcher = (() => {
    const MAX_INFLIGHT = 2;
    const MAX_QUEUE = 48;
    const BUCKETS_PER_DEG = 10;
    let urlTpl = null;
    let buckets = null;       // Set of "by:bx" 0.1° buckets that contain a visited cell
    let queue = [];
    let inflight = 0;
    const requested = new Set();

    function saveData() {
        const c = navigator.connection;
        return !!(c && c.saveData);
    }

    function reset(data) {
        queue = [];
        requested.clear();
        urlTpl = null;
        buckets = null;
        if (!data || !data.cells || !data.cells.length) return;
        urlTpl = overviewTileUrl(data);
        buckets = new Set();
        const d = data.per_deg || 1000;
        for (let i = 0; i < data.cells.length; i += 3) {
            buckets.add(`${Math.floor(data.cells[i] / d * BUCKETS_PER_DEG)}:${Math.floor(data.cells[i + 1] / d * BUCKETS_PER_DEG)}`);
        }
    }

    function tileBounds(z, x, y) {
        const n = 2 ** z;
        const lng = v => v / n * 360 - 180;
        const lat = v => Math.atan(Math.sinh(Math.PI * (1 - 2 * v / n))) * 180 / Math.PI;
        return { w: lng(x), e: lng(x + 1), n: lat(y), s: lat(y + 1) };
    }

    function tileHasData(z, x, y) {
        const b = tileBounds(z, x, y);
        const y0 = Math.floor(b.s * BUCKETS_PER_DEG), y1 = Math.floor(b.n * BUCKETS_PER_DEG);
        const x0 = Math.floor(b.w * BUCKETS_PER_DEG), x1 = Math.floor(b.e * BUCKETS_PER_DEG);
        if ((y1 - y0 + 1) * (x1 - x0 + 1) > 4000) return true;  // huge tile: not worth checking
        for (let by = y0; by <= y1; by++) {
            for (let bx = x0; bx <= x1; bx++) {
                if (buckets.has(`${by}:${bx}`)) return true;
            }
        }
        return false;
    }

    function tileAt(lng, lat, z) {
        const n = 2 ** z;
        lat = Math.max(-85.05, Math.min(85.05, lat));
        const x = Math.floor((lng + 180) / 360 * n);
        const r = lat * Math.PI / 180;
        const y = Math.floor((1 - Math.asinh(Math.tan(r)) / Math.PI) / 2 * n);
        return [Math.max(0, Math.min(n - 1, x)), Math.max(0, Math.min(n - 1, y))];
    }

    function enqueue(z, x, y, front) {
        if (!urlTpl || z < DETAIL_MIN_ZOOM || z > TILE_MAX_ZOOM) return;
        const n = 2 ** z;
        if (x < 0 || y < 0 || x >= n || y >= n) return;
        const url = urlTpl.replace('{z}', z).replace('{x}', x).replace('{y}', y);
        if (requested.has(url) || !tileHasData(z, x, y)) return;
        requested.add(url);
        if (front) queue.unshift(url); else queue.push(url);
        if (queue.length > MAX_QUEUE) queue.length = MAX_QUEUE;
        pump();
    }

    function pump() {
        while (inflight < MAX_INFLIGHT && queue.length) {
            const url = queue.shift();
            inflight++;
            fetch(url, { priority: 'low', credentials: 'same-origin' })
                .then(r => r.arrayBuffer())
                .catch(() => requested.delete(url))
                .finally(() => { inflight--; pump(); });
        }
    }

    /** Tiles around (lng, lat) at zoom `z`: the one under it plus `ring` neighbours each way. */
    function around(lng, lat, z, ring, front) {
        const [cx, cy] = tileAt(lng, lat, z);
        for (let dy = -ring; dy <= ring; dy++) {
            for (let dx = -ring; dx <= ring; dx++) enqueue(z, cx + dx, cy + dy, front);
        }
    }

    /** The zoom levels the next zoom-in from the current one will land on. */
    function nextZooms(extra) {
        const z = Math.floor(map.getZoom());
        const first = Math.max(DETAIL_MIN_ZOOM, z + 1);
        const out = [];
        for (let i = 0; i < extra; i++) if (first + i <= TILE_MAX_ZOOM) out.push(first + i);
        return out;
    }

    function predictAtPoint(lngLat, levels, front) {
        if (!urlTpl || saveData()) return;
        nextZooms(levels).forEach((z, i) => around(lngLat.lng, lngLat.lat, z, i === 0 ? 1 : 0, front));
    }

    // A wheel/pinch/double-click zoom heads for the pointer, so the pointer is
    // where the next tiles will be needed.
    const canvas = map.getCanvasContainer();
    canvas.addEventListener('wheel', e => {
        if (e.deltaY >= 0 || !urlTpl) return;
        predictAtPoint(map.unproject([e.offsetX, e.offsetY]), 2, true);
    }, { passive: true });
    canvas.addEventListener('dblclick', e => {
        predictAtPoint(map.unproject([e.offsetX, e.offsetY]), 2, true);
    });
    canvas.addEventListener('touchstart', e => {
        if (e.touches.length !== 2 || !urlTpl) return;
        const r = canvas.getBoundingClientRect();
        const mx = (e.touches[0].clientX + e.touches[1].clientX) / 2 - r.left;
        const my = (e.touches[0].clientY + e.touches[1].clientY) / 2 - r.top;
        predictAtPoint(map.unproject([mx, my]), 2, true);
    }, { passive: true });

    // Resting the mouse on a spot is the clearest "I'm about to zoom in here".
    let hoverTimer = null;
    canvas.addEventListener('mousemove', e => {
        if (hoverTimer) clearTimeout(hoverTimer);
        if (!urlTpl) return;
        const pt = [e.offsetX, e.offsetY];
        hoverTimer = setTimeout(() => predictAtPoint(map.unproject(pt), 3, true), 350);
    });
    canvas.addEventListener('mouseleave', () => { if (hoverTimer) clearTimeout(hoverTimer); });

    // After every move, the middle of the screen at the next zoom level.
    map.on('idle', () => {
        if (!urlTpl || saveData()) return;
        const c = map.getCenter();
        const z = nextZooms(1)[0];
        if (z === undefined) return;
        around(c.lng, c.lat, z, 1, false);
    });

    return { reset };
})();

// ═══════════════════════════════════════════════════════════════════
// Trip Lines mode — selective, road-snapped travel lines + dwell blobs.
//
// A Google-Timeline-style alternative to points mode, not an overlay on top
// of it: when active, this replaces the decimated track + viewport detail
// layers entirely with a single whole-range fetch from /api/track/lines/,
// which already did the point selection and road-snapping server-side (see
// trip_lines_api in views.py). Client work is just drawing what came back and
// handling the dwell-blob "click to expand" interaction.
// ═══════════════════════════════════════════════════════════════════

const TRIP_LINE_SOURCE = 'trip-lines-src';
const TRIP_LINE_LAYER = 'trip-lines-layer';
const TRIP_DWELL_SOURCE = 'trip-dwells-src';
const TRIP_DWELL_LAYER = 'trip-dwells-layer';
const TRIP_EXPAND_SOURCE = 'trip-dwell-expand-src';
const TRIP_EXPAND_LINE_LAYER = 'trip-dwell-expand-lines';
const TRIP_EXPAND_DOT_LAYER = 'trip-dwell-expand-dots';

// Web Mercator ground resolution at zoom 0, equator (m/px) — the same constant
// map.html's fog-of-war canvas overlay uses to convert a real-world radius to
// screen pixels at the current zoom.
//
// A blob's true pixel radius is `radius_m / mPerPx(lat, zoom)`, which doubles
// every zoom level, floored at DWELL_MIN_PX so a small stay still reads as a
// dot from far away instead of vanishing below one pixel. Expressing that
// directly as ['max', MIN, ['interpolate', ..., ['zoom'], ...]] is INVALID:
// MapLibre requires ['zoom'] to be the input of a *top-level* step/interpolate,
// and nesting it inside ['max'] makes addLayer throw — which silently killed
// the entire dwell layer. So the floor is instead baked into per-feature stop
// values at every other zoom level: below the crossover both endpoints of an
// interval are MIN (so it interpolates flat), above it they're exact powers of
// two (so ['exponential', 2] reproduces the true curve exactly). Only the one
// interval straddling the crossover is approximate, by well under a pixel.
const MERC_RES_Z0 = 156543.03392;
const DWELL_MIN_PX = 7;
const DWELL_MAX_ZOOM_STOP = 20;
const DWELL_ZOOM_STEP = 2;
const DWELL_ZOOM_STOPS = (() => {
    const zs = [];
    for (let z = 0; z <= DWELL_MAX_ZOOM_STOP; z += DWELL_ZOOM_STEP) zs.push(z);
    return zs;
})();

let tripLineData = null;    // last-fetched devices[], kept for style-swap re-render
let expandedDwellId = null; // visit id currently expanded to its raw point cloud, or null

function dwellFeature(d, deviceId, color) {
    const pxZ0 = d.radius_m / (MERC_RES_Z0 * Math.cos(d.lat * Math.PI / 180));
    const props = {
        id: d.id, device_id: deviceId, color,
        radius_m: d.radius_m, start: d.start, end: d.end,
        duration_s: d.duration_s, point_count: d.point_count,
        place_name: d.place_name || '',
    };
    // One pre-floored radius per zoom stop — see DWELL_ZOOM_STOPS above.
    DWELL_ZOOM_STOPS.forEach(z => {
        props['px_z' + z] = Math.max(DWELL_MIN_PX, pxZ0 * Math.pow(2, z));
    });
    return {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [d.lng, d.lat] },
        properties: props,
    };
}

// ['interpolate', ['exponential', 2], ['zoom'], 0, ['get','px_z0'], 2, ...]
function dwellRadiusExpr() {
    const expr = ['interpolate', ['exponential', 2], ['zoom']];
    DWELL_ZOOM_STOPS.forEach(z => { expr.push(z, ['get', 'px_z' + z]); });
    return expr;
}

function clearTripLineLayers() {
    collapseDwellExpand();
    [TRIP_DWELL_LAYER, TRIP_LINE_LAYER].forEach(id => { if (map.getLayer(id)) map.removeLayer(id); });
    [TRIP_DWELL_SOURCE, TRIP_LINE_SOURCE].forEach(id => { if (map.getSource(id)) map.removeSource(id); });
}

function renderTripLines(devices, autoFit) {
    tripLineData = devices || [];
    clearTripLineLayers();
    if (!devices || !devices.length) return;

    const lineFeatures = [];
    const dwellFeatures = [];
    let minLng = Infinity, maxLng = -Infinity, minLat = Infinity, maxLat = -Infinity;
    const extend = (lng, lat) => {
        if (lng < minLng) minLng = lng;
        if (lng > maxLng) maxLng = lng;
        if (lat < minLat) minLat = lat;
        if (lat > maxLat) maxLat = lat;
    };

    devices.forEach(device => {
        const color = deviceColor(device.id);
        (device.segments || []).forEach(seg => {
            if (!seg.coords || seg.coords.length < 2) return;
            lineFeatures.push({
                type: 'Feature',
                geometry: { type: 'LineString', coordinates: seg.coords },
                properties: { color, snapped: !!seg.snapped }
            });
            seg.coords.forEach(c => extend(c[0], c[1]));
        });
        if (dwellBlobsOn) {
            (device.dwells || []).forEach(d => {
                dwellFeatures.push(dwellFeature(d, device.id, color));
                extend(d.lng, d.lat);
            });
        }
    });

    map.addSource(TRIP_LINE_SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: lineFeatures } });
    map.addLayer({
        id: TRIP_LINE_LAYER,
        type: 'line',
        source: TRIP_LINE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
            'line-color': ['get', 'color'],
            'line-width': ['case', ['==', ['get', 'snapped'], true], 4, 3],
            'line-opacity': 0.8,
        }
    });

    if (dwellBlobsOn) {
        map.addSource(TRIP_DWELL_SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features: dwellFeatures } });
        map.addLayer({
            id: TRIP_DWELL_LAYER,
            type: 'circle',
            source: TRIP_DWELL_SOURCE,
            paint: {
                // True-to-scale in metres once zoomed in close, but never smaller
                // than DWELL_MIN_PX — that's what keeps a stop "visible and
                // readable from a distance" at a low, zoomed-out view.
                'circle-radius': dwellRadiusExpr(),
                'circle-color': ['get', 'color'],
                'circle-opacity': 0.28,
                'circle-stroke-width': 2,
                'circle-stroke-color': ['get', 'color'],
                'circle-stroke-opacity': 0.9,
            }
        });
    }

    if (autoFit && minLng !== Infinity) {
        map.fitBounds([[minLng, minLat], [maxLng, maxLat]], { padding: 60, maxZoom: 15, duration: 800 });
    }
}

function loadTripLines(autoFit) {
    if (pendingFetch) { pendingFetch.abort(); }
    pendingFetch = new AbortController();

    showMapLoading('loading...');
    const gapMinutes = Math.round(lineGapMs / 60000);
    const qs = `${buildFilterParams()}&gap_minutes=${gapMinutes}`
        + `&dwell_min_minutes=${dwellMinMinutes}&dwell_min_points=${dwellMinPoints}&dwell_radius_m=${dwellRadiusM}`;

    fetch(`/api/track/lines/?${qs}`, { signal: pendingFetch.signal })
        .then(r => r.json())
        .then(data => {
            renderTripLines(data.devices, autoFit);
            hideMapLoading();
            const devices = data.devices || [];
            const segs = devices.reduce((s, d) => s + (d.segments || []).length, 0);
            const stops = devices.reduce((s, d) => s + (d.dwells || []).length, 0);
            const routed = devices.reduce(
                (s, d) => s + (d.segments || []).filter(x => x.routed).length, 0);
            // Say why the lines aren't following roads rather than leaving the
            // user to guess: the master toggle is off, or nothing resolved a
            // provider (no road data downloaded / no token / no OSRM url).
            let roadNote;
            // Name the exact control that fixes each case — "roads: off" alone
            // sent people to the sidebar button, which is a different switch.
            if (!data.snap_enabled) roadNote = 'roads: off (Settings → Road Snapping)';
            else if (!data.snap_provider) roadNote = 'roads: no data (ask an admin to download road data)';
            else roadNote = `roads: ${data.snap_provider}${routed ? ` · ${routed} routed` : ' · none matched'}`;
            document.getElementById('map-info').textContent =
                `${segs.toLocaleString()} line segment(s) · ${stops.toLocaleString()} stop(s) · ${roadNote}`;
        })
        .catch(err => {
            if (err.name === 'AbortError') return;
            hideMapLoading();
            document.getElementById('map-info').textContent = 'error loading data';
            console.error(err);
        });
}

// ── Dwell-blob click to expand into its raw point cloud ──
function formatDwellDuration(seconds) {
    const mins = Math.round(seconds / 60);
    if (mins < 60) return `${mins} min`;
    const hrs = mins / 60;
    return `${hrs >= 10 ? Math.round(hrs) : hrs.toFixed(1)} hr`;
}

function showDwellPopup(p, coords) {
    const expanded = String(expandedDwellId) === String(p.id);
    let html = `<strong>${p.place_name || 'Stop'}</strong>`;
    html += `<br><span style="color:var(--text-muted)">${formatDwellDuration(p.duration_s)} · `
          + `${Number(p.point_count).toLocaleString()} points</span>`;
    if (p.start) html += `<br><span style="color:var(--text-muted)">${new Date(p.start).toLocaleString()}</span>`;
    html += `<br><span style="color:var(--text-muted)">click ${expanded ? 'again to collapse' : 'to see the raw track'}</span>`;
    openPointPopup(coords, html);
}

function collapseDwellExpand() {
    expandedDwellId = null;
    [TRIP_EXPAND_LINE_LAYER, TRIP_EXPAND_DOT_LAYER].forEach(id => { if (map.getLayer(id)) map.removeLayer(id); });
    if (map.getSource(TRIP_EXPAND_SOURCE)) map.removeSource(TRIP_EXPAND_SOURCE);
}

function renderDwellExpand(points, color) {
    [TRIP_EXPAND_LINE_LAYER, TRIP_EXPAND_DOT_LAYER].forEach(id => { if (map.getLayer(id)) map.removeLayer(id); });
    if (map.getSource(TRIP_EXPAND_SOURCE)) map.removeSource(TRIP_EXPAND_SOURCE);
    if (!points || !points.length) return;

    const features = [{
        type: 'Feature',
        geometry: { type: 'LineString', coordinates: points.map(p => [p.lng, p.lat]) },
        properties: { color }
    }];
    points.forEach(p => features.push({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [p.lng, p.lat] },
        properties: { color, ts: p.ts, speed: p.speed ?? null }
    }));

    map.addSource(TRIP_EXPAND_SOURCE, { type: 'geojson', data: { type: 'FeatureCollection', features } });
    map.addLayer({
        id: TRIP_EXPAND_LINE_LAYER,
        type: 'line',
        source: TRIP_EXPAND_SOURCE,
        filter: ['==', ['geometry-type'], 'LineString'],
        paint: { 'line-color': ['get', 'color'], 'line-width': 1.5, 'line-opacity': 0.55 }
    });
    map.addLayer({
        id: TRIP_EXPAND_DOT_LAYER,
        type: 'circle',
        source: TRIP_EXPAND_SOURCE,
        filter: ['==', ['geometry-type'], 'Point'],
        paint: {
            'circle-radius': 3,
            'circle-color': ['get', 'color'],
            'circle-stroke-width': 1,
            'circle-stroke-color': 'rgba(255,255,255,0.8)',
        }
    });
}

function onDwellClick(e) {
    const f = e.features[0];
    const p = f.properties;
    const coords = f.geometry.coordinates.slice();

    if (String(expandedDwellId) === String(p.id)) {
        collapseDwellExpand();
        showDwellPopup(p, coords);
        return;
    }
    expandedDwellId = p.id;
    showDwellPopup(p, coords);

    // Dwells are detected per request and never stored, so the window itself is
    // the key rather than a row id.
    const q = new URLSearchParams({
        device_id: p.device_id,
        start: String(Math.floor(new Date(p.start).getTime() / 1000)),
        end: String(Math.ceil(new Date(p.end).getTime() / 1000)),
    });
    fetch(`/api/dwell/points/?${q.toString()}`)
        .then(r => r.json())
        .then(data => {
            // Toggled off, or a different blob clicked, while this was in flight.
            if (String(expandedDwellId) !== String(p.id)) return;
            renderDwellExpand(data.points || [], p.color);
        })
        .catch(err => console.error('Failed to load dwell points:', err));
}

// Registered once, unconditionally: TRIP_DWELL_LAYER is a fixed layer id that
// renderTripLines only ever removes and re-adds (never renamed), so a single
// delegated listener here survives every re-render without stacking — unlike
// re-registering inside renderTripLines itself, which would fire once per
// past render on every click.
if (TRIP_LINES_ENABLED) {
    map.on('click', TRIP_DWELL_LAYER, onDwellClick);
    map.on('mouseenter', TRIP_DWELL_LAYER, () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', TRIP_DWELL_LAYER, () => map.getCanvas().style.cursor = '');
}

// ═══════════════════════════════════════════════════════════════════
// Init + filter handlers
// ═══════════════════════════════════════════════════════════════════

(function() {
    const p = new URLSearchParams(window.location.search);
    PLACE_FIELDS.forEach(f => { if (p.get(f)) placeFilter[f] = p.get(f); });
    if (Object.keys(placeFilter).length) {
        // A place link means "everything I did there", so start from all time;
        // an explicit start/end/date below still narrows it.
        document.getElementById('time-range').value = 'all';
        const clearBtn = document.getElementById('place-filter-clear');
        if (clearBtn) clearBtn.addEventListener('click', clearPlaceFilter);
    }
    renderPlaceFilter();
    // ?range=all|<hours>: a link that zooms somewhere without naming a day
    // (Achievements' regulars, counties) wants every point there, not 24h.
    const range = p.get('range');
    const rangeSel = document.getElementById('time-range');
    if (range && [...rangeSel.options].some(o => o.value === range)) rangeSel.value = range;
    if (p.get('start') && p.get('end')) {
        document.getElementById('start-date').value = p.get('start');
        document.getElementById('end-date').value = p.get('end');
        document.getElementById('time-range').value = 'all';
    }
    if (p.get('date')) {
        const d = p.get('date');
        document.getElementById('start-date').value = d;
        document.getElementById('end-date').value = d;
        document.getElementById('time-range').value = 'all';
    }
    if (p.get('selected')) {
        window.__selectedFromURL = p.get('selected');
        window.__selectedDateFromURL = p.get('date') || null;
    }
    const at = p.get('at') ? new Date(p.get('at')) : null;
    if (at && !isNaN(at)) {
        const lat = parseFloat(p.get('lat')), lng = parseFloat(p.get('lng'));
        focusMoment = {
            t: at.getTime(),
            lat: isNaN(lat) ? null : lat, lng: isNaN(lng) ? null : lng,
            zoom: parseInt(p.get('zoom') || '16'),
        };
        if (!p.get('date') && !(p.get('start') && p.get('end'))) {
            const d = localDateStr(at);
            document.getElementById('start-date').value = d;
            document.getElementById('end-date').value = d;
            document.getElementById('time-range').value = 'all';
        }
    } else if (p.get('lat') && p.get('lng')) {
        urlCentred = true;
        const lat = parseFloat(p.get('lat'));
        const lng = parseFloat(p.get('lng'));
        const zoom = parseInt(p.get('zoom') || '16');
        map.once('load', () => setTimeout(() => map.flyTo({ center: [lng, lat], zoom }), 500));
    }
    updateDateFilterUI();
})();

map.on('load', () => {
    // A focused moment does its own camera move; fitting the day would undo it.
    loadCurrentMode(!focusMoment && !urlCentred);
    if (focusMoment) focusOnMoment(focusMoment);
    if (window.__selectedFromURL) {
        fetchAndFocusSelected(window.__selectedFromURL, window.__selectedDateFromURL);
    }
});

map.on('zoomend', () => updateDetailControls());

map.on('moveend', () => {
    if (moveDebounce) clearTimeout(moveDebounce);
    moveDebounce = setTimeout(() => {
        moveDebounce = null;
        maybeLoadViewport();
    }, 400);
});

document.getElementById('map-load-more-btn').addEventListener('click', () => {
    loadViewport(MORE_LOAD_LIMIT);
});

['time-range', 'device-filter'].forEach(id => {
    document.getElementById(id).addEventListener('change', () => {
        updateDateFilterUI();
        clearAccumulated();
        loadCurrentMode(true);
    });
});

['start-date', 'end-date'].forEach(id => {
    document.getElementById(id).addEventListener('change', () => {
        const s = document.getElementById('start-date').value;
        const e = document.getElementById('end-date').value;
        if (e && !s) return;            // end without a start: nothing to anchor
        if (s && e) {                   // exact range overrides the relative dropdown
            document.getElementById('time-range').value = 'all';
        }
        updateDateFilterUI();
        clearAccumulated();
        loadCurrentMode(true);
    });
});

document.getElementById('date-reset').addEventListener('click', resetDateFilter);


// ═══════════════════════════════════════════════════════════════════
// Snapped view — display only, this browser only.
//
// Two different controls used to be called "snap to roads", which is the whole
// reason snapping looked broken: this button (the sidebar's `snapped view`,
// backed by localStorage `roamly_snap_roads`) only decides whether snapped
// positions are *shown* on this page in this browser. The account-wide master
// switch is UserProfile.snap_to_roads (Settings -> Road Snapping) — with that
// off, /api/roads/snap/ returns nothing and this button has nothing to show,
// and ROAD_SNAP_ENABLED means the button isn't even rendered.
//
// The recorded latitude/longitude in the database are never touched: the server
// works out the nearest road position, caches it per point id, and hands it back
// for rendering. Toggling off restores the recorded coordinates from rawCoords,
// so the raw track is always one click away.
//
// Points are snapped BEFORE they are ever drawn, and the result is remembered in
// snapCache for the life of the page. That matters because panning re-fetches
// overlapping points as fresh raw features: painting first and correcting later
// made already-settled dots jump back to their recorded position and then snap
// again on every pan. A point id's road position is immutable, so it is resolved
// once, cached client-side (on top of the server's own 7-day cache) and applied
// to every later copy of that point before it reaches the map.
//
// Only the detail (accMap) layer snaps. The decimated /api/track/ layer only
// shows below DETAIL_MIN_ZOOM, where a 10m correction is well under a pixel.
// ═══════════════════════════════════════════════════════════════════
const SNAP_AVAILABLE = !!ROAMLY_MAP_CFG.snapAvailable;
const SNAP_BATCH = 2000;          // ids per request; the server caps at 5000
const snapBtn = document.getElementById('snap-btn');
const snapBadge = document.getElementById('snap-badge');
let snapOn = SNAP_AVAILABLE && (localStorage.getItem('roamly_snap_roads') || 'on') === 'on';
// id → [lng,lat], or null for "asked, no road within tolerance". Deliberately
// never cleared: a recorded fix and the road beside it are both immutable, so
// this stays valid across pans, zooms and filter changes.
const snapCache = new Map();

function updateSnapBadge() {
    if (!snapBadge) return;
    if (!snapOn) { snapBadge.textContent = ''; return; }
    let n = 0;
    accMap.forEach(f => { if (f.properties.snapped) n++; });
    snapBadge.textContent = n ? `${n.toLocaleString()} snapped` : 'on';
}

// Move features onto their cached road position. Mutates in place, so callers
// must do this *before* handing the features to mergeAccFeatures.
function applyKnownSnaps(features) {
    if (!snapOn) return 0;
    let n = 0;
    features.forEach(f => {
        const id = f.properties.id;
        const c = snapCache.get(id);
        if (!c) return;                       // absent, or a cached miss
        if (!rawCoords.has(id)) rawCoords.set(id, f.geometry.coordinates.slice());
        f.geometry.coordinates = [c[0], c[1]];
        f.properties.snapped = true;
        n++;
    });
    return n;
}

// Resolve any ids we haven't asked about yet. Resolves once every batch has
// landed, so the caller can paint a fully-settled set of points.
function fetchSnapsFor(features) {
    if (!features || !features.length) return Promise.resolve();
    return fetchSnapsForIds(features.map(f => f.properties.id));
}

function fetchSnapsForIds(rawIds) {
    if (!snapOn || !rawIds || !rawIds.length) return Promise.resolve();
    const ids = rawIds.filter(v => v !== null && v !== undefined && !snapCache.has(v));
    if (!ids.length) return Promise.resolve();

    const batches = [];
    for (let i = 0; i < ids.length; i += SNAP_BATCH) batches.push(ids.slice(i, i + SNAP_BATCH));

    return Promise.all(batches.map(batch =>
        fetch('/api/roads/snap/', {
            method: 'POST',
            headers: { 'X-CSRFToken': csrfToken, 'Content-Type': 'application/json' },
            body: JSON.stringify({ ids: batch }),
        })
            .then(r => r.json())
            .then(d => {
                if (d.error) { console.warn('Snap to roads:', d.error); return; }
                const snapped = d.snapped || {};
                batch.forEach(id => {
                    const c = snapped[String(id)];
                    // Cache misses too, as null, so a point with no road nearby
                    // isn't re-requested on every single pan.
                    snapCache.set(id, c ? [c[0], c[1]] : null);
                });
            })
            .catch(() => {})
    ));
}

function repaintTrackSnaps() {
    if (!snapOn) return;
    trackFeatures.forEach((features, srcId) => {
        if (!applyKnownSnaps(features)) return;
        const src = map.getSource(srcId);
        if (src) src.setData({ type: 'FeatureCollection', features });
    });
}

function unsnapAcc() {
    let n = 0;
    accMap.forEach(f => {
        const raw = rawCoords.get(f.properties.id);
        if (raw) {
            f.geometry.coordinates = raw.slice();
            delete f.properties.snapped;
            n++;
        }
    });
    trackFeatures.forEach((features, srcId) => {
        let m = 0;
        features.forEach(f => {
            const raw = rawCoords.get(f.properties.id);
            if (raw) { f.geometry.coordinates = raw.slice(); delete f.properties.snapped; m++; }
        });
        const src = map.getSource(srcId);
        if (m && src) src.setData({ type: 'FeatureCollection', features });
    });
    // rawCoords and snapCache are kept: toggling back on must not re-query.
    updateSnapBadge();
    if (n) repaintAcc();
}

if (snapBtn) {
    snapBtn.classList.toggle('active', snapOn);
    updateSnapBadge();
    snapBtn.addEventListener('click', () => {
        snapOn = !snapOn;
        localStorage.setItem('roamly_snap_roads', snapOn ? 'on' : 'off');
        snapBtn.classList.toggle('active', snapOn);
        if (snapOn) {
            const loaded = Array.from(accMap.values());
            fetchSnapsFor(loaded).then(() => {
                if (!snapOn) return;          // toggled off again mid-flight
                applyKnownSnaps(loaded);
                repaintTrackSnaps();
                updateSnapBadge();
                repaintAcc();
            });
        } else {
            unsnapAcc();
        }
    });
}

