"""The app's public config: the Cognito pool, the android app client and the ten service URLs.

Everything in ``config/aura-dev.json`` is public: the pool and client ids are what any app ships with, and the
URLs answer only to a signed-in person's access token. No AWS credentials are involved anywhere in this app.
``AURA_MOBILE_CONFIG`` points at another file (a test, or another stage later).
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

#: demo/  (src/aura_mobile/services/config.py → parents[3])
PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CONFIG = PROJECT_ROOT / "config" / "aura-dev.json"

SERVICES = (
    "identity",
    "health-data",
    "notification",
    "calendar",
    "capacity",
    "content",
    "habit",
    "planner",
    "session",
    "task",
)


@dataclass(frozen=True)
class AppConfig:
    stage: str
    region: str
    user_pool_id: str
    client_id: str
    services: dict[str, str] = field(default_factory=dict)
    platform: str = "android"
    app_version: str = "0.1.0"
    consent_policy_version: str = "2026-10"

    @property
    def cognito_endpoint(self) -> str:
        return f"https://cognito-idp.{self.region}.amazonaws.com/"

    def base_url(self, service: str) -> str:
        try:
            return self.services[service].rstrip("/")
        except KeyError:
            raise KeyError(f"No URL configured for the {service!r} service") from None

    @classmethod
    def from_dict(cls, data: dict) -> AppConfig:
        return cls(
            stage=str(data["stage"]),
            region=str(data["region"]),
            user_pool_id=str(data["userPoolId"]),
            client_id=str(data["clientId"]),
            services={str(k): str(v) for k, v in dict(data.get("services") or {}).items()},
            platform=str(data.get("platform") or "android"),
            app_version=str(data.get("appVersion") or "0.1.0"),
            consent_policy_version=str(data.get("consentPolicyVersion") or "2026-10"),
        )


def load_config(path: str | os.PathLike[str] | None = None) -> AppConfig:
    """Read the config file. ``utf-8-sig`` because Notepad writes a BOM and strict utf-8 refuses it."""
    chosen = Path(path or os.environ.get("AURA_MOBILE_CONFIG") or DEFAULT_CONFIG)
    config = AppConfig.from_dict(json.loads(chosen.read_text(encoding="utf-8-sig")))
    if config.stage == "prod" or config.region == "ap-south-1":
        # This is a test app: it writes synthetic band days. It must never point at real people's data.
        raise ValueError("The demo app only runs against a dev stage outside ap-south-1.")
    return config
