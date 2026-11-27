"""Badge registry and level table for Achievements (pure Python, no Django).

A badge reads one numeric metric from the payload ``metrics`` dict and has
four ascending tier thresholds: bronze, silver, gold, platinum. A metric that
is absent (no data, no PostGIS, feature never used) leaves the badge locked at
0 progress rather than erroring, so one missing source never hides the wall.

``reach`` (optional, per metric) is a list of ``(iso_datetime, cumulative)``
pairs in ascending order. When present, a newly earned tier is back-dated to
the first point the metric crossed its threshold — so the first scan of a
long history produces a real milestone timeline instead of stamping every
badge with today.
"""
from dataclasses import dataclass, field
import math

# Bumped when the badge set or payload shape changes: an older snapshot is still
# served (patched by compat_game below) while the API kicks one background rescan.
SCHEMA = 2

TIER_NAMES = ('bronze', 'silver', 'gold', 'platinum')
TIER_XP = (10, 25, 60, 150)


@dataclass(frozen=True)
class Badge:
    id: str
    name: str
    icon: str
    group: str
    metric: str
    tiers: tuple
    unit: str = ''
    blurb: str = ''
    # Lower values are better (none today, kept so a "fewest X" badge can't
    # silently invert).
    lower_is_better: bool = field(default=False)


GROUPS = (
    ('explorer', 'Explorer'),
    ('roads', 'Road Warrior'),
    ('distance', 'Speed & Distance'),
    ('regulars', 'Your Regulars'),
    ('rhythm', 'Rhythm'),
    ('bigdays', 'Big Days'),
    ('climber', 'Climber'),
    ('journeys', 'Epic Journeys'),
    ('globe', 'Globe-trotter'),
    ('collector', 'Town Collector'),
    ('nature', 'Nature & Culture'),
    ('health', 'Health & Fitness'),
    ('sky', 'Sky & Signals'),
    ('years', 'Seasons & Years'),
)

