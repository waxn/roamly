"""Self-contained /beta/ UI sandbox.

Everything beta lives under this package, ``templates/tracker/beta/`` and
``static/tracker/beta/``. Classic pages never reference any of it. To switch it
off, remove the two ``tracker.beta`` entries from ``roamly/settings.py``
(MIDDLEWARE and the TEMPLATES loaders); nothing else needs to change.
"""
