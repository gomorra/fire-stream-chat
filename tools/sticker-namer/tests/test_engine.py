import base64
import json
import urllib.error

import pytest

import engine as engine_module
from answer import QUESTION, SCHEMA
from engine import Engine, EngineDown, NoAnswer

PNG = b"\x89PNG picture"
ANSWER = {"name_en": "Cat"}


class FakeHttp:
    """Records each request and answers with the next of [responses]."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, method, url, body, timeout):
        self.calls.append((method, url, body))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response


def ollama(http, model="gemma4:26b"):
    return Engine("ollama", "http://127.0.0.1:11434", model, keep_alive="2m", http=http)


def openai(http):
    return Engine("openai", "http://127.0.0.1:8080", "qwen", http=http)


def test_ollama_is_asked_with_the_picture_the_schema_and_no_thinking():
    http = FakeHttp({"message": {"content": json.dumps(ANSWER)}})

    assert ollama(http).ask(PNG) == ANSWER

    method, url, body = http.calls[0]
    assert (method, url) == ("POST", "http://127.0.0.1:11434/api/chat")
    assert body["model"] == "gemma4:26b"
    assert body["format"] == SCHEMA
    assert body["stream"] is False
    assert body["think"] is False
    assert body["keep_alive"] == "2m"
    assert body["options"] == {"temperature": 0.2}
    assert body["messages"] == [
        {"role": "user", "content": QUESTION, "images": [base64.b64encode(PNG).decode("ascii")]}
    ]
    assert "tools" not in body


def test_the_openai_style_engine_is_asked_with_the_picture_and_the_schema():
    http = FakeHttp({"choices": [{"message": {"content": json.dumps(ANSWER)}}]})

    assert openai(http).ask(PNG) == ANSWER

    method, url, body = http.calls[0]
    assert (method, url) == ("POST", "http://127.0.0.1:8080/v1/chat/completions")
    assert body["temperature"] == 0.2
    assert body["response_format"]["type"] == "json_schema"
    assert body["response_format"]["json_schema"]["schema"] == SCHEMA
    text, image = body["messages"][0]["content"]
    assert text == {"type": "text", "text": QUESTION}
    assert image["image_url"]["url"] == "data:image/png;base64," + base64.b64encode(PNG).decode("ascii")
    assert "tools" not in body


@pytest.mark.parametrize(
    "response",
    [
        {"message": {"content": "this is no JSON"}},
        {"message": {}},
        {"error": "something"},
        ["a list"],
        None,
    ],
)
def test_a_response_without_a_json_answer_is_no_answer(response):
    with pytest.raises(NoAnswer):
        ollama(FakeHttp(response)).ask(PNG)


@pytest.mark.parametrize("response", [{"choices": []}, {"choices": "none"}, {"choices": [{"message": None}]}])
def test_an_openai_response_without_a_choice_is_no_answer(response):
    with pytest.raises(NoAnswer):
        openai(FakeHttp(response)).ask(PNG)


def test_an_engine_that_is_down_is_passed_on():
    with pytest.raises(EngineDown):
        ollama(FakeHttp(EngineDown("connection refused"))).ask(PNG)


def test_a_refused_connection_is_engine_down(monkeypatch):
    def refuse(*args, **kwargs):
        raise urllib.error.URLError("connection refused")

    monkeypatch.setattr(engine_module.urllib.request, "urlopen", refuse)
    with pytest.raises(EngineDown):
        engine_module.http_json("POST", "http://127.0.0.1:1/api/chat", {"a": 1}, 1)


def http_error(status):
    def fail(*args, **kwargs):
        raise urllib.error.HTTPError("http://127.0.0.1:1/api/chat", status, "refused", {}, None)

    return fail


@pytest.mark.parametrize("status", [500, 503])
def test_a_server_error_is_engine_down(monkeypatch, status):
    monkeypatch.setattr(engine_module.urllib.request, "urlopen", http_error(status))
    with pytest.raises(EngineDown):
        engine_module.http_json("POST", "http://127.0.0.1:1/api/chat", {"a": 1}, 1)


@pytest.mark.parametrize("status", [400, 404])
def test_a_refused_request_is_no_answer_and_not_engine_down(monkeypatch, status):
    monkeypatch.setattr(engine_module.urllib.request, "urlopen", http_error(status))
    with pytest.raises(NoAnswer):
        engine_module.http_json("POST", "http://127.0.0.1:1/api/chat", {"a": 1}, 1)


def test_a_timeout_is_engine_down(monkeypatch):
    def time_out(*args, **kwargs):
        raise TimeoutError("timed out")

    monkeypatch.setattr(engine_module.urllib.request, "urlopen", time_out)
    with pytest.raises(EngineDown):
        engine_module.http_json("GET", "http://127.0.0.1:1/api/ps", None, 1)


def test_ollama_says_whether_the_model_is_loaded():
    running = {"models": [{"name": "gemma4:26b", "model": "gemma4:26b"}]}
    http = FakeHttp(running, running, {"models": []})

    assert ollama(http).is_loaded()
    assert not ollama(http, model="gemma4:12b").is_loaded()
    assert not ollama(http).is_loaded()
    assert http.calls[0][:2] == ("GET", "http://127.0.0.1:11434/api/ps")


@pytest.mark.parametrize("response", [EngineDown("down"), NoAnswer("refused"), {}, {"models": "none"}, None])
def test_an_ollama_that_cannot_say_counts_as_not_loaded(response):
    assert not ollama(FakeHttp(response)).is_loaded()


def test_llama_cpp_holds_its_model_and_is_not_asked():
    http = FakeHttp()
    assert openai(http).is_loaded()
    assert http.calls == []
