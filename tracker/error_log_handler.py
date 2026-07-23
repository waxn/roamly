"""Logging handler that records unhandled 500s into ActionLog.

Attached to the 'django.request' logger (see settings.LOGGING), which emits an
ERROR record on every 5xx response. We pull the traceback from the record and
the path/IP/user from the attached request, and hand an 'error' ActionLog row to
the shared batched writer. Best-effort: a failure here must never mask the
original error or break request handling, so everything is wrapped in try/except.
"""

import logging
import traceback


class ActionLogErrorHandler(logging.Handler):
    def emit(self, record):
        try:
            request = getattr(record, 'request', None)

            path = ''
            ip = None
            user_id = None
            if request is not None:
                path = getattr(request, 'path', '') or ''
                try:
                    from .middleware import _get_client_ip
                    ip = _get_client_ip(request)
                except Exception:
                    ip = request.META.get('REMOTE_ADDR') if hasattr(request, 'META') else None
                user = getattr(request, 'user', None)
                if user is not None and getattr(user, 'is_authenticated', False):
                    user_id = user.id

            if record.exc_info:
                tb = ''.join(traceback.format_exception(*record.exc_info))
            else:
                tb = record.getMessage()

            summary = record.getMessage()
            description = f"{summary}\n\n{tb}" if tb and tb != summary else (tb or summary)
            # Prefix with the path so the Events list is scannable without expanding.
            if path:
                description = f"{path}\n\n{description}"

            from django.utils import timezone
            from .log_writer import enqueue_action
            enqueue_action(
                user_id=user_id,
                action='error',
                description=description[:20000],
                ip_address=ip,
                timestamp=timezone.now(),
            )
        except Exception:
            # Never let error logging cause a second error.
            pass
