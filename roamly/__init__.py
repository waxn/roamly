# Single source of truth for the app version.
#
# It used to live only in base.html's footer as a literal, hand-edited on each
# release — which is exactly the kind of thing that silently drifts from the git
# tag it is meant to match. context_processors exposes it as APP_VERSION.
#
# Bump this in the same commit as the `v*` git tag (see CLAUDE.md's versioning
# table for which component to increment).
__version__ = '0.61.0'
