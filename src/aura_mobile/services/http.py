"""One HTTP client for the ten services, each with its own API Gateway URL.

The rules are the desktop client's (``aws_docs/apps/mvp-desktop/src/api/http.ts``):

* every call carries ``Authorization: Bearer <access token>``. The JWT authorizer refuses an id token;
* ``X-Time-Zone`` is the phone's IANA zone, and ``x-aura-app`` names this client;
* a 401 refreshes the session once and tries again. A second 401 ends the session;
* a 503 from API Gateway itself (a body with no ``error``) is throttling, so it is retried after 1, 2, 4 and 8 s.
  On dev it is Lambda's: the account runs at most 10 Lambdas at once in us-west-1, so this client keeps at most
  ``MAX_IN_FLIGHT`` calls open and leaves the rest for the backend's own work;
* an error body is ``{"error": {code, message, details}}``, and API Gateway's own errors are ``{"message"}``.
  Both become :class:`ApiError`.

Synchronous and Qt-free, like every service here. The UI runs calls on a worker thread.
"""

from __future__ import annotations

import json as _json
import logging
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Protocol

from aura_mobile.services.config import AppConfig

if TYPE_CHECKING:
    import httpx

log = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 25.0
#: how long an idle connection is kept for the next call
KEEPALIVE_S = 120.0
THROTTLE_DELAYS = (1.0, 2.0, 4.0, 8.0)
#: calls open at once, across every service (dev's Lambda concurrency limit is 10 for the whole account)
MAX_IN_FLIGHT = 6
#: a consent grant this recent covers other calls refused at the same time (no second PUT)
CONSENT_REUSE_S = 30.0
#: after a grant, waits before each new try: Health Data keeps its own copy of the consent, which Identity's
#: ``identity.consent.changed`` event updates a few seconds later (an old "off" copy refuses until then)
CONSENT_WAITS = (1.0, 2.0, 3.0, 4.0)

OFFLINE = "offline"
SIGNED_OUT = "signed_out"


