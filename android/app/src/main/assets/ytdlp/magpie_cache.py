# Magpie's yt-dlp plugin: keep YouTube's player JavaScript between runs.
#
# yt-dlp downloads the full player script (a few MB) on every run and has its
# JS runtime preprocess it from scratch. On a phone that is most of the time it
# takes to read a link. The player only changes every week or so, so both the
# script and the preprocessed form are kept on disk, keyed by player version,
# and only the newest player's copies are kept.
#
# Everything is wrapped: a yt-dlp that renames these internals just runs
# uncached, never fails because of this file.

import glob
import os

try:
    from yt_dlp.extractor.youtube._video import YoutubeIE
except Exception:
    YoutubeIE = None

try:
    from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
except Exception:
    EJSBaseJCP = None

try:
    from yt_dlp.version import __version__ as YTDLP_VERSION
except Exception:
    YTDLP_VERSION = '?'

_PLAYER = 'magpie-player'
_SOLVER = 'challenge-solver'


def _files(cache, section):
    try:
        return glob.glob(os.path.join(cache._get_root_dir(), section, '*.json'))
    except Exception:
        return []


def _prune(cache, player_id):
    """Drops cached players other than `player_id`, raw and preprocessed."""
    for path in _files(cache, _PLAYER):
        if not os.path.basename(path).startswith(player_id):
            _remove(path)
    for path in _files(cache, _SOLVER):
        name = os.path.basename(path)
        if name.startswith('player') and player_id not in name:
            _remove(path)


def _remove(path):
    try:
        os.remove(path)
    except Exception:
        pass


def _fresh_solver_cache(cache):
    """A preprocessed player belongs to the solver that made it: drop them
    when yt-dlp, and so its solver, has changed since."""
    try:
        marker = os.path.join(cache._get_root_dir(), _SOLVER, 'magpie-version')
        seen = open(marker).read().strip() if os.path.exists(marker) else None
        if seen != YTDLP_VERSION:
            for path in _files(cache, _SOLVER):
                if os.path.basename(path).startswith('player'):
                    _remove(path)
            os.makedirs(os.path.dirname(marker), exist_ok=True)
            with open(marker, 'w') as f:
                f.write(YTDLP_VERSION)
    except Exception:
        pass


if YoutubeIE is not None and hasattr(YoutubeIE, '_load_player'):
    _original_load_player = YoutubeIE._load_player

    def _load_player(self, video_id, player_url, fatal=True):
        key = None
        try:
            key = self._player_js_cache_key(player_url)
            if key not in self._code_cache:
                code = self.cache.load(_PLAYER, key)
                if code:
                    self._code_cache[key] = code
                    return code
        except Exception:
            key = None
        code = _original_load_player(self, video_id, player_url, fatal=fatal)
        if key and code:
            try:
                self.cache.store(_PLAYER, key, code)
                _prune(self.cache, key.split('-')[0])
            except Exception:
                pass
        return code

    YoutubeIE._load_player = _load_player

if EJSBaseJCP is not None and hasattr(EJSBaseJCP, '_real_bulk_solve'):
    EJSBaseJCP._ENABLE_PREPROCESSED_PLAYER_CACHE = True
    _original_bulk_solve = EJSBaseJCP._real_bulk_solve

    def _real_bulk_solve(self, *args, **kwargs):
        _fresh_solver_cache(self.ie.cache)
        return _original_bulk_solve(self, *args, **kwargs)

    EJSBaseJCP._real_bulk_solve = _real_bulk_solve
