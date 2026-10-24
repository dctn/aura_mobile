"""Signing in with an emailed code: a copy of the Windows app's ``aura.services.clarus_auth``.

Copied, not imported, so this demo stays a standalone project. Only the config type, the secrets module, the
Credential Manager prefix and the offline sentence changed. The flow:

* **Someone with an account** gets an 8-digit sign-in code
  (``InitiateAuth`` USER_AUTH → EMAIL_OTP → ``RespondToAuthChallenge``).
* **Someone new** is signed up on the spot with no password and gets a 6-digit
  confirmation code (``SignUp`` → ``ConfirmSignUp``), whose session signs them in.
* **Someone who signed up but never confirmed** is sent a fresh confirmation code.

Cognito is spoken to directly over httpx: one JSON POST per call with ``X-Amz-Target``. The android app client is
public, so there are no AWS credentials and no client secret.
"""

from __future__ import annotations

import base64
import json
import logging
import secrets as _random
import threading
from collections.abc import Callable
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING, Any, Literal

from aura_mobile.services import secrets
from aura_mobile.services.config import AppConfig

if TYPE_CHECKING:
    import httpx

log = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 20.0

#: ``Step.codeLength`` (CognitoClient.swift:63): a sign-in code is 8 digits, a
#: sign-up confirmation 6.
SIGN_IN_CODE_LENGTH = 8
CONFIRM_CODE_LENGTH = 6

#: ``AuthSession.refreshMargin`` — refresh when less than this is left.
REFRESH_MARGIN = timedelta(seconds=120)

#: ``AuthError.unsupportedStep("CONFIRMED_WITHOUT_SESSION")`` — the account is
#: confirmed but no session came back; a sign-in code completes it.
CONFIRMED_WITHOUT_SESSION = "CONFIRMED_WITHOUT_SESSION"

# The machine codes this module adds beside Cognito's own `__type`s.
INVALID_EMAIL = "invalid_email"
OFFLINE = "offline"
UNSUPPORTED_STEP = "unsupported_step"
SESSION_ENDED = "session_ended"

#: The two Cognito refusals that mean a refresh token can never work again
#: (CognitoClient.swift:186-188, AuthSession.swift:185-193).
_SESSION_ENDING = ("NotAuthorizedException", "UserNotFoundException")


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------


