"""Outbound requests to configured URLs: redirects refused, Overpass validated."""
import urllib.error
import urllib.request

import pytest

from tracker import net_utils, overpass


def test_redirect_is_refused_not_followed():
    req = urllib.request.Request('https://example.com/a')
    with pytest.raises(urllib.error.HTTPError) as info:
        net_utils._RefuseRedirect().redirect_request(
            req, None, 302, 'Found', {}, 'http://169.254.169.254/latest/meta-data/')
    assert info.value.code == 302
    assert '169.254.169.254' in str(info.value.reason)


def test_overpass_refuses_private_endpoint(settings):
    settings.ALLOW_PRIVATE_OUTBOUND = False
    with pytest.raises(net_utils.OutboundURLError):
        overpass._post('http://127.0.0.1:8080/api/interpreter', overpass.PROBE_QUERY, 5)


def test_refused_endpoint_classifies_as_unreachable():
    kind, msg = overpass.classify(net_utils.OutboundURLError('nope'))
    assert kind == 'unreachable' and msg == 'nope'


@pytest.mark.parametrize('ip', ['100.64.0.1', '100.100.100.100', '127.0.0.1', '169.254.169.254', '10.0.0.5'])
def test_internal_addresses_are_blocked(ip):
    assert net_utils._is_blocked_ip(ip)


def test_public_address_is_allowed():
    assert not net_utils._is_blocked_ip('1.1.1.1')