B = Badge
BADGES = (
    # ── Explorer ────────────────────────────────────────────────────────────
    B('countries', 'Passport Stamps', 'passport', 'explorer', 'countries', (2, 5, 15, 40), 'countries',
      'Countries you have set foot in.'),
    B('us_states', 'State Hopper', 'flag', 'explorer', 'us_states', (3, 10, 25, 51), 'states',
      'US states and DC you have visited.'),
    B('us_counties', 'County Line', 'compass', 'explorer', 'us_counties', (10, 50, 200, 750), 'counties',
      'US counties you have been inside.'),
    B('towns', 'Town Crier', 'building', 'explorer', 'towns', (10, 50, 250, 1000), 'towns',
      'Distinct towns and cities in your history.'),
    B('regions_abroad', 'Abroad', 'map', 'explorer', 'regions_abroad', (1, 5, 20, 60), 'regions',
      'Regions or provinces visited outside the US.'),
    B('home_state_pct', 'Know Your Home', 'home', 'explorer', 'home_state_town_pct', (10, 25, 50, 80), '%',
      'Share of the towns in your home state you have visited.'),
    B('cells', 'Cartographer', 'grid', 'explorer', 'cells', (1000, 10000, 50000, 200000), 'cells',
      'Distinct ~110m squares you have stood in.'),
    # ── Road Warrior ────────────────────────────────────────────────────────
    B('road_km', 'Asphalt Collector', 'road', 'roads', 'road_km', (25, 250, 1500, 6000), 'km',
      'Kilometres of distinct mapped road you have travelled.'),
    B('completionist', 'Completionist', 'check', 'roads', 'towns_90pct', (1, 3, 10, 30), 'towns',
      'Towns where you have covered 90% of the mapped roads.'),
    B('motorway', 'Interstate', 'truck', 'roads', 'motorway_km', (10, 100, 500, 2000), 'km',
      'Distinct motorway kilometres travelled.'),
    B('backroads', 'Backroads', 'leaf', 'roads', 'minor_km', (10, 100, 500, 2000), 'km',
      'Distinct residential and unclassified road kilometres.'),
    # ── Speed & Distance ────────────────────────────────────────────────────
    B('total_km', 'Odometer', 'ruler', 'distance', 'total_km', (1000, 10000, 40075, 100000), 'km',
      'Total distance travelled. Gold is once around the Earth.'),
    B('max_speed', 'Need for Speed', 'gauge', 'distance', 'max_ground_kmh', (60, 100, 140, 200), 'km/h',
      'Top corroborated ground speed (flights excluded).'),
    B('best_day', 'Marathon Day', 'route', 'distance', 'best_day_km', (100, 300, 600, 1000), 'km',
      'Most distance in a single day.'),
    B('move_streak', 'Always Moving', 'repeat', 'distance', 'move_streak', (7, 30, 100, 365), 'days',
      'Longest run of days with more than 10 km travelled.'),
    B('walk_km', 'Iron Legs', 'feet', 'distance', 'walk_km', (50, 250, 1000, 4000), 'km',
      'Distance travelled on foot.'),
    B('cycle_km', 'Pedal Power', 'bike', 'distance', 'cycle_km', (25, 250, 1000, 5000), 'km',
      'Distance travelled by bike.'),
    # ── Regulars ────────────────────────────────────────────────────────────
    B('pois', 'Place Hunter', 'pin', 'regulars', 'distinct_pois', (10, 50, 200, 750), 'places',
      'Distinct named places you have stopped at.'),
    B('regulars', 'Regular', 'coffee', 'regulars', 'regular_places', (1, 5, 15, 40), 'places',
      'Places you have stopped at ten or more times.'),
    B('top_store', 'Loyal Customer', 'cart', 'regulars', 'top_store_visits', (5, 25, 100, 300), 'visits',
      'Visits to your most-visited store.'),
    B('chain', 'Chain Gang', 'link', 'regulars', 'chain_branches', (2, 4, 8, 15), 'branches',
      'Different branches of one chain you have visited.'),
    B('new_places_year', 'Fresh Finds', 'sparkles', 'regulars', 'new_places_this_year', (5, 20, 60, 150), 'places',
      'New named places this year.'),
    # ── Rhythm ──────────────────────────────────────────────────────────────
    B('days_tracked', 'Diarist', 'calendar', 'rhythm', 'days_tracked', (30, 180, 365, 1000), 'days',
      'Days with recorded location.'),
    B('track_streak', 'Unbroken', 'link', 'rhythm', 'track_streak', (14, 60, 180, 365), 'days',
      'Longest run of consecutive tracked days.'),
    B('nights_away', 'Night Away', 'bed', 'rhythm', 'nights_away', (1, 10, 30, 100), 'nights',
      'Nights spent more than 50 km from home.'),
    B('early_bird', 'Early Bird', 'sunrise', 'rhythm', 'early_bird_days', (1, 10, 30, 100), 'days',
      'Days you were already on the move before 6 am.'),
    B('night_owl', 'Night Owl', 'moon', 'rhythm', 'night_owl_days', (1, 10, 30, 100), 'days',
      'Days you were still on the move after midnight.'),
    # ── Big Days ────────────────────────────────────────────────────────────
    B('towns_day', 'Whirlwind', 'wind', 'bigdays', 'max_towns_day', (5, 10, 20, 40), 'towns',
      'Most towns visited in one day.'),
    B('border_hopper', 'Border Hopper', 'passport', 'bigdays', 'max_states_day', (2, 3, 5, 8), 'states/regions',
      'Most states or regions in one day.'),
    B('countries_day', 'Three-Country Lunch', 'utensils', 'bigdays', 'max_countries_day', (2, 3, 4, 6), 'countries',
      'Most countries in one day.'),
    # ── Climber ─────────────────────────────────────────────────────────────
    B('climbed', 'Everest Climber', 'mountain', 'climber', 'climbed_m', (1000, 8849, 50000, 200000), 'm',
      'Total metres climbed. Silver is one Everest.'),
    B('highest', 'Thin Air', 'mountain', 'climber', 'highest_m', (500, 1500, 3000, 4500), 'm',
      'Highest altitude reached on the ground.'),
    B('climb_day', 'Big Climb', 'trend', 'climber', 'best_climb_day_m', (200, 600, 1200, 2500), 'm',
      'Most metres climbed in one day.'),
    # ── Epic Journeys ───────────────────────────────────────────────────────
    B('long_journey', 'Long Haul', 'car', 'journeys', 'longest_journey_km', (50, 200, 500, 1000), 'km',
      'Longest non-stop journey.'),
    B('long_walk', 'Wanderer', 'compass', 'journeys', 'longest_walk_km', (5, 10, 21, 42), 'km',
      'Longest single walk.'),
    B('long_ride', 'Century', 'bike', 'journeys', 'longest_ride_km', (20, 50, 100, 160), 'km',
      'Longest single bike ride.'),
    B('commute', 'Commute King', 'crown', 'journeys', 'commute_count', (10, 50, 200, 500), 'trips',
      'Times you made your most common trip.'),
    B('journeys', 'Road Tripper', 'suitcase', 'journeys', 'journeys', (50, 500, 2000, 5000), 'journeys',
      'Journeys taken.'),
    # ── Globe-trotter ───────────────────────────────────────────────────────
    B('timezones', 'Jet Lag', 'clock', 'globe', 'timezones', (2, 4, 8, 15), 'zones',
      'Time zones you have been in.'),
    B('hemispheres', 'Hemisphere Hopper', 'globe', 'globe', 'hemispheres', (1, 2, 3, 4), 'hemispheres',
      'Of N, S, E and W — how many you have stood in.'),
    B('crossings', 'Line Crosser', 'crosshair', 'globe', 'crossings', (1, 2, 3, 4), 'lines',
      'Equator, Tropics, polar circles or the date line crossed.'),
    B('far_home', 'Far From Home', 'compass', 'globe', 'farthest_km', (100, 1000, 5000, 12000), 'km',
      'Farthest you have been from home.'),
    # ── Town Collector ──────────────────────────────────────────────────────
    B('alphabet', 'Alphabet', 'letters', 'collector', 'alphabet_letters', (8, 15, 22, 26), 'letters',
      'Letters of the alphabet your visited towns start with.'),
    B('namesakes', 'Namesakes', 'users', 'collector', 'max_namesakes', (2, 3, 4, 6), 'towns',
      'Most towns sharing one name you have visited.'),
    B('comeback', 'Comeback Kid', 'undo', 'collector', 'longest_comeback_days', (90, 365, 1000, 2500), 'days',
      'Longest gap before returning to a town.'),
    B('home_town', 'Local Legend', 'home', 'collector', 'top_town_days', (30, 180, 500, 1500), 'days',
      'Days spent in your most-visited town.'),
    # ── Nature & Culture ────────────────────────────────────────────────────
    B('parks', 'Park Ranger', 'tree', 'nature', 'parks', (3, 10, 30, 80), 'parks',
      'Distinct parks and nature spots you have stopped at.'),
    B('culture', 'Culture Vulture', 'landmark', 'nature', 'culture', (2, 8, 20, 50), 'places',
      'Museums, galleries, landmarks and historic sites.'),
    B('beaches', 'Beach Bum', 'umbrella', 'nature', 'beaches', (1, 3, 8, 20), 'beaches',
      'Distinct beaches.'),
    B('eats', 'Foodie', 'utensils', 'nature', 'food_places', (10, 40, 120, 300), 'places',
      'Distinct restaurants, cafés and bars.'),
    # ── Sky & Body ──────────────────────────────────────────────────────────
    B('flights', 'Frequent Flyer', 'plane', 'sky', 'flights', (1, 10, 50, 150), 'flights',
      'Flights in your log.'),
    B('airports', 'Terminal Collector', 'ticket', 'sky', 'airports', (2, 8, 25, 60), 'airports',
      'Distinct airports.'),
    B('flight_km', 'Mile High', 'cloud', 'sky', 'flight_km', (1000, 20000, 100000, 400000), 'km',
      'Distance flown.'),
    B('steps', 'Step Counter', 'feet', 'health', 'steps_total', (100000, 1000000, 5000000, 20000000), 'steps',
      'Steps recorded.'),
    B('step_day', 'Twenty Thousand', 'bolt', 'health', 'best_step_day', (10000, 20000, 30000, 50000), 'steps',
      'Most steps in one day.'),
    B('steps_10k', 'Ten Thousand Club', 'check', 'health', 'steps_10k_days', (10, 50, 200, 500), 'days',
      'Days you reached 10,000 steps.'),
    B('steps_streak', 'On a Roll', 'repeat', 'health', 'steps_10k_streak', (3, 7, 30, 100), 'days',
      'Longest run of consecutive days at 10,000 steps or more.'),
    B('walked', 'Footslogger', 'route', 'health', 'health_distance_km', (100, 1000, 5000, 20000), 'km',
      'Distance your phone or watch counted on foot.'),
    B('kcal_total', 'Furnace', 'flame', 'health', 'kcal_active_total', (5000, 50000, 250000, 1000000), 'kcal',
      'Active calories burned.'),
    B('kcal_day', 'Calorie Crusher', 'flame', 'health', 'best_kcal_day', (500, 1000, 2000, 3500), 'kcal',
      'Most active calories in one day.'),
    B('workouts', 'Gym Rat', 'dumbbell', 'health', 'workouts', (1, 10, 50, 200), 'workouts',
      'Workouts imported from Health Connect.'),
    B('activities', 'Athlete', 'medal', 'health', 'activities', (1, 10, 50, 200), 'activities',
      'Activities you recorded in Roamly.'),
    B('towers', 'Tower Spotter', 'tower', 'sky', 'towers', (10, 100, 500, 2000), 'towers',
      'Distinct cell sites seen.'),
    B('journal', 'Scribe', 'book', 'sky', 'journal_streak', (3, 14, 60, 200), 'days',
      'Longest journal-writing streak.'),
    # ── Seasons & Years ─────────────────────────────────────────────────────
    B('years', 'Veteran', 'medal', 'years', 'years_tracked', (1, 2, 4, 8), 'years',
      'Calendar years with data.'),
    B('seasons', 'Four Seasons', 'leaf', 'years', 'seasons_with_travel', (1, 2, 3, 4), 'seasons',
      'Seasons in which you travelled more than 100 km.'),
    B('pb', 'Personal Best', 'trend', 'years', 'personal_bests', (1, 2, 3, 5), 'records',
      'Records set this year that beat every earlier year.'),
)

