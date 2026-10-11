from datetime import datetime, timedelta, timezone

import pytest

import namer as namer_module
from answer import AnswerError
from engine import EngineDown, NoAnswer
from fakes import GOOD_ANSWER, FakeEngine, FakeFiles, FakeRequests, id_of, webp
from namer import Namer

NOW = datetime(2026, 10, 11, 12, 0, tzinfo=timezone.utc)


class Service:
    """A namer over fakes. The card is free unless a test says otherwise."""

    def __init__(self, engine=None, free_vram=(20000,), min_free_vram_mb=10000):
        self.requests = FakeRequests()
        self.files = FakeFiles()
        self.engine = engine or FakeEngine()
        self.free_vram = list(free_vram)
        self.card_reads = 0
        self.sleeps = []
        self.on_sleep = lambda: None
        self.namer = Namer(
            self.requests,
            self.files,
            self.engine,
            min_free_vram_mb,
            free_vram_mb=self._free_vram_mb,
            sleep=self._sleep,
            now=lambda: NOW,
        )

    def _free_vram_mb(self):
        self.card_reads += 1
        return self.free_vram.pop(0) if len(self.free_vram) > 1 else self.free_vram[0]

    def _sleep(self, seconds):
        self.sleeps.append(seconds)
        self.on_sleep()

    def sticker(self, data=None, **request):
        """A sticker in Storage with a pending request. Returns its id."""
        sticker_id = self.files.put(data or webp())
        self.requests.ask(sticker_id, **{"requested_at": NOW, **request})
        return sticker_id

    def document(self, sticker_id):
        return self.requests.documents[sticker_id]


def test_a_request_is_answered_with_the_names():
    service = Service()
    sticker_id = service.sticker()

    assert service.namer.run_once() is False

    assert service.document(sticker_id) == {
        "status": "done",
        "format": "WEBP",
        "requestedAt": NOW,
        "nameEn": "Red square",
        "nameDe": "Rotes Quadrat",
        "words": ["red", "square", "rot", "Quadrat"],
        "text": "",
        "emojis": ["🟥"],
        "model": "fake-model",
    }
    assert service.engine.pictures[0].startswith(b"\x89PNG")


def test_a_file_that_does_not_match_its_id_is_skipped_and_never_shown_to_the_model():
    service = Service()
    sticker_id = "ab" * 32
    service.files.put(webp(), sticker_id=sticker_id)
    service.requests.ask(sticker_id, requested_at=NOW)

    assert service.namer.run_once() is False

    assert service.document(sticker_id)["status"] == "skipped"
    assert service.document(sticker_id)["reason"] == "hash mismatch"
    assert service.engine.pictures == []


@pytest.mark.parametrize("format", ["LOTTIE", "webp", "../x", "", None, 7, ["WEBP"]])
def test_a_format_that_is_not_webp_is_skipped_without_a_read(format):
    service = Service()
    service.files.error = AssertionError("Storage must not be read")
    sticker_id = id_of(webp())
    service.requests.ask(sticker_id, format=format, requested_at=NOW)

    assert service.namer.run_once() is False

    assert service.document(sticker_id)["status"] == "skipped"
    assert service.document(sticker_id)["reason"] == "format not named"
    assert service.engine.pictures == []


def test_a_lottie_file_asked_for_as_webp_is_skipped():
    service = Service()
    sticker_id = service.sticker(data=b'{"v":"5.5.2","layers":[]}')

    service.namer.run_once()

    assert service.document(sticker_id)["status"] == "skipped"
    assert service.document(sticker_id)["reason"] == "not a WebP picture"


@pytest.mark.parametrize("sticker_id", ["abc", "AB" * 32, "../../etc/passwd", "g" * 64, "ab" * 32 + "\n"])
def test_an_id_that_is_no_hash_is_skipped_without_a_read(sticker_id):
    service = Service()
    service.files.error = AssertionError("Storage must not be read")
    service.requests.ask(sticker_id, requested_at=NOW)

    service.namer.run_once()

    assert service.document(sticker_id)["reason"] == "bad id"


def test_a_file_above_the_size_limit_is_skipped(monkeypatch):
    monkeypatch.setattr(namer_module, "MAX_FILE_BYTES", 10)
    service = Service()
    sticker_id = service.sticker()

    service.namer.run_once()

    assert service.document(sticker_id)["reason"] == "file too large"


def test_an_engine_that_is_down_leaves_every_request_pending():
    service = Service(engine=FakeEngine(EngineDown("connection refused")))
    first = service.sticker(webp(color=(1, 2, 3, 255)))
    second = service.sticker(webp(color=(4, 5, 6, 255)))

    assert service.namer.run_once() is True

    assert service.document(first)["status"] == "pending"
    assert service.document(second)["status"] == "pending"
    assert service.requests.writes == 0
    # The batch ends at the first failure.
    assert len(service.engine.pictures) == 1


def test_a_request_left_pending_is_answered_by_a_later_round():
    service = Service(engine=FakeEngine(EngineDown("starting"), GOOD_ANSWER))
    sticker_id = service.sticker()

    assert service.namer.run_once() is True
    assert service.namer.run_once() is False

    assert service.document(sticker_id)["status"] == "done"


def test_storage_out_of_reach_leaves_the_request_pending_and_goes_on():
    service = Service()
    sticker_id = service.sticker()
    service.files.error = ConnectionError("no network")

    assert service.namer.run_once() is True

    assert service.document(sticker_id)["status"] == "pending"


