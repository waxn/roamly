import os
from django.apps import AppConfig


class TrackerConfig(AppConfig):
    default_auto_field = 'django.db.models.BigAutoField'
    name = 'tracker'

    def ready(self):
        # Only start the scheduler in the main process (avoid double-start with dev reloader)
        if os.environ.get('RUN_MAIN') == 'true' or not os.environ.get('RUN_MAIN'):
            try:
                from .backup_tasks import start_backup_scheduler
                start_backup_scheduler()
            except Exception:
                pass
            try:
                from .stats_tasks import start_stats_scheduler
                start_stats_scheduler()
            except Exception:
                pass
            try:
                from .mobile_release_tasks import start_mobile_release_scheduler
                start_mobile_release_scheduler()
            except Exception:
                pass
            try:
                from .summary_email_tasks import start_summary_email_scheduler
                start_summary_email_scheduler()
            except Exception:
                pass
