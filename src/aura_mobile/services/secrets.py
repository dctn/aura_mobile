"""Windows Credential Manager through ``keyring``, chunked.

Credential Manager caps one secret at 1,280 characters and a Cognito token triple is far longer, so a value is
split across ``key#0``, ``key#1``, … with ``key`` holding the count (the same scheme as the Windows app).
Every call swallows the backend's errors and says so in the log: a missing vault must not stop the app, it only
means the session is not remembered across launches.
"""

from __future__ import annotations

import logging

log = logging.getLogger(__name__)

SERVICE = "AuraMobileDemo"
CHUNK = 1000


def _keyring():
    import keyring

    return keyring


def get_secret(key: str) -> str | None:
    try:
        kr = _keyring()
        count = kr.get_password(SERVICE, key)
        if not count:
            return None
        parts = [kr.get_password(SERVICE, f"{key}#{i}") for i in range(int(count))]
    except Exception:
        log.warning("Could not read %s from Credential Manager", key, exc_info=True)
        return None
    if any(p is None for p in parts):
        return None
    return "".join(p for p in parts if p is not None)


def set_secret(key: str, value: str) -> bool:
    try:
        kr = _keyring()
        delete_secret(key)
        chunks = [value[i : i + CHUNK] for i in range(0, len(value), CHUNK)] or [""]
        for i, chunk in enumerate(chunks):
            kr.set_password(SERVICE, f"{key}#{i}", chunk)
        kr.set_password(SERVICE, key, str(len(chunks)))
    except Exception:
        log.warning("Could not save %s to Credential Manager", key, exc_info=True)
        return False
    return True


def delete_secret(key: str) -> bool:
    try:
        kr = _keyring()
        count = kr.get_password(SERVICE, key)
        if count:
            for i in range(int(count)):
                try:
                    kr.delete_password(SERVICE, f"{key}#{i}")
                except Exception:
                    pass
            kr.delete_password(SERVICE, key)
    except Exception:
        log.warning("Could not delete %s from Credential Manager", key, exc_info=True)
        return False
    return True
