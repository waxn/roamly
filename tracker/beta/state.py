import threading

_local = threading.local()


def is_beta():
    return getattr(_local, 'beta', False)


def set_beta(value):
    _local.beta = value