BADGE_BY_ID = {b.id: b for b in BADGES}
assert len(BADGE_BY_ID) == len(BADGES), 'duplicate badge id'

# Levels: cumulative XP needed to reach level n. Grows a bit faster than
# linear, so early levels come quickly and the top ones mean something.
LEVEL_TITLES = (
    (1, 'Homebody'), (4, 'Stroller'), (8, 'Wayfarer'), (13, 'Pathfinder'),
    (19, 'Voyager'), (26, 'Cartographer'), (34, 'Globetrotter'), (43, 'Legend'),
)


def xp_for_level(n):
    return 0 if n <= 1 else int(round(30 * (n - 1) ** 1.55))


def level_for_xp(xp):
    n = 1
    while xp_for_level(n + 1) <= xp:
        n += 1
    title = LEVEL_TITLES[0][1]
    for lvl, t in LEVEL_TITLES:
        if n >= lvl:
            title = t
    lo, hi = xp_for_level(n), xp_for_level(n + 1)
    return {'level': n, 'title': title, 'xp': xp, 'level_xp': lo, 'next_xp': hi,
            'progress': (xp - lo) / (hi - lo) if hi > lo else 1.0}


def tier_for(badge, value):
    """Highest tier index (1-4) reached by ``value``, 0 for none."""
    if value is None:
        return 0
    t = 0
    for i, th in enumerate(badge.tiers, start=1):
        if (value <= th) if badge.lower_is_better else (value >= th):
            t = i
    return t


