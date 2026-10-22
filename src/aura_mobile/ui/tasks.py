"""Run blocking service calls off the UI thread; deliver the answer back on it.

Copied from the Windows app (``aura_window_app/src/aura/services/tasks.py``), whose header explains the one rule:
a job is owned by :data:`_in_flight` from ``start`` until its callback has run. Letting ``QThreadPool`` delete it
when ``run`` returns frees the signal proxy before the queued callback arrives, and the process dies entering it.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from PyQt6.QtCore import QObject, QRunnable, QThreadPool, pyqtSignal

log = logging.getLogger(__name__)


class JobSignals(QObject):
    finished = pyqtSignal(object)
    failed = pyqtSignal(object)
    progress = pyqtSignal(str, float)


_in_flight: set[Job] = set()


class Job(QRunnable):
    def __init__(self, fn: Callable[..., Any], *, with_progress: bool = False) -> None:
        super().__init__()
        self._fn = fn
        self._with_progress = with_progress
        self.signals = JobSignals()
        self.setAutoDelete(False)

    def run(self) -> None:
        try:
            if self._with_progress:
                result = self._fn(self.signals.progress.emit)
            else:
                result = self._fn()
        except Exception as exc:
            log.warning("Background job failed: %r", exc)
            self.signals.failed.emit(exc)
            return
        self.signals.finished.emit(result)

    def _release(self, _outcome: object = None) -> None:
        _in_flight.discard(self)


def run_async(
    fn: Callable[..., Any],
    on_done: Callable[[Any], None],
    on_error: Callable[[Exception], None] | None = None,
    *,
    on_progress: Callable[[str, float], None] | None = None,
) -> Job:
    """Run ``fn`` on a worker. With ``on_progress``, ``fn`` is called with a ``report(message, fraction)``."""
    job = Job(fn, with_progress=on_progress is not None)
    _in_flight.add(job)
    if on_progress is not None:
        job.signals.progress.connect(on_progress)
    job.signals.finished.connect(on_done)
    job.signals.failed.connect(on_error if on_error is not None else on_done)
    # After the caller's slots: slots run in connection order.
    job.signals.finished.connect(job._release)
    job.signals.failed.connect(job._release)
    QThreadPool.globalInstance().start(job)
    return job


def in_flight_count() -> int:
    return len(_in_flight)
