"""``uv run aura-mobile``: open the demo phone against the dev backend.

The saved session (Windows Credential Manager) is restored on a worker after the window shows, so a slow keyring
or a token refresh never holds up the first paint.
"""

from __future__ import annotations

import logging
import os
import sys
import traceback
from logging.handlers import RotatingFileHandler

from PyQt6.QtCore import QThreadPool, QTimer
from PyQt6.QtWidgets import QApplication, QMessageBox

from aura_mobile.services.config import load_config
from aura_mobile.services.sim.runner import state_dir
from aura_mobile.ui.context import AppContext
from aura_mobile.ui.shell import PhoneWindow
from aura_mobile.ui.tasks import run_async

log = logging.getLogger("aura_mobile")

#: a running job past this is a socket read; leave without it (see the Windows app's ``release_background_work``)
SHUTDOWN_GRACE_MS = 1500


def _setup_logging() -> None:
    folder = state_dir()
    folder.mkdir(parents=True, exist_ok=True)
    handler = RotatingFileHandler(folder / "aura-mobile.log", maxBytes=2 * 1024 * 1024, backupCount=2,
                                  encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s",
                                           datefmt="%Y-%m-%d %H:%M:%S"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(handler)
    root.addHandler(logging.StreamHandler())
    # httpx logs every request URL at INFO; keep it to warnings.
    logging.getLogger("httpx").setLevel(logging.WARNING)


def _excepthook(exc_type, exc_value, exc_tb) -> None:
    log.critical("Unhandled exception", exc_info=(exc_type, exc_value, exc_tb))
    summary = "".join(traceback.format_exception_only(exc_type, exc_value)).strip()
    if QApplication.instance() is not None:
        QMessageBox.critical(None, "Aura demo", f"Unexpected error:\n\n{summary}\n\n"
                                                f"Details: {state_dir() / 'aura-mobile.log'}")


def release_background_work(grace_ms: int = SHUTDOWN_GRACE_MS) -> bool:
    """Drop queued jobs and wait briefly for running ones; Qt's global pool otherwise blocks process exit."""
    pool = QThreadPool.globalInstance()
    pool.clear()
    return pool.waitForDone(grace_ms)


def main() -> int:
    _setup_logging()
    sys.excepthook = _excepthook
    config = load_config()
    app = QApplication(sys.argv)
    app.setApplicationName("Aura demo phone")
    app.setStyle("Fusion")

    ctx = AppContext(config)
    window = PhoneWindow(ctx)
    window.show()

    def restored(tokens) -> None:
        if tokens is not None:
            log.info("Restored the saved session")
            ctx.after_sign_in()

    def restore_failed(exc: Exception) -> None:
        log.warning("Could not restore the session: %r", exc)

    QTimer.singleShot(0, lambda: run_async(ctx.session.restore, restored, restore_failed))
    code = app.exec()
    if release_background_work():
        return code
    log.info("Background work outlived the window; leaving without it")
    logging.shutdown()
    os._exit(code)


if __name__ == "__main__":
    sys.exit(main())
