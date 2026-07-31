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

const mapStyles = {
    'Streets': {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
            'osm': {
                type: 'raster',
                tiles: ['https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'],
                tileSize: 256,
                attribution: '&copy; OpenStreetMap contributors &copy; CARTO'
            }
        },
        layers: [{ id: 'osm', type: 'raster', source: 'osm' }]
    },
    'Dark': {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
            'carto': {
                type: 'raster',
                tiles: ['https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png'],
                tileSize: 256,
                attribution: '&copy; CartoDB'
            }
        },
        layers: [{ id: 'carto', type: 'raster', source: 'carto' }]
    },
    'Satellite': {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
            'esri': {
                type: 'raster',
                tiles: ['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],
                tileSize: 256,
                maxzoom: 19,
                attribution: '&copy; Esri'
            }
        },
        layers: [{ id: 'esri', type: 'raster', source: 'esri' }]
    }
};

// Optional Mapbox basemaps — only added when the user has a public token saved
// on their account (Settings; served here via MAPBOX_TOKEN). Uses Mapbox raster
// tiles so it slots into the same style-8 raster pattern as the built-in basemaps.
// The built-in free basemaps above are always available regardless.
const _mapboxToken = (ROAMLY_MAP_CFG.mapboxToken || '').trim();
function _mapboxRasterStyle(styleId, attribution) {
    return {
        version: 8,
        glyphs: 'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',
        sources: {
            'mapbox': {
                type: 'raster',
                tiles: ['https://api.mapbox.com/styles/v1/mapbox/' + styleId +
                        '/tiles/256/{z}/{x}/{y}?access_token=' + encodeURIComponent(_mapboxToken)],
                tileSize: 256,
                maxzoom: 22,
                attribution: attribution
            }
        },
        layers: [{ id: 'mapbox', type: 'raster', source: 'mapbox' }]
    };
}
if (_mapboxToken) {
    const _mbAttr = '&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a> &copy; OpenStreetMap contributors';
    mapStyles['Mapbox Streets'] = _mapboxRasterStyle('streets-v12', _mbAttr);
    mapStyles['Mapbox Outdoors'] = _mapboxRasterStyle('outdoors-v12', _mbAttr);
    mapStyles['Mapbox Satellite'] = _mapboxRasterStyle('satellite-streets-v12', _mbAttr);
    // Give the Mapbox basemaps their own labelled group under the built-in one.
    // Appending them to the built-in grid crushed them into unreadable slivers —
    // the sidebar is 220px, so a shared row leaves ~60px per button and "mb
    // outdoors" simply does not fit. Their own group means the same three
    // one-word labels as above, and the "mapbox" sublabel carries the prefix.
    // Injected before the button wiring below runs, so they pick up the same
    // click behaviour from the global .style-btn query.
    const _basemapSection = document.getElementById('basemap-section');
    if (_basemapSection) {
        const _mbLabel = document.createElement('div');
        _mbLabel.className = 'sidebar-sublabel';
        _mbLabel.textContent = 'mapbox';
        _basemapSection.appendChild(_mbLabel);

        const _mbGroup = document.createElement('div');
        _mbGroup.className = 'sidebar-btn-group';
        [['Mapbox Streets', 'streets'], ['Mapbox Outdoors', 'outdoors'],
         ['Mapbox Satellite', 'sat']].forEach(([style, label]) => {
            const btn = document.createElement('button');
            btn.className = 'style-btn';
            btn.dataset.style = style;
            btn.textContent = label;
            btn.title = style;
            _mbGroup.appendChild(btn);
        });
        _basemapSection.appendChild(_mbGroup);
    }
}

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

// Fall back to Streets if the saved style is unavailable (e.g. a Mapbox basemap
// was chosen, then the token was later removed in Settings).
const _rawSavedTileStyle = localStorage.getItem('roamly_map_tile_style') || 'Streets';
const _savedTileStyle = mapStyles[_rawSavedTileStyle] ? _rawSavedTileStyle : 'Streets';

const map = new maplibregl.Map({
    container: 'map',
    style: mapStyles[_savedTileStyle],
    center: [0, 20],
    zoom: 2,
});

map.addControl(new maplibregl.NavigationControl(), 'bottom-right');

// Surface Mapbox tile auth failures instead of showing a silent blank map.
// A correct token returns tiles; a 401/403 here almost always means the token
// is invalid or has URL restrictions that exclude this site's domain.
let _mapboxErrorShown = false;
map.on('error', (e) => {
    const url = e && e.error && (e.error.url || '');
    const status = e && e.error && e.error.status;
    const isMapbox = (e && e.sourceId === 'mapbox') || (typeof url === 'string' && url.indexOf('api.mapbox.com') !== -1);
    if (!isMapbox) return;
    console.warn('Mapbox tile error', status || '', url || '', e && e.error);
    if (!_mapboxErrorShown) {
        _mapboxErrorShown = true;
        document.getElementById('map-info').textContent =
            'Mapbox tiles failed to load — check your token and its URL restrictions in Settings.';
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

function showPointPopup(pt, coords) {
    const ts = new Date(pt.ts * 1000);
    let html = `<strong>${pt.device_name || 'Unknown'}</strong><br>`;
    html += `<span style="color:var(--text-muted)">${ts.toLocaleString()}</span>`;
    if (pt.city)    html += `<br>${pt.city}`;
    if (pt.state)   html += `, ${pt.state}`;
    if (pt.country) html += `<br>${pt.country}`;
    const spd = formatSpeed(pt.speed);
    if (spd) html += `<br><span style="color:var(--text-muted)">${spd}</span>`;
    new maplibregl.Popup({ offset: 10 }).setLngLat(coords).setHTML(html).addTo(map);
}

// ── Filter params ──
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
    loadTrack(true);
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
        new maplibregl.Popup({ offset: 10 })
            .setLngLat(e.features[0].geometry.coordinates.slice())
            .setHTML(html)
            .addTo(map);
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

        map.on('click', `${srcId}-dots`, e => {
            showPointPopup(e.features[0].properties, e.features[0].geometry.coordinates.slice());
        });
        map.on('mouseenter', `${srcId}-dots`, () => map.getCanvas().style.cursor = 'pointer');
        map.on('mouseleave', `${srcId}-dots`, () => map.getCanvas().style.cursor = '');
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
// Init + filter handlers
// ═══════════════════════════════════════════════════════════════════

(function() {
    const p = new URLSearchParams(window.location.search);
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
    if (p.get('lat') && p.get('lng')) {
        const lat = parseFloat(p.get('lat'));
        const lng = parseFloat(p.get('lng'));
        const zoom = parseInt(p.get('zoom') || '16');
        map.once('load', () => setTimeout(() => map.flyTo({ center: [lng, lat], zoom }), 500));
    }
    updateDateFilterUI();
})();

map.on('load', () => {
    loadTrack(true);
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
        loadTrack(true);
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
        loadTrack(true);
    });
});

document.getElementById('date-reset').addEventListener('click', resetDateFilter);


// ═══════════════════════════════════════════════════════════════════
// Snap to roads — display only.
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