class ApiError(Exception):
    """A refused or failed call. ``code`` is the service's machine code, or ``offline`` / ``http_<status>``."""

    def __init__(self, code: str, message: str, *, status: int = 0, details: Any = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.details = details

    def __repr__(self) -> str:
        return f"ApiError({self.code!r}, {self.status}, {self.message!r})"


class TokenSource(Protocol):
    """What :class:`~aura_mobile.services.auth.AuthSession` gives this client."""

    def access_token(self) -> str: ...

    def force_refresh(self) -> str: ...

    def rejected(self) -> None: ...


@dataclass
class Reply:
    status: int
    body: Any
    headers: Mapping[str, str] = field(default_factory=dict)

    @property
    def etag(self) -> str | None:
        for key, value in self.headers.items():
            if key.lower() == "etag":
                return value
        return None


def local_time_zone() -> str:
    """The phone's IANA zone. ``tzlocal`` maps Windows' own names ("India Standard Time") to IANA ones."""
    import os

    override = os.environ.get("AURA_MOBILE_TZ")
    if override:
        return override
    try:
        from tzlocal import get_localzone_name

        name = get_localzone_name()
        if name:
            return name
    except Exception:
        log.warning("Could not read the local time zone; using UTC", exc_info=True)
    return "UTC"


def if_match(version: int | str) -> str:
    """``ifMatchHeader``: a profile version 7 is sent as ``"v7"``."""
    return f'"v{version}"' if isinstance(version, int) else version


class ServiceClient:
    def __init__(
        self,
        config: AppConfig,
        session: TokenSource,
        *,
        transport: httpx.BaseTransport | None = None,
        timeout: float = DEFAULT_TIMEOUT,
        sleep: Callable[[float], None] = time.sleep,
        time_zone: str | None = None,
    ) -> None:
        self.config = config
        self.session = session
        self._transport = transport
        self._timeout = timeout
        self._sleep = sleep
        self.time_zone = time_zone or local_time_zone()
        self._client: httpx.Client | None = None
        self._lock = threading.Lock()
        self._gate = threading.BoundedSemaphore(MAX_IN_FLIGHT)
        #: called once when a call is refused with 403 ``consent_required``; the call is then sent again (CONSENT_WAITS)
        self.on_consent_required: Callable[[], None] | None = None
        self._consent_lock = threading.Lock()
        self._consent_at = 0.0

    def _http(self) -> httpx.Client:
        import httpx

        with self._lock:
            if self._client is None:
                # Keep connections open between taps: a new one to us-west-1 costs another 0.5–0.7 s (TCP + TLS).
                limits = httpx.Limits(max_connections=40, max_keepalive_connections=20,
                                      keepalive_expiry=KEEPALIVE_S)
                self._client = httpx.Client(transport=self._transport, timeout=self._timeout, limits=limits)
            return self._client

    def warm(self) -> int:
        """Open a connection to every service now (``GET /v1/health``, public), so the first real call is fast."""
        from concurrent.futures import ThreadPoolExecutor

        def ping(service: str) -> bool:
            try:
                self.request(service, "GET", "/v1/health", auth=False)
            except Exception:
                return False
            return True

        services = list(self.config.services)
        with ThreadPoolExecutor(max_workers=len(services) or 1) as pool:
            return sum(pool.map(ping, services))

    def close(self) -> None:
        with self._lock:
            if self._client is not None:
                self._client.close()
                self._client = None

    # -- requests -----------------------------------------------------------

    def request(
        self,
        service: str,
        method: str,
        path: str,
        *,
        json: Any = None,
        params: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
        auth: bool = True,
    ) -> Reply:
        """One call. Returns the reply for a 2xx; raises :class:`ApiError` for anything else."""
        url = self.config.base_url(service) + path
        clean_params = {k: v for k, v in (params or {}).items() if v is not None}
        refreshed = False
        consent_tries = -1  # -1: health data not turned on by this call yet
        throttled = 0
        while True:
            sent = self._headers(headers, auth=auth, fresh=refreshed)
            with self._gate:  # held only while the call is open, not while waiting to retry
                reply = self._send(method, url, json, clean_params, sent)
            if reply.status == 401 and auth and not refreshed:
                log.info("%s %s: 401, refreshing the session once", method, path)
                self.session.force_refresh()
                refreshed = True
                continue
            if reply.status == 503 and not _has_error_body(reply.body) and throttled < len(THROTTLE_DELAYS):
                delay = THROTTLE_DELAYS[throttled]
                throttled += 1
                log.info("%s %s: throttled, retrying in %.0f s", method, path, delay)
                self._sleep(delay)
                continue
            if 200 <= reply.status < 300:
                return reply
            if reply.status == 401 and auth:
                self.session.rejected()
            error = _error(reply)
            if error.code == "consent_required":
                if consent_tries < 0 and self._grant_consent():
                    consent_tries = 0
                if 0 <= consent_tries < len(CONSENT_WAITS):
                    delay = CONSENT_WAITS[consent_tries]
                    consent_tries += 1
                    log.info("%s %s: health data turned on; sending again in %.0f s", method, path, delay)
                    self._sleep(delay)
                    continue
            level = logging.INFO if reply.status == 404 else logging.WARNING
            log.log(level, "%s %s %s: %s %s", service, method, path, reply.status, error.code)
            raise error

    def _grant_consent(self) -> bool:
        """Turn health data on (the demo's default). Calls refused at the same moment share one grant."""
        if self.on_consent_required is None:
            return False
        with self._consent_lock:
            if time.monotonic() - self._consent_at < CONSENT_REUSE_S:
                return True
            try:
                self.on_consent_required()
            except Exception as exc:
                log.warning("Turning health data on failed: %s", exc)
                return False
            self._consent_at = time.monotonic()
            return True

    def get(self, service: str, path: str, **kw: Any) -> Any:
        return self.request(service, "GET", path, **kw).body

    def post(self, service: str, path: str, json: Any = None, **kw: Any) -> Any:
        return self.request(service, "POST", path, json=json, **kw).body

    def put(self, service: str, path: str, json: Any = None, **kw: Any) -> Any:
        return self.request(service, "PUT", path, json=json, **kw).body

    def delete(self, service: str, path: str, **kw: Any) -> Any:
        return self.request(service, "DELETE", path, **kw).body

    def post_form(self, url: str, fields: Mapping[str, str], file_bytes: bytes, *, filename: str) -> int:
        """A presigned S3 POST: the form fields, then ``file``. No Authorization; the fields are the grant."""
        import httpx

        try:
            response = self._http().post(
                url,
                data=dict(fields),
                files={"file": (filename, file_bytes, "application/json")},
            )
        except httpx.TransportError as exc:
            raise ApiError(OFFLINE, f"Upload failed: {exc.__class__.__name__}") from None
        if response.status_code not in (200, 201, 204):
            raise ApiError(
                f"http_{response.status_code}",
                f"The upload was refused (HTTP {response.status_code}).",
                status=response.status_code,
            )
        return response.status_code

    # -- internals ----------------------------------------------------------

    def _headers(self, extra: Mapping[str, str] | None, *, auth: bool, fresh: bool) -> dict[str, str]:
        headers = {
            "Accept": "application/json",
            "X-Time-Zone": self.time_zone,
            "x-aura-app": f"{self.config.platform}-demo/{self.config.app_version}",
        }
        if auth:
            token = self.session.access_token()
            headers["Authorization"] = f"Bearer {token}"
        headers.update(extra or {})
        return headers

    def _send(
        self, method: str, url: str, body: Any, params: Mapping[str, Any], headers: dict[str, str]
    ) -> Reply:
        import httpx

        content = None
        if body is not None:
            content = _json.dumps(body).encode("utf-8")
            headers = {**headers, "Content-Type": "application/json"}
        try:
            response = self._http().request(method, url, content=content, params=params, headers=headers)
        except httpx.TransportError as exc:
            log.warning("%s %s failed: %s", method, url, exc.__class__.__name__)
            raise ApiError(OFFLINE, "Can't reach Aura. Check your connection.") from None
        parsed: Any = None
        if response.content:
            try:
                parsed = response.json()
            except ValueError:
                parsed = response.text
        return Reply(response.status_code, parsed, dict(response.headers))


def _has_error_body(body: Any) -> bool:
    return isinstance(body, dict) and isinstance(body.get("error"), dict)


def _error(reply: Reply) -> ApiError:
    body = reply.body
    if _has_error_body(body):
        err = body["error"]
        message = str(err.get("message") or f"HTTP {reply.status}")
        if message.startswith("The identity service refused"):
            # Health Data could not read the health-data consent from Identity (dev Identity lacks /v1/me/consent).
            message = ("Health data consent isn't available for this account yet, "
                       "so band data, check-ins and water are refused.")
        return ApiError(
            str(err.get("code") or f"http_{reply.status}"),
            message,
            status=reply.status,
            details=err.get("details"),
        )
    message = body.get("message") if isinstance(body, dict) else None
    return ApiError(f"http_{reply.status}", str(message or f"HTTP {reply.status}"), status=reply.status)
