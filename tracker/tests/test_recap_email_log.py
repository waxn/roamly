"""Recap emails on the Raw data page: the SummaryEmailLog history.

Every read is scoped to the signed-in user, newest first; the send path keeps a
copy of exactly what went out and never lets the log break a send.
"""
from datetime import date, timedelta
from unittest import mock

import pytest
from django.utils import timezone

from tracker import summary_email_tasks
from tracker.models import SummaryEmailLog, UserProfile

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user('linus', email='l@example.com', password='pw-123456')
    UserProfile.objects.get_or_create(user=u)
    return u


@pytest.fixture
def other(django_user_model):
    return django_user_model.objects.create_user('mallory', password='pw-123456')


@pytest.fixture
def client_in(client, user):
    client.force_login(user)
    return client


def _log(user, subject, *, days_ago=0, period='daily', html='<html><body>hi</body></html>'):
    return SummaryEmailLog.objects.create(
        user=user, period=period, period_start=date(2026, 11, 20), period_end=date(2026, 11, 20),
        subject=subject, body_text=f'{subject} text', body_html=html,
        sent_at=timezone.now() - timedelta(days=days_ago),
    )


def test_list_is_scoped_and_newest_first(client_in, user, other):
    _log(user, 'old', days_ago=30)
    _log(user, 'new', days_ago=1)
    _log(other, 'not yours', days_ago=0)
    # Outside the page's default 24h window too: the recap history isn't windowed.
    r = client_in.get('/api/data/source/recaps/?hours=24')
    assert r.status_code == 200
    body = r.json()
    assert [row['subject'] for row in body['rows']] == ['new', 'old']
    assert body['open'] == 'recap'
    assert not {'device', 'device_id'} & {c['key'] for c in body['columns']}
    assert 'not yours' not in r.content.decode()


def test_paging_cursor(client_in, user, monkeypatch):
    from tracker import views
    monkeypatch.setattr(views, '_RAW_PAGE_SIZE', 2)
    for i in range(3):
        _log(user, f's{i}', days_ago=i)
    first = client_in.get('/api/data/source/recaps/').json()
    assert [r['subject'] for r in first['rows']] == ['s0', 's1'] and first['has_more']
    rest = client_in.get('/api/data/source/recaps/', {
        'before_value': first['next_before_value'], 'before_id': first['next_before_id']}).json()
    assert [r['subject'] for r in rest['rows']] == ['s2'] and not rest['has_more']


def test_detail_is_scoped(client_in, user, other):
    mine = _log(user, 'mine', html='<html><body><img src="cid:roamlylogo"></body></html>')
    theirs = _log(other, 'theirs')
    r = client_in.get(f'/api/data/recap-emails/{mine.id}/')
    assert r.status_code == 200
    d = r.json()
    assert d['subject'] == 'mine' and d['text'] == 'mine text'
    assert 'cid:' not in d['html']
    assert client_in.get(f'/api/data/recap-emails/{theirs.id}/').status_code == 404


def test_login_required(client, user):
    log = _log(user, 'x')
    assert client.get(f'/api/data/recap-emails/{log.id}/').status_code == 302
    assert client.get('/api/data/source/recaps/').status_code == 302


def test_sources_list_offers_recaps_only_with_history_or_opt_in(client_in, user):
    keys = lambda: [s['key'] for s in client_in.get('/api/data/sources/').json()['sources']]  # noqa: E731
    assert 'recaps' not in keys()
    UserProfile.objects.filter(user=user).update(summary_weekly=True)
    sources = {s['key']: s for s in client_in.get('/api/data/sources/').json()['sources']}
    assert sources['recaps']['device'] is False and sources['recaps']['range'] is False


def test_record_sent_keeps_copy_and_prunes(user):
    _log(user, 'ancient', days_ago=SummaryEmailLog.RETENTION_DAYS + 5)
    summary_email_tasks._record_sent(user, 'weekly', date(2026, 11, 16), date(2026, 11, 22),
                                     'Your Roamly week recap', 'text', '<html></html>', ok=False)
    rows = list(SummaryEmailLog.objects.filter(user=user))
    assert [r.subject for r in rows] == ['Your Roamly week recap']
    assert rows[0].status == 'failed' and rows[0].period == 'weekly'


def test_record_sent_never_raises(user):
    with mock.patch.object(SummaryEmailLog.objects, 'create', side_effect=RuntimeError('db down')):
        summary_email_tasks._record_sent(user, 'daily', date(2026, 11, 20), date(2026, 11, 20),
                                         's', 't', 'h', ok=True)


def test_send_period_records_what_was_sent(user):
    """The scheduler path stores the exact subject/body it handed to SMTP."""
    profile = UserProfile.objects.get(user=user)
    sent = {}

    def fake_send(subject, text, html, to):
        sent.update(subject=subject, text=text, html=html, to=to)
        return True

    overview = {'total_points': 5, 'cities': 1, 'states': 1, 'countries': 1}
    with mock.patch.object(summary_email_tasks.email_utils, 'email_enabled', return_value=True), \
            mock.patch.object(type(profile), 'ai_configured', new_callable=mock.PropertyMock, return_value=True), \
            mock.patch('tracker.views._compute_overview_from_qs', return_value=overview), \
            mock.patch('tracker.views._compute_distance_from_qs',
                       return_value={'distances': [1.0], 'total_km': 1.0}), \
            mock.patch.object(summary_email_tasks, '_period_visits', return_value=[]), \
            mock.patch.object(summary_email_tasks, '_top_cities', return_value=[]), \
            mock.patch.object(summary_email_tasks, '_narrative', return_value=['A good day.']), \
            mock.patch.object(summary_email_tasks.email_utils, '_send', side_effect=fake_send):
        assert summary_email_tasks._send_period(profile, 'daily') == 'sent'
    log = SummaryEmailLog.objects.get(user=user)
    assert sent['to'] == 'l@example.com'
    assert log.subject == sent['subject'] == 'Your Roamly day recap'
    assert log.body_html == sent['html'] and log.body_text == sent['text']
    assert log.status == 'sent' and not log.is_test
    assert log.period_start == log.period_end