class CognitoError(Exception):
    """``AuthError`` — why signing in (or staying signed in) did not work.

    ``code`` is Cognito's ``__type`` with its namespace stripped
    (``CodeMismatchException``), or one of this module's own
    (:data:`OFFLINE`, :data:`INVALID_EMAIL`, :data:`UNSUPPORTED_STEP`,
    :data:`SESSION_ENDED`). ``message`` is the sentence a person reads —
    ``str(error)`` is the same text.
    """

    def __init__(self, code: str, message: str, *, step: str | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        #: For :data:`UNSUPPORTED_STEP`, the step Cognito asked for.
        self.step = step

    @property
    def is_session_ended(self) -> bool:
        """Whether a *refresh* that failed this way ends the session for good.

        Only Cognito refusing the refresh token or its user does — anything
        else (offline, throttled, Cognito down, an answer that is not Cognito's)
        says nothing about the token, and clearing it would force a new emailed
        code on a working network (AuthSession.swift:195-206).
        """
        return self.code in (*_SESSION_ENDING, SESSION_ENDED)

    def __repr__(self) -> str:
        return f"CognitoError({self.code!r}, {self.message!r})"


# `AuthError.errorDescription` (AuthError.swift:23-47), verbatim.
_WRONG_CODE = "That code isn't right. Check the email and try again."
_EXPIRED_CODE = "That code has expired. Send a new one."
_WINDOW_CLOSED = "The sign-in took too long. Send a new code."
_TOO_MANY = "Too many attempts. Wait a minute, then try again."
_DISABLED = "This account has been turned off."
SESSION_ENDED_MESSAGE = "Your session has ended. Sign in again."
OFFLINE_MESSAGE = "Can't reach Aura. Check your connection."
INVALID_EMAIL_MESSAGE = "Enter a valid email address."
_DELIVERY_FAILED = "The code email couldn't be sent. Check the address and try again."


def _unsupported(step: str) -> CognitoError:
    return CognitoError(
        UNSUPPORTED_STEP,
        "Sign-in needs a step this version of the app doesn't support "
        f"({step}). Update the app.",
        step=step,
    )


def describe(raw_type: str, message: str) -> str:
    """``AuthError.fromCognito`` (AuthError.swift:58-81) — the sentence for a refusal."""
    kind = raw_type.split("#")[-1]
    lower = message.lower()
    if kind == "CodeMismatchException":
        return _WRONG_CODE
    if kind == "ExpiredCodeException":
        return _EXPIRED_CODE
    if kind == "CodeDeliveryFailureException":
        return _DELIVERY_FAILED
    if kind in ("LimitExceededException", "TooManyRequestsException", "TooManyFailedAttemptsException"):
        return _TOO_MANY
    if kind == "NotAuthorizedException":
        if "session" in lower:
            return _WINDOW_CLOSED
        if "disabled" in lower:
            return _DISABLED
        if "refresh token" in lower or "revoked" in lower:
            return SESSION_ENDED_MESSAGE
        if "attempts" in lower:
            return _TOO_MANY
    if kind == "InvalidParameterException" and "email" in lower:
        return INVALID_EMAIL_MESSAGE
    return message or "Sign-in didn't work. Try again."


class _Refused(Exception):
    """``AuthCallError.cognito`` — Cognito's raw refusal, before it is described."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(f"{kind}: {message}")
        self.kind = kind.split("#")[-1]
        self.message = message

    def public(self) -> CognitoError:
        return CognitoError(self.kind, describe(self.kind, self.message))


# ---------------------------------------------------------------------------
# Tokens
# ---------------------------------------------------------------------------


def jwt_claims(token: object) -> dict[str, Any]:
    """``JWT.claims`` — a JWT's payload, unverified; ``{}`` for anything malformed.

    The signature is not checked here and does not need to be: the API checks
    it on every request, and the client only reads its own identity and expiry.
    """
    if not isinstance(token, str):
        return {}
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    body = parts[1]
    try:
        decoded = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))
    except (ValueError, TypeError):
        return {}
    return decoded if isinstance(decoded, dict) else {}


def jwt_expiry(token: object) -> datetime | None:
    exp = jwt_claims(token).get("exp")
    if isinstance(exp, bool) or not isinstance(exp, int | float):
        return None
    return datetime.fromtimestamp(float(exp), tz=UTC)


@dataclass(frozen=True)
class Tokens:
    """``AuthTokens`` plus ``AuthUser`` — what Cognito issued, and whose it is."""

    access_token: str
    id_token: str
    refresh_token: str
    #: When the access token stops being accepted. UTC, aware.
    expires_at: datetime
    #: The Cognito ``sub``: the key everything this person owns is stored under.
    sub: str
    email: str

    @classmethod
    def issued(
        cls,
        access_token: str,
        id_token: str,
        refresh_token: str,
        expires_in: object = None,
        *,
        now: datetime | None = None,
    ) -> Tokens:
        """Build from Cognito's ``AuthenticationResult``; identity from the id token."""
        claims = jwt_claims(id_token)
        expires = jwt_expiry(access_token)
        if expires is None:
            seconds = expires_in if isinstance(expires_in, int) and expires_in > 0 else 3600
            expires = (now or datetime.now(UTC)) + timedelta(seconds=seconds)
        sub = claims.get("sub")
        email = claims.get("email")
        return cls(
            access_token=access_token,
            id_token=id_token,
            refresh_token=refresh_token,
            expires_at=expires,
            sub=sub if isinstance(sub, str) else "",
            email=email.lower() if isinstance(email, str) else "",
        )

    def expires_within(self, margin: timedelta, now: datetime | None = None) -> bool:
        """``expires(within:)`` — true when less than ``margin`` is left."""
        return self.expires_at - (now or datetime.now(UTC)) < margin

    def to_json(self) -> str:
        """The stored form — ``AuthTokens``' Codable keys, never logged."""
        return json.dumps({
            "idToken": self.id_token,
            "accessToken": self.access_token,
            "refreshToken": self.refresh_token,
            "expiresAt": self.expires_at.timestamp(),
        })

    @classmethod
    def from_json(cls, text: str) -> Tokens | None:
        """Read what :meth:`to_json` wrote; ``None`` for anything unusable."""
        try:
            data = json.loads(text)
            access, id_token, refresh = data["accessToken"], data["idToken"], data["refreshToken"]
            expires = datetime.fromtimestamp(float(data["expiresAt"]), tz=UTC)
        except (ValueError, TypeError, KeyError, OverflowError, OSError):
            return None
        if not all(isinstance(v, str) and v for v in (access, id_token, refresh)):
            return None
        tokens = cls.issued(access, id_token, refresh)
        # The stored expiry wins: it may have been pulled in by a rejection.
        return Tokens(access, id_token, refresh, expires, tokens.sub, tokens.email)


# ---------------------------------------------------------------------------
# The steps
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class AuthStep:
    """``CognitoClient.Step`` — where a sign-in stands after the email was entered."""

    email: str
    #: ``sign_in`` — an 8-digit sign-in code was emailed; ``confirm_sign_up`` —
    #: a 6-digit confirmation code was emailed, this is a new account.
    kind: Literal["sign_in", "confirm_sign_up"]
    session: str | None
    code_length: int
    #: ``CODE_DELIVERY_DESTINATION`` — the masked address the code went to.
    destination: str = ""
    #: A sign-in step that also asked for a confirmation code: the pool hides unconfirmed accounts behind a fake
    #: sign-in challenge, so either a 6-digit confirmation or an 8-digit sign-in code may be the one that arrives.
    also_confirm: bool = False

    @property
    def is_new_account(self) -> bool:
        return self.kind == "confirm_sign_up"


def _sign_in_step(email: str, session: str, destination: str) -> AuthStep:
    return AuthStep(email, "sign_in", session, SIGN_IN_CODE_LENGTH, destination or email)


def _confirm_step(email: str, session: str | None, destination: str) -> AuthStep:
    return AuthStep(email, "confirm_sign_up", session, CONFIRM_CODE_LENGTH, destination or email)


def looks_like_email(email: str) -> bool:
    """``looksLikeEmail`` (CognitoClient.swift:273-276)."""
    parts = email.split("@")
    return len(parts) == 2 and bool(parts[0]) and "." in parts[1] and " " not in email


def is_already_confirmed(message: str) -> bool:
    """``isAlreadyConfirmed`` — Cognito's two ways of saying so (:281-284)."""
    lower = message.lower()
    return "already confirmed" in lower or "current status is confirmed" in lower


def random_password() -> str:
    """``randomPassword`` — for a pool that still insists on one at sign-up."""
    return "Cl4!" + base64.b64encode(_random.token_bytes(24)).decode("ascii")


# ---------------------------------------------------------------------------
# The client
# ---------------------------------------------------------------------------


class CognitoClient:
    """``CognitoClient`` — stateless; :class:`AuthSession` owns the tokens."""

    def __init__(
        self,
        config: AppConfig,
        *,
        transport: httpx.BaseTransport | None = None,
        timeout: float = DEFAULT_TIMEOUT,
    ) -> None:
        self.config = config
        self._transport = transport
        self._timeout = timeout
        self._client: httpx.Client | None = None
        self._client_lock = threading.Lock()

    # -- the steps --------------------------------------------------------

    def start(self, email: str) -> AuthStep:
        """``start(email:)`` — send a code, creating the account first if there is none."""
        address = email.strip().lower()
        if not looks_like_email(address):
            raise CognitoError(INVALID_EMAIL, INVALID_EMAIL_MESSAGE)
        try:
            try:
                return self._with_confirmation(self._request_sign_in_code(address))
            except _Refused as refused:
                if refused.kind == "UserNotFoundException":
                    log.info("No account for this email; signing up")
                    try:
                        return self._sign_up(address)
                    except _Refused as exists:
                        # Hidden earlier: the account exists but was never confirmed.
                        if exists.kind != "UsernameExistsException":
                            raise
                        log.info("Account exists but is unconfirmed; resending the code")
                        return self._resend_confirmation(address)
                if refused.kind == "UserNotConfirmedException":
                    log.info("Account exists but was never confirmed; resending the code")
                    return self._resend_confirmation(address)
                raise
        except _Refused as refused:
            raise refused.public() from None

    def finish(self, step: AuthStep, code: str) -> Tokens:
        """``finish(_:code:)`` — complete the sign-in with the code the person typed."""
        digits = "".join(ch for ch in code if ch.isdigit())
        if step.kind == "sign_in" and step.also_confirm and len(digits) == CONFIRM_CODE_LENGTH:
            log.info("A confirmation code arrived: this account was never confirmed")
            step = replace(step, kind="confirm_sign_up", session=None, code_length=CONFIRM_CODE_LENGTH)
        try:
            if step.kind == "sign_in":
                result = self._call("RespondToAuthChallenge", {
                    "ChallengeName": "EMAIL_OTP",
                    "ClientId": self.config.client_id,
                    "Session": step.session,
                    "ChallengeResponses": {"USERNAME": step.email, "EMAIL_OTP_CODE": digits},
                })
                return self._tokens(result, "EMAIL_OTP")

            try:
                confirmed = self._call("ConfirmSignUp", {
                    "ClientId": self.config.client_id,
                    "Username": step.email,
                    "ConfirmationCode": digits,
                })
            except _Refused as refused:
                if is_already_confirmed(refused.message):
                    # An earlier try confirmed the account, then the sign-in
                    # after it failed. A sign-in code completes it (:118-124).
                    log.info("Account already confirmed; a sign-in code completes it")
                    raise _unsupported(CONFIRMED_WITHOUT_SESSION) from None
                raise
            log.info("New account confirmed")
            session = confirmed.get("Session")
            if not isinstance(session, str) or not session:
                # Older pools return no session; a sign-in code completes it.
                raise _unsupported(CONFIRMED_WITHOUT_SESSION)
            result = self._call("InitiateAuth", {
                "AuthFlow": "USER_AUTH",
                "ClientId": self.config.client_id,
                "Session": session,
                "AuthParameters": {"USERNAME": step.email},
            })
            return self._tokens(result, "SIGN_UP_SESSION")
        except _Refused as refused:
            raise refused.public() from None

    def resend(self, step: AuthStep) -> AuthStep:
        """``resend(_:)`` — a new code for the same step."""
        try:
            if step.kind == "sign_in":
                return self._with_confirmation(self._request_sign_in_code(step.email))
            return self._resend_confirmation(step.email)
        except _Refused as refused:
            raise refused.public() from None

    def sign_in_password(self, email: str, password: str) -> Tokens:
        """USER_AUTH with the PASSWORD first factor, in one call: for the shared demo account (demo_website), which
        has no inbox to receive a code. The app's own sign-in never uses it."""
        try:
            result = self._call("InitiateAuth", {
                "AuthFlow": "USER_AUTH",
                "ClientId": self.config.client_id,
                "AuthParameters": {"USERNAME": email.strip().lower(), "PREFERRED_CHALLENGE": "PASSWORD",
                                   "PASSWORD": password},
            })
        except _Refused as refused:
            raise refused.public() from None
        return self._tokens(result, "PASSWORD")

    def refresh(self, refresh_token: str) -> Tokens:
        """``refresh(_:)`` — REFRESH_TOKEN_AUTH. The refresh token is not rotated, so it is kept.

        Raises a :class:`CognitoError` whose :attr:`~CognitoError.is_session_ended`
        is true only when Cognito refused this token or its user.
        """
        try:
            result = self._call("InitiateAuth", {
                "AuthFlow": "REFRESH_TOKEN_AUTH",
                "ClientId": self.config.client_id,
                "AuthParameters": {"REFRESH_TOKEN": refresh_token},
            })
        except _Refused as refused:
            public = refused.public()
            if refused.kind in _SESSION_ENDING:
                # Every NotAuthorizedException here is a refusal of this token,
                # whatever its wording (:183-188); keep "turned off" when it says so.
                message = public.message if public.message == _DISABLED else SESSION_ENDED_MESSAGE
                raise CognitoError(refused.kind, message) from None
            raise public from None
        auth = result.get("AuthenticationResult")
        if not isinstance(auth, dict):
            # A 200 without tokens is not Cognito refusing the token; something
            # else answered, so it must not end the session (:161-165).
            raise _unsupported(str(result.get("ChallengeName") or "REFRESH_WITHOUT_TOKENS"))
        tokens = Tokens.issued(
            str(auth.get("AccessToken") or ""),
            str(auth.get("IdToken") or ""),
            str(auth.get("RefreshToken") or refresh_token),
            auth.get("ExpiresIn"),
        )
        if not tokens.sub:
            raise _unsupported("TOKENS_WITHOUT_IDENTITY")
        return tokens

    def revoke(self, refresh_token: str) -> bool:
        """``revoke(_:)`` — best effort: signing out works offline. Never raises."""
        try:
            self._call("RevokeToken", {"ClientId": self.config.client_id, "Token": refresh_token})
        except Exception as exc:
            log.info("Could not revoke the refresh token (it will expire on its own): %s",
                     getattr(exc, "kind", exc.__class__.__name__))
            return False
        log.info("Refresh token revoked")
        return True

    def close(self) -> None:
        with self._client_lock:
            if self._client is not None:
                self._client.close()
                self._client = None

    # -- calls --------------------------------------------------------------

    def _request_sign_in_code(self, email: str) -> AuthStep:
        result = self._call("InitiateAuth", {
            "AuthFlow": "USER_AUTH",
            "ClientId": self.config.client_id,
            "AuthParameters": {"USERNAME": email, "PREFERRED_CHALLENGE": "EMAIL_OTP"},
        })
        if result.get("ChallengeName") == "SELECT_CHALLENGE" and result.get("Session"):
            # The pool hides unknown emails (preventUserExistenceErrors): instead of UserNotFoundException it offers
            # password challenges only, and picking EMAIL_OTP is then refused. Both mean "no usable account".
            offered = result.get("AvailableChallenges")
            if isinstance(offered, list) and "EMAIL_OTP" not in offered:
                raise _Refused("UserNotFoundException", "No account can take an email code")
            try:
                result = self._call("RespondToAuthChallenge", {
                    "ChallengeName": "SELECT_CHALLENGE",
                    "ClientId": self.config.client_id,
                    "Session": result["Session"],
                    "ChallengeResponses": {"USERNAME": email, "ANSWER": "EMAIL_OTP"},
                })
            except _Refused as refused:
                if "challenge is not available" in refused.message.lower():
                    raise _Refused("UserNotFoundException", refused.message) from None
                raise
        session = result.get("Session")
        if result.get("ChallengeName") != "EMAIL_OTP" or not isinstance(session, str) or not session:
            raise _unsupported(str(result.get("ChallengeName") or "NO_CHALLENGE"))
        log.info("Sign-in code requested")
        params = result.get("ChallengeParameters")
        destination = params.get("CODE_DELIVERY_DESTINATION") if isinstance(params, dict) else None
        return _sign_in_step(email, session, str(destination or ""))

    def _with_confirmation(self, step: AuthStep) -> AuthStep:
        """Also ask for a confirmation code. For a confirmed account Cognito answers this with a fake success and
        sends nothing; for an unconfirmed one the sign-in challenge was the fake. Exactly one real email goes out."""
        try:
            self._call("ResendConfirmationCode", {"ClientId": self.config.client_id, "Username": step.email})
        except _Refused as refused:
            log.info("No confirmation code needed (%s)", refused.kind)
            return step
        return replace(step, also_confirm=True)

    def _sign_up(self, email: str) -> AuthStep:
        body: dict[str, Any] = {
            "ClientId": self.config.client_id,
            "Username": email,
            "UserAttributes": [{"Name": "email", "Value": email}],
        }
        try:
            result = self._call("SignUp", body)
        except _Refused as refused:
            if refused.kind != "InvalidPasswordException" and "password" not in refused.message.lower():
                raise
            # A pool that still insists on a password gets one nobody knows or
            # needs: every sign-in after this uses an emailed code (:224-235).
            log.info("Pool requires a password at sign-up; using a random one")
            result = self._call("SignUp", {**body, "Password": random_password()})
        session = result.get("Session")
        return _confirm_step(
            email, session if isinstance(session, str) and session else None, _destination(result)
        )

    def _resend_confirmation(self, email: str) -> AuthStep:
        try:
            result = self._call(
                "ResendConfirmationCode", {"ClientId": self.config.client_id, "Username": email}
            )
        except _Refused as refused:
            if not is_already_confirmed(refused.message):
                raise
            # Confirmed, but the sign-in after it never happened: a sign-in code
            # finishes that, a confirmation code cannot (:245-250).
            log.info("Account already confirmed; sending a sign-in code instead")
            return self._request_sign_in_code(email)
        return _confirm_step(email, None, _destination(result))

    def _tokens(self, result: dict[str, Any], step: str) -> Tokens:
        auth = result.get("AuthenticationResult")
        if not isinstance(auth, dict) or not auth.get("RefreshToken"):
            raise _unsupported(str(result.get("ChallengeName") or step))
        tokens = Tokens.issued(
            str(auth.get("AccessToken") or ""),
            str(auth.get("IdToken") or ""),
            str(auth["RefreshToken"]),
            auth.get("ExpiresIn"),
        )
        if not tokens.sub:
            # `AuthSession.begin` refuses tokens without an identity (:109).
            raise _unsupported("TOKENS_WITHOUT_IDENTITY")
        log.info("Signed in")
        return tokens

    def _http(self) -> httpx.Client:
        import httpx

        with self._client_lock:
            if self._client is None:
                self._client = httpx.Client(transport=self._transport, timeout=self._timeout)
            return self._client

    def _call(self, target: str, body: dict[str, Any]) -> dict[str, Any]:
        """One Cognito call. Raises :class:`_Refused` for a refusal, ``offline`` for no answer."""
        import httpx

        try:
            response = self._http().post(
                self.config.cognito_endpoint,
                content=json.dumps(body).encode("utf-8"),
                headers={
                    "Content-Type": "application/x-amz-json-1.1",
                    "X-Amz-Target": f"AWSCognitoIdentityProviderService.{target}",
                },
            )
        except httpx.TransportError as exc:
            log.warning("%s failed: offline (%s)", target, exc.__class__.__name__)
            raise CognitoError(OFFLINE, OFFLINE_MESSAGE) from None
        try:
            data = response.json() if response.content else {}
        except ValueError:
            data = {}
        if not isinstance(data, dict):
            data = {}
        if not 200 <= response.status_code < 300:
            kind = str(data.get("__type") or f"HTTP{response.status_code}")
            message = str(data.get("message") or data.get("Message") or "")
            log.warning("%s refused: %s", target, kind.split("#")[-1])
            raise _Refused(kind, message)
        return data


def _destination(result: dict[str, Any]) -> str:
    details = result.get("CodeDeliveryDetails")
    value = details.get("Destination") if isinstance(details, dict) else None
    return str(value or "")


# ---------------------------------------------------------------------------
# Where tokens live
# ---------------------------------------------------------------------------


class TokenStore:
    """``KeychainTokenStore`` — Windows Credential Manager, one entry per stage.

    A dev build never reads a production session. The value is a JWT triple,
    far past Credential Manager's 1,280-character cap, which
    :mod:`aura_mobile.services.secrets` already splits across numbered entries.
    """

    PREFIX = "aura.mobile.session."

    def __init__(self, stage: str) -> None:
        self.stage = stage
        self.key = f"{self.PREFIX}{stage}"

    def load(self) -> Tokens | None:
        text = secrets.get_secret(self.key)
        if not text:
            return None
        tokens = Tokens.from_json(text)
        if tokens is None:
            log.warning("Saved %s session unreadable; treating as signed out", self.stage)
        return tokens

    def save(self, tokens: Tokens) -> bool:
        return secrets.set_secret(self.key, tokens.to_json())

    def clear(self) -> bool:
        return secrets.delete_secret(self.key)


class MemoryTokenStore:
    """``InMemoryTokenStore`` — for tests."""

    def __init__(self, tokens: Tokens | None = None) -> None:
        self.tokens = tokens

    def load(self) -> Tokens | None:
        return self.tokens

    def save(self, tokens: Tokens) -> bool:
        self.tokens = tokens
        return True

    def clear(self) -> bool:
        self.tokens = None
        return True


# ---------------------------------------------------------------------------
# The session
# ---------------------------------------------------------------------------


class AuthSession:
    """``AuthSession`` — restores the session, keeps it fresh, ends it.

    Thread-safe: the API calls :meth:`access_token` from worker threads, and
    however many ask at once, one refresh runs. It never reports signed in with
    tokens that cannot work:

    * a token close to expiry is refreshed before it is handed out;
    * a refresh Cognito **refuses** ends the session — the tokens are cleared
      here and in the store, and :attr:`on_ended` is called (from whatever
      thread noticed);
    * a refresh that fails any other way keeps the session and raises, so the
      request fails as offline and is tried again later.
    """

    def __init__(
        self,
        client: CognitoClient,
        store: TokenStore | MemoryTokenStore,
        *,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.client = client
        self.store = store
        self._clock = clock or (lambda: datetime.now(UTC))
        self._lock = threading.RLock()
        self._refresh_lock = threading.Lock()
        self._tokens: Tokens | None = None
        #: Bumped whenever a session begins or ends, so a refresh in flight
        #: across a sign-out cannot bring the old account back (:37-40).
        self._epoch = 0
        #: Called with no arguments when the server ended the session.
        self.on_ended: Callable[[], None] | None = None

    # -- state ----------------------------------------------------------------

    @property
    def tokens(self) -> Tokens | None:
        with self._lock:
            return self._tokens

    @property
    def is_signed_in(self) -> bool:
        return self.tokens is not None

    # -- lifecycle ------------------------------------------------------------

    def restore(self) -> Tokens | None:
        """``restore()`` — read the saved session. Reads Credential Manager: call off the UI thread."""
        try:
            tokens = self.store.load()
        except Exception:
            log.warning("Saved session unreadable", exc_info=True)
            tokens = None
        if tokens is not None and not tokens.sub:
            tokens = None
        with self._lock:
            self._tokens = tokens
            self._epoch += 1
        if tokens is not None:
            log.info("Session restored")
        return tokens

    def begin(self, tokens: Tokens) -> None:
        """``begin(with:)`` — start a session with freshly issued tokens."""
        if not tokens.sub:
            raise _unsupported("TOKENS_WITHOUT_IDENTITY")
        with self._lock:
            self._epoch += 1
            self._tokens = tokens
        try:
            # The session still works until quit if this fails; say so in the
            # log rather than failing the sign-in (:113-117).
            if not self.store.save(tokens):
                log.warning("Session not saved")
        except Exception:
            log.warning("Session not saved", exc_info=True)

    def clear(self) -> Tokens | None:
        """End the session here. Returns the tokens that were held, for a revoke."""
        with self._lock:
            held = self._tokens
            self._tokens = None
            self._epoch += 1
        try:
            self.store.clear()
        except Exception:
            log.warning("Could not clear the saved session", exc_info=True)
        return held

    def rejected(self) -> None:
        """The API refused a token that was just refreshed: the session is over."""
        if self.clear() is not None:
            self._ended()

    # -- tokens ---------------------------------------------------------------

    def access_token(self) -> str:
        """``accessToken()`` — valid now, refreshed first when about to expire."""
        current = self.tokens
        if current is None:
            raise CognitoError(SESSION_ENDED, SESSION_ENDED_MESSAGE)
        if not current.expires_within(REFRESH_MARGIN, self._clock()):
            return current.access_token
        return self._refreshed(current).access_token

    def force_refresh(self) -> str:
        """``tokenRejected()`` + ``accessToken()`` — the API said 401; refresh now."""
        current = self.tokens
        if current is None:
            raise CognitoError(SESSION_ENDED, SESSION_ENDED_MESSAGE)
        return self._refreshed(current, force=True).access_token

    def _refreshed(self, seen: Tokens, *, force: bool = False) -> Tokens:
        with self._refresh_lock:
            with self._lock:
                current = self._tokens
                epoch = self._epoch
            if current is None:
                raise CognitoError(SESSION_ENDED, SESSION_ENDED_MESSAGE)
            # Someone else refreshed while this caller waited for the lock:
            # everyone waiting gets that one answer, and one refresh runs.
            if current is not seen and (
                force or not current.expires_within(REFRESH_MARGIN, self._clock())
            ):
                return current
            try:
                fresh = self.client.refresh(current.refresh_token)
            except CognitoError as exc:
                if exc.is_session_ended:
                    log.warning("Refresh refused; signing out")
                    ended = False
                    with self._lock:
                        if epoch == self._epoch:
                            self._tokens = None
                            self._epoch += 1
                            ended = True
                    if ended:
                        try:
                            self.store.clear()
                        except Exception:
                            log.warning("Could not clear the saved session", exc_info=True)
                        self._ended()
                else:
                    log.info("Refresh failed; keeping the session (%s)", exc.code)
                raise
            with self._lock:
                if epoch != self._epoch:
                    # Signed out, or in again, while the refresh was in flight:
                    # saving these would bring back the account that left.
                    raise CognitoError(SESSION_ENDED, SESSION_ENDED_MESSAGE)
                self._tokens = fresh
            try:
                self.store.save(fresh)
            except Exception:
                log.warning("Refreshed session not saved", exc_info=True)
            log.info("Session refreshed")
            return fresh

    def _ended(self) -> None:
        callback = self.on_ended
        if callback is not None:
            try:
                callback()
            except Exception:
                log.warning("Session-ended listener failed", exc_info=True)


__all__ = [
    "CONFIRMED_WITHOUT_SESSION",
    "CONFIRM_CODE_LENGTH",
    "INVALID_EMAIL",
    "OFFLINE",
    "OFFLINE_MESSAGE",
    "REFRESH_MARGIN",
    "SESSION_ENDED",
    "SESSION_ENDED_MESSAGE",
    "SIGN_IN_CODE_LENGTH",
    "UNSUPPORTED_STEP",
    "AuthSession",
    "AuthStep",
    "CognitoClient",
    "CognitoError",
    "MemoryTokenStore",
    "TokenStore",
    "Tokens",
    "describe",
    "is_already_confirmed",
    "jwt_claims",
    "jwt_expiry",
    "looks_like_email",
    "random_password",
]