def progress_for(badge, value):
    """Fraction (0-1) of the way from the current tier to the next one."""
    if value is None:
        return 0.0
    t = tier_for(badge, value)
    if t >= len(badge.tiers):
        return 1.0
    lo = badge.tiers[t - 1] if t else 0
    hi = badge.tiers[t]
    if hi <= lo:
        return 0.0
    return max(0.0, min(1.0, (value - lo) / (hi - lo)))


def reached_at(reach, threshold):
    """First ISO time in ``reach`` whose cumulative value meets ``threshold``."""
    for ts, cum in reach or ():
        if cum >= threshold:
            return ts
    return None


def evaluate(metrics):
    """Return ``(badge_rows, xp)`` for a metrics dict. Pure — the caller merges
    persisted unlock dates in."""
    rows = []
    xp = 0
    for b in BADGES:
        v = metrics.get(b.metric)
        if isinstance(v, float) and (math.isnan(v) or math.isinf(v)):
            v = None
        t = tier_for(b, v)
        xp += sum(TIER_XP[:t])
        nxt = b.tiers[t] if t < len(b.tiers) else None
        rows.append({
            'id': b.id, 'name': b.name, 'icon': b.icon, 'group': b.group,
            'unit': b.unit, 'blurb': b.blurb, 'tiers': list(b.tiers),
            'value': v, 'tier': t, 'next': nxt, 'progress': round(progress_for(b, v), 4),
            'available': v is not None,
        })
    return rows, xp


def compat_game(game):
    """Patch a stored ``game`` block written under an older schema, in place.

    Emoji gave way to icon keys and some badges changed group, so an old
    snapshot would otherwise render with blank icons and badges under the wrong
    heading until its next scan. Pure and cheap: only fields the registry owns.
    """
    if not isinstance(game, dict):
        return game
    for row in game.get('badges', []):
        b = BADGE_BY_ID.get(row.get('id'))
        if b:
            row['icon'] = b.icon
            row['group'] = b.group
            row.pop('emoji', None)
    for key in ('recent', 'timeline'):
        for e in game.get(key, []):
            b = BADGE_BY_ID.get(e.get('badge'))
            if b:
                e['icon'] = b.icon
            e.pop('emoji', None)
    game['groups'] = [{'id': g, 'name': n} for g, n in GROUPS]
    return game