def test_firestore_out_of_reach_is_a_round_to_try_again():
    service = Service()

    def no_network():
        raise ConnectionError("no network")

    service.requests.pending = no_network

    assert service.namer.run_once() is True


def test_a_busy_card_makes_the_namer_wait():
    service = Service(free_vram=(900, 4000, 15000))
    sticker_id = service.sticker()

    service.namer.run_once()

    assert service.sleeps == [namer_module.CARD_WAIT_SECONDS] * 2
    assert service.document(sticker_id)["status"] == "done"


def test_nothing_is_asked_while_the_card_is_busy():
    service = Service(free_vram=(900, 15000))
    service.sticker()
    asked_during_wait = []
    service.on_sleep = lambda: asked_during_wait.append(len(service.engine.pictures))

    service.namer.run_once()

    assert asked_during_wait == [0]


def test_a_request_answered_during_the_wait_is_left_alone():
    service = Service(free_vram=(900, 15000))
    sticker_id = service.sticker()
    other = {"status": "done", "nameEn": "From the other namer"}
    service.on_sleep = lambda: service.requests.documents[sticker_id].update(other)

    assert service.namer.run_once() is False

    assert service.document(sticker_id)["nameEn"] == "From the other namer"
    assert service.engine.pictures == []
    assert service.requests.writes == 0


def test_a_request_answered_while_the_model_looks_is_not_written_over():
    service = Service()
    sticker_id = service.sticker()

    class AnsweredMeanwhile(FakeEngine):
        def ask(self, png):
            service.requests.documents[sticker_id].update({"status": "skipped", "reason": "by the owner"})
            return super().ask(png)

    service.namer.engine = AnsweredMeanwhile()

    assert service.namer.run_once() is False

    assert service.document(sticker_id)["status"] == "skipped"
    assert "nameEn" not in service.document(sticker_id)


def test_a_loaded_model_does_not_wait_for_the_card_it_fills_itself():
    service = Service(engine=FakeEngine(loaded=True), free_vram=(100,))
    sticker_id = service.sticker()

    service.namer.run_once()

    assert service.card_reads == 0
    assert service.document(sticker_id)["status"] == "done"


def test_a_limit_of_zero_never_looks_at_the_card():
    service = Service(free_vram=(0,), min_free_vram_mb=0)
    sticker_id = service.sticker()

    service.namer.run_once()

    assert service.card_reads == 0
    assert service.document(sticker_id)["status"] == "done"


def test_without_nvidia_smi_the_request_goes_ahead():
    service = Service(free_vram=(None,))
    sticker_id = service.sticker()

    service.namer.run_once()

    assert service.sleeps == []
    assert service.document(sticker_id)["status"] == "done"


def test_a_name_that_breaks_a_limit_is_asked_for_again():
    too_long = {**GOOD_ANSWER, "name_de": "x" * 81}
    service = Service(engine=FakeEngine(too_long, AnswerError("no JSON"), GOOD_ANSWER))
    sticker_id = service.sticker()

    service.namer.run_once()

    assert len(service.engine.pictures) == 3
    assert service.document(sticker_id)["nameDe"] == "Rotes Quadrat"


def test_a_model_that_never_gives_a_usable_answer_ends_in_skipped():
    service = Service(engine=FakeEngine({**GOOD_ANSWER, "name_en": "A sticker"}))
    sticker_id = service.sticker()

    service.namer.run_once()

    assert len(service.engine.pictures) == namer_module.ASK_ATTEMPTS
    assert service.document(sticker_id)["status"] == "skipped"
    assert service.document(sticker_id)["reason"] == "no usable answer"
    assert "nameEn" not in service.document(sticker_id)


def test_an_engine_that_gives_no_answer_skips_nothing_and_blocks_nothing():
    # A model the engine does not have: every request is refused.
    service = Service(engine=FakeEngine(NoAnswer("status 404")))
    first = service.sticker(webp(color=(1, 2, 3, 255)))
    second = service.sticker(webp(color=(4, 5, 6, 255)))

    assert service.namer.run_once() is True

    assert service.document(first)["status"] == "pending"
    assert service.document(second)["status"] == "pending"
    assert len(service.engine.pictures) == 2 * namer_module.ASK_ATTEMPTS


def test_a_file_that_is_not_uploaded_yet_stays_pending():
    service = Service()
    sticker_id = id_of(webp())
    service.requests.ask(sticker_id, requested_at=NOW - timedelta(hours=23))

    assert service.namer.run_once() is True

    assert service.document(sticker_id)["status"] == "pending"


@pytest.mark.parametrize("requested_at", [NOW - timedelta(hours=25), datetime(2026, 10, 9, 12, 0), None, "today"])
def test_a_file_that_never_came_is_skipped(requested_at):
    service = Service()
    sticker_id = id_of(webp())
    service.requests.ask(sticker_id, requested_at=requested_at)

    assert service.namer.run_once() is False

    assert service.document(sticker_id)["reason"] == "no file"


def test_one_request_that_fails_does_not_stop_the_others():
    service = Service()
    missing = id_of(webp(color=(9, 9, 9, 255)))
    service.requests.ask(missing, requested_at=NOW)
    named = service.sticker()

    assert service.namer.run_once() is True

    assert service.document(missing)["status"] == "pending"
    assert service.document(named)["status"] == "done"


def test_what_the_model_wrote_is_not_logged(caplog):
    caplog.set_level("DEBUG")
    service = Service()
    service.sticker()

    service.namer.run_once()

    assert "Red square" not in caplog.text
    assert "Rotes Quadrat" not in caplog.text
