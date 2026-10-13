import pytest

from retry import retrying


class Reset(Exception):
    """Stands for the transport error of a dead connection."""


class Flaky:
    """Fails with each of [failures] in turn, then answers."""

    def __init__(self, *failures):
        self.failures = list(failures)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        if self.failures:
            raise self.failures.pop(0)
        return "token"


def test_a_dead_connection_after_standby_is_tried_again():
    call, slept = Flaky(Reset()), []

    assert retrying(call, Reset, waits=(0.5, 2), sleep=slept.append) == "token"
    assert call.calls == 2
    assert slept == [0.5]


def test_a_call_that_works_is_made_once_and_waits_for_nothing():
    call, slept = Flaky(), []

    assert retrying(call, Reset, sleep=slept.append) == "token"
    assert (call.calls, slept) == (1, [])


def test_a_failure_that_does_not_pass_is_raised_after_the_last_wait():
    call, slept = Flaky(Reset(), Reset(), Reset()), []

    with pytest.raises(Reset):
        retrying(call, Reset, waits=(0.5, 2), sleep=slept.append)
    assert call.calls == 3
    assert slept == [0.5, 2]


def test_another_failure_is_not_tried_again():
    call = Flaky(ValueError("bad key"))

    with pytest.raises(ValueError):
        retrying(call, Reset, sleep=lambda _: None)
    assert call.calls == 1
