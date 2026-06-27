"""Background writer for AccessLog / ActionLog rows.

Logging used to spawn a fresh daemon thread per request and call
``Model.objects.create()`` inside it. Each thread opened its own Django DB
connection and **never closed it** — Django only auto-closes the connection
owned by the request thread. Under any real traffic on PostgreSQL this leaks
connections until ``max_connections`` is hit, after which every write raises
and is swallowed by the surrounding ``except Exception: pass`` — i.e. logging
silently stops working.

This module replaces that with a single long-lived consumer thread (the same
pattern the geocode/backup workers use: one connection, reused) fed by an
in-memory queue. Writes are batched with ``bulk_create`` and the queue drops
on overflow so request handling is never blocked or delayed.
"""

import logging
import queue
import threading

from django.db import close_old_connections

logger = logging.getLogger(__name__)

# Bounded so a writer stall can never balloon memory; we'd rather drop a few
# log rows than degrade the app. ~20k rows is plenty of headroom.
_QUEUE = queue.Queue(maxsize=20000)
_BATCH_MAX = 500          # rows flushed per DB round-trip
_GATHER_TIMEOUT_S = 0.5   # how long to keep batching before flushing

_worker = None
_start_lock = threading.Lock()


def _worker_loop():
    from .models import AccessLog, ActionLog

    while True:
        # Block for the first item, then opportunistically drain more so a
        # burst of requests collapses into one bulk insert.
        batch = [_QUEUE.get()]
        try:
            while len(batch) < _BATCH_MAX:
                batch.append(_QUEUE.get(timeout=_GATHER_TIMEOUT_S))
        except queue.Empty:
            pass

        access = [AccessLog(**kw) for kind, kw in batch if kind == 'access']
        action = [ActionLog(**kw) for kind, kw in batch if kind == 'action']

        # Refresh any connection the DB server closed under us (long-lived
        # thread), and hand it back to the pool when the flush is done.
        close_old_connections()
        try:
            if access:
                AccessLog.objects.bulk_create(access)
            if action:
                ActionLog.objects.bulk_create(action)
        except Exception:
            logger.exception("log writer flush failed (%d rows dropped)", len(batch))
        finally:
            close_old_connections()


def _ensure_worker():
    global _worker
    if _worker is not None and _worker.is_alive():
        return
    with _start_lock:
        if _worker is not None and _worker.is_alive():
            return
        _worker = threading.Thread(target=_worker_loop, daemon=True, name="log-writer")
        _worker.start()


def _enqueue(kind, kwargs):
    _ensure_worker()
    try:
        _QUEUE.put_nowait((kind, kwargs))
    except queue.Full:
        # Writer is behind; drop rather than block the request thread.
        pass


def enqueue_access(**kwargs):
    """Queue an AccessLog row for background insertion."""
    _enqueue('access', kwargs)


def enqueue_action(**kwargs):
    """Queue an ActionLog row for background insertion."""
    _enqueue('action', kwargs)
