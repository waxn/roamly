# Mobile redesign proposal: "Still Water"

Status: proposal, nothing implemented. Replaces the Night Atlas look (see `docs/claude/mobile-app.md`) if approved.

## Brief

A calm, polished, map-first app where every piece of data is reachable in one or two taps. Audience: someone who tracks their own movement all day and opens the app for a few seconds to check "where have I been / how far / what did I do". Primary job: look at the map; everything else is a lens on it.

## Core idea: the map is the app

Today: four tabs (Map, Trips, Ask, You) plus pushed routes for Stats, Flights, Health, Location data. Data lives behind navigation.

Proposed: one screen, the map, with one persistent bottom sheet. No tab dock.

```
┌──────────────────────────┐
│  [ Search or ask…      ] ● │  floating pill, one tap to Search / Ask
│                          │
│        MAP (full bleed)  │  track, fog, places; period shown on the map
│                     [◎]  │  locate
│                     [●]  │  Record (the only coral thing)
│ ──────────────────────── │
│  Today · 12.4 km · 5 places│ peek (≈96dp): always-visible headline figures
└──────────────────────────┘
        swipe up ↑
┌──────────────────────────┐
│ Today  Week  Year  Trips │  lenses, one segmented row
│  12.4 km   5 places      │
│  timeline of the day…    │  tap an item → map flies there
│  Stats · Flights · Health│  data shelf: one row of chips, one tap each
└──────────────────────────┘
```

Sheet detents: peek, half, full. Peek is the default, so the map stays calm.

### Click budget

| Want | Today | Proposed |
|---|---|---|
| Distance today | You → Stats, or Map pill | 0 (peek) |
| Where was I on a date | Stats → month → day | Sheet → Week lens → day (2) |
| Search a place | Ask tab → Search | Pill (1) |
| Ask the AI | Ask tab | Pill, toggle Ask (1–2) |
| Trips | Trips tab | Sheet → Trips lens (2) |
| Flights / Health / Location data | You → tile | Sheet full → chip (2) |
| Start recording | Dock button | Floating button (1) |
| Settings | You → Settings | Avatar in search pill (2) |

You tab disappears: tracking status becomes a small state line in the peek ("Tracking · last fix 2 min ago"), Family, Tracker, Settings and Diagnostics move behind the avatar menu.

## Visual system

Calm = low contrast between surfaces, one quiet accent, generous space, no glass-on-glass, no glow.

**Colour** (Day is the default; Night follows the system)

| Token | Day | Night | Role |
|---|---|---|---|
| `ground` | `#EEF2F1` mist | `#0F171A` deep water | Sheet and screens |
| `surface` | `#FAFBFA` | `#162226` | Cards |
| `ink` | `#16303A` | `#E6EEEE` | Text, primary buttons |
| `inkMuted` | `#5B6F75` | `#90A4A9` | Secondary text |
| `track` | `#1F7A84` tide | `#7FD1C7` | Your route, active lens, links |
| `record` | `#E4604D` coral | `#F07A68` | Record and live tracking only |

Categorical ramp for transport modes and charts stays muted: sand `#C9A66B`, moss `#7A9A5B`, dusk `#8C7BB5`, rose `#C77D8A`, slate `#6C8DB0`. Orange as the global accent is retired; coral is reserved so "red dot = recording" is unambiguous.

**Map basemap**: a desaturated, low-contrast light style in Day (tinted OSM, not inverted) so the track is the only saturated thing. Night keeps the existing `basemapNightFilter`, cooled toward the new ground.

**Type**: Figtree throughout (humanist, soft, has tabular figures). Sizes: figure 34/40 semibold tabular, title 20, body 16, label 13. Sentence case everywhere, no mono labels, no uppercase.

**Shape and depth**: keep `SquircleShape`. Sheet top radius 28, cards 16, controls 12, pills full. One soft neutral shadow for floating controls; the sheet has none, only a 1dp `line` edge.

**Motion**: two springs only. Sheet drag follows the finger; detents settle with `AtlasMotion.glide`. Selecting a timeline item flies the camera (400ms) instead of navigating. The only ambient motion is the live position dot's slow pulse. Reduced-motion turns the camera flies into jumps.

## What changes in code (rough sizing)

1. Theme: new `AtlasColors` values and Figtree font, same token names, so screens recolour without edits. (small)
2. Kit: add `MapSheet` (three detents), `LensRow`, `FigureStrip`; restyle `Panel`, `AtlasButton`. Remove `Dock`. (medium)
3. Shell: `RoamlyNavHost` drops the four tabs; Map is the root; Trips, Stats, Search/Ask become sheet lenses or pushed routes from the sheet. Activity-scoped ViewModel rules in `mobile-app.md` still apply. (large)
4. Map: floating search pill, locate, Record button; options sheet folds into the Today/Week/Year lens plus a layers button. (medium)
5. Simple Mode: map + peek only, no lenses beyond Today. (small)

Suggested order, one commit each: theme tokens, kit components, map screen, nav shell, then per-screen polish (Trips, Stats, Ask, You contents).

## Risks and questions

- The Ask and Search experience needs a full-height surface; plan is a full-screen pushed route opened from the pill, not a lens.
- Hiding Trips and Stats one level deeper trades discoverability for a calmer map; the chip shelf mitigates it.
- Keeping the Record button floating over the map risks accidental taps; keep the existing stop-confirmation and 44dp+ target.
- Colour changes are low risk and could ship first, independent of the navigation change.
