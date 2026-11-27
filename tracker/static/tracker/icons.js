/*
 * Roamly line icons — one small stroke set shared by pages that used to lean on
 * emoji (Achievements, Health). Every glyph is drawn on a 24×24 grid with
 * currentColor, so it inherits the theme's text colour and the active shape
 * facet never has to be told about it.
 *
 *   RoamlyIcons.svg('globe')        -> '<svg …>…</svg>' string
 *   RoamlyIcons.svg('globe', 20)    -> sized in px
 *   RoamlyIcons.names               -> every available name
 */
(function () {
    var P = {
        globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18"/>',
        flag: '<path d="M5 21V4M5 4h11l-2 4 2 4H5"/>',
        compass: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>',
        pin: '<path d="M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0113 0c0 5.4-6.5 11-6.5 11z"/><circle cx="12" cy="10" r="2.3"/>',
        map: '<path d="M3 6.5l6-2.5 6 2.5 6-2.5v13.5l-6 2.5-6-2.5-6 2.5z"/><path d="M9 4v13.5M15 6.5V20"/>',
        grid: '<rect x="3.5" y="3.5" width="7" height="7"/><rect x="13.5" y="3.5" width="7" height="7"/><rect x="3.5" y="13.5" width="7" height="7"/><rect x="13.5" y="13.5" width="7" height="7"/>',
        road: '<path d="M7 3L4 21M17 3l3 18M12 4v3M12 10.5v3M12 17v3"/>',
        check: '<circle cx="12" cy="12" r="9"/><path d="M8 12.5l2.7 2.7L16 9.5"/>',
        tick: '<path d="M5 12.5l4.500 4.500L19 7.500"/>',
        truck: '<path d="M2.5 6.5h11v10h-11zM13.5 10h4l3 3v3.500h-7"/><circle cx="7" cy="17.500" r="1.800"/><circle cx="17" cy="17.500" r="1.800"/>',
        leaf: '<path d="M5 19c0-8 5-14 14-14 0 9-6 14-14 14zM5 19l7-7"/>',
        ruler: '<path d="M3 16.500L16.500 3l4.500 4.500L7.500 21z"/><path d="M8.500 9.500l2 2M11.500 6.500l2 2M5.500 12.500l2 2"/>',
        gauge: '<path d="M4 18a9 9 0 1116 0"/><path d="M12 14l4-5"/><circle cx="12" cy="14" r="1"/>',
        route: '<circle cx="6" cy="18" r="2"/><circle cx="18" cy="6" r="2"/><path d="M8 18h7a3 3 0 000-6H9a3 3 0 010-6h7"/>',
        repeat: '<path d="M17 3l3 3-3 3M4 11V9a3 3 0 013-3h13M7 21l-3-3 3-3M20 13v2a3 3 0 01-3 3H4"/>',
        feet: '<path d="M8 4c1.700 0 2.700 1.800 2.700 4.200S9.700 12 8 12 5.300 10.600 5.300 8.200 6.300 4 8 4zM5.800 14.500h4.400v1.800a2.200 2.200 0 01-4.400 0z"/><path d="M16 9c1.700 0 2.700 1.800 2.700 4.200S17.700 17 16 17s-2.700-1.400-2.700-3.800S14.300 9 16 9zM13.800 19.500h4.400v.8a2.200 2.200 0 01-4.400 0z"/>',
        bike: '<circle cx="6" cy="16" r="3.500"/><circle cx="18" cy="16" r="3.500"/><path d="M6 16l4-8h5l3 8M10 8l2.500 8M14 5h2.500"/>',
        cart: '<path d="M3 4h2.500l2 11h10l2-8H6.500"/><circle cx="9" cy="19" r="1.500"/><circle cx="17" cy="19" r="1.500"/>',
        coffee: '<path d="M4 9h12v6a4 4 0 01-4 4H8a4 4 0 01-4-4z"/><path d="M16 10h2a2.500 2.500 0 010 5h-2M7 3v3M11 3v3"/>',
        link: '<path d="M10 14a4 4 0 005.700 0l3-3a4 4 0 00-5.700-5.700l-1 1M14 10a4 4 0 00-5.700 0l-3 3a4 4 0 005.700 5.700l1-1"/>',
        star: '<path d="M12 3l2.700 5.600 6.100.9-4.400 4.300 1 6.100L12 17l-5.400 2.900 1-6.100L3.200 9.500l6.100-.9z"/>',
        sparkles: '<path d="M11 3l1.800 5.200L18 10l-5.200 1.800L11 17l-1.800-5.200L4 10l5.200-1.800zM19 16l.8 2.200L22 19l-2.200.8L19 22l-.8-2.200L16 19l2.200-.8z"/>',
        calendar: '<rect x="3.500" y="5" width="17" height="15.500"/><path d="M3.500 10h17M8 3v4M16 3v4"/>',
        bed: '<path d="M3 18V6M3 14h18v4M21 14v-2.500a3 3 0 00-3-3h-8V14"/><circle cx="6.500" cy="10.500" r="1.500"/>',
        sunrise: '<path d="M3 18h18M6 18a6 6 0 0112 0M12 4v3M4.500 9l2 2M19.500 9l-2 2"/>',
        moon: '<path d="M20 14.500A8 8 0 019.500 4 8 8 0 1020 14.500z"/>',
        wind: '<path d="M3 8h10a2.500 2.500 0 10-2.500-2.500M3 12h15a2.500 2.500 0 11-2.500 2.500M3 16h7a2 2 0 11-2 2"/>',
        passport: '<rect x="5" y="3" width="14" height="18"/><circle cx="12" cy="10" r="2.500"/><path d="M8.500 16.500h7"/>',
        utensils: '<path d="M6 3v7a2 2 0 002 2v9M10 3v7a2 2 0 01-2 2M17 21V3c-2 1.500-3 4-3 7s1 4 3 4"/>',
        mountain: '<path d="M3 20L10 7l4 7 2.500-4L21 20z"/>',
        trend: '<path d="M3 17l6-6 4 4 8-8M15 7h6v6"/>',
        car: '<path d="M4 16v-4l2-5h12l2 5v4zM4 16v2.500M20 16v2.500M7 12h10"/><circle cx="7.500" cy="14" r=".6"/><circle cx="16.500" cy="14" r=".6"/>',
        crown: '<path d="M3.500 18.500h17M4 17L3 7l5 4 4-6 4 6 5-4-1 10z"/>',
        suitcase: '<rect x="3.500" y="7" width="17" height="13"/><path d="M9 7V4.500h6V7M3.500 13h17"/>',
        clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.500 2"/>',
        home: '<path d="M3.500 11L12 4l8.500 7M6 9.500V20h12V9.500M10 20v-5h4v5"/>',
        letters: '<path d="M5 20L12 4l7 16M8 14h8"/>',
        users: '<circle cx="9" cy="8" r="3.200"/><path d="M3 20c0-3.500 2.700-6 6-6s6 2.500 6 6M16 5.500a3 3 0 010 5.500M18 14.500c2 .8 3 2.800 3 5.500"/>',
        undo: '<path d="M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3"/>',
        tree: '<path d="M12 21v-5M12 3l6 8h-3l4 6H5l4-6H6z"/>',
        landmark: '<path d="M3 20h18M5 20V10M9.500 20V10M14.500 20V10M19 20V10M3 10l9-6 9 6z"/>',
        umbrella: '<path d="M3 13a9 9 0 0118 0zM12 13v7M12 20a2 2 0 004 0"/>',
        plane: '<path d="M21 15.500L13.500 11V5a1.500 1.500 0 00-3 0v6L3 15.500V18l7.500-2v3.500L9 21v1.500l3-1 3 1V21l-1.500-1.500V16l7.500 2z"/>',
        ticket: '<path d="M3 8a2 2 0 002-2h14a2 2 0 002 2v2a2 2 0 000 4v2a2 2 0 00-2 2H5a2 2 0 00-2-2v-2a2 2 0 000-4z"/><path d="M14 6v12"/>',
        cloud: '<path d="M7 18a4.500 4.500 0 01-.500-9A6 6 0 0118 10.500 3.800 3.800 0 0117.500 18z"/>',
        bolt: '<path d="M13 3L5 13.500h6L10 21l8-10.500h-6z"/>',
        medal: '<circle cx="12" cy="9" r="5.500"/><path d="M8.500 14L7 21l5-3 5 3-1.500-7"/>',
        trophy: '<path d="M7 4h10v5a5 5 0 01-10 0zM7 6H4v1.500A3 3 0 007 10.500M17 6h3v1.500a3 3 0 01-3 3M12 14v4M8 21h8M9.500 18h5"/>',
        tower: '<path d="M12 12v9M8 21h8M9 9a4.200 4.200 0 000 5.900M15 9a4.200 4.200 0 010 5.900M6 6.500a8 8 0 000 11M18 6.500a8 8 0 010 11"/><circle cx="12" cy="11.500" r="1.200"/>',
        book: '<path d="M5 4.500A1.500 1.500 0 016.500 3H19v15H6.500A1.500 1.500 0 005 19.500zM5 19.500A1.500 1.500 0 006.500 21H19v-3"/>',
        pulse: '<path d="M3 12h4l2.500-6 4 12 2.500-6H21"/>',
        flame: '<path d="M12 21c4 0 6.500-2.700 6.500-6.200 0-3-2-4.800-3-7-1.500 1-2 2.500-2.300 3.800C12.500 9 12 6 10 3 9 6.500 5.500 9 5.500 14.800 5.500 18.300 8 21 12 21z"/>',
        list: '<path d="M8 6h13M8 12h13M8 18h13M3.500 6h.01M3.500 12h.01M3.500 18h.01"/>',
        thermo: '<path d="M10 14.500V5a2 2 0 014 0v9.500a4 4 0 11-4 0z"/>',
        dumbbell: '<path d="M6 7v10M3.500 9.500v5M18 7v10M20.500 9.500v5M6 12h12"/>',
        crosshair: '<circle cx="12" cy="12" r="8"/><path d="M12 2v6M12 16v6M2 12h6M16 12h6"/>',
        chevron: '<path d="M6 9l6 6 6-6"/>',
        refresh: '<path d="M20 11a8 8 0 00-14.500-3.500L4 9M4 4v5h5M4 13a8 8 0 0014.500 3.500L20 15M20 20v-5h-5"/>',
        stop: '<rect x="6" y="6" width="12" height="12"/>',
        building: '<rect x="5" y="3" width="14" height="18"/><path d="M9 7h2M13 7h2M9 11h2M13 11h2M10 21v-4h4v4"/>',
        run: '<circle cx="14" cy="4.500" r="1.800"/><path d="M5 12l3-3 4 1.500 2.500 3.500 3 1M12 10.500L9.500 16 6 19M14.500 14l1 4 3 2.500"/>',
        walk: '<circle cx="13" cy="4.500" r="1.800"/><path d="M13 8l-2.500 4 2.500 2.500V21M10.500 12L7 14M13 8.500l3 3.500 2.500.5M13 14.500L16 21"/>',
        swim: '<circle cx="16" cy="6" r="1.800"/><path d="M3 18c1.500 1.200 3 1.200 4.500 0s3-1.200 4.500 0 3 1.200 4.500 0 3-1.200 4.500 0M5 14l5-4 3 2 3-1.500"/>',
        yoga: '<circle cx="12" cy="5" r="1.800"/><path d="M12 8v6M5 11.500l7 2.500 7-2.500M8 21l4-7 4 7"/>',
        row: '<path d="M3 17c2 1.500 3.500 1.500 5.500 0s3.500-1.500 5.500 0 3.500 1.500 5.500 0M6 13l12-7M15 4l4 4"/>',
        glass: '<path d="M7 3h10l-1 7a4 4 0 01-8 0z"/><path d="M12 14v7M8.500 21h7"/>',
        fuel: '<path d="M5 21V5a2 2 0 012-2h6a2 2 0 012 2v16M4 21h12M8 8h4M15 10h2.500a1.500 1.500 0 011.500 1.500V17a1.500 1.500 0 003 0V9l-3-3"/>',
        wave: '<path d="M2 9c2-3 4-3 6 0s4 3 6 0 4-3 6 0M2 16c2-3 4-3 6 0s4 3 6 0 4-3 6 0"/>',
        hike: '<path d="M3 20L9 8l3.500 6 2-3L21 20z"/><path d="M9 8V4l3 1.500L9 7"/>'
    };

    function svg(name, size, extra) {
        var body = P[name] || P.medal;
        var s = size || 18;
        return '<svg class="rico' + (extra ? ' ' + extra : '') + '" viewBox="0 0 24 24" width="' + s + '" height="' + s +
            '" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
            body + '</svg>';
    }

    window.RoamlyIcons = { svg: svg, names: Object.keys(P), has: function (n) { return !!P[n]; } };
})();
