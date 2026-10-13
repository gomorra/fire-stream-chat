"""Tries a call again after a failure that passes.

`firebase_store.py` uses it for the request that fetches a new access token.
After the PC's standby the kept connection to Google is dead, and the first
request on it fails with a reset. The second one opens a new connection.
"""

from __future__ import annotations

import time
from typing import Callable, TypeVar

T = TypeVar("T")

# The waits between the attempts. The first is short: a dead connection needs
# no wait. The later ones give the network time to come back after standby.
WAITS_SECONDS = (0.5, 2, 5)


def retrying(
    call: Callable[[], T],
    passing: type[Exception],
    waits: tuple[float, ...] = WAITS_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
) -> T:
    """The result of [call]. A [passing] failure is tried again after each of [waits], then raised."""
    for wait in waits:
        try:
            return call()
        except passing:
            sleep(wait)
    return call()
