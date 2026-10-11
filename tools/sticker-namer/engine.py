"""The picture model, behind one function.

Two engines answer the same question: Ollama's `/api/chat`, and the
OpenAI-style `/v1/chat/completions` that llama.cpp's server speaks. Both get
the picture as a base64 PNG and the JSON schema the answer is bound to. The
model has no tools. Thinking is off and the temperature is low, so the same
sticker gets much the same name twice.
"""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.request
from typing import Callable

from answer import QUESTION, SCHEMA, AnswerError

TEMPERATURE = 0.2

# A model that has to be loaded first takes a while to answer its first question.
ANSWER_TIMEOUT_SECONDS = 300
STATUS_TIMEOUT_SECONDS = 10

# (method, url, body or None, timeout in seconds) -> the decoded JSON of the response.
Http = Callable[[str, str, "dict | None", float], object]


class NoAnswer(AnswerError):
    """The engine answered, and not with a model's answer: it refused the request, or sent no JSON.

    This says something about the engine or its settings, such as a model it
    does not have. It says nothing about the sticker.
    """


class EngineDown(Exception):
    """The engine did not answer: it is not running, still starting, or out of memory. Try again later."""


def http_json(method: str, url: str, body: dict | None, timeout: float) -> object:
    """One JSON request to the engine.

    A failure of the connection or of the server is [EngineDown]. A request
    the engine refuses (a 4xx status) is [NoAnswer]: the next request may be
    taken, so it must not end a batch.
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        if 400 <= e.code < 500:
            raise NoAnswer(f"the engine refused the request with status {e.code}") from None
        raise EngineDown(f"status {e.code}") from None
    except (urllib.error.URLError, OSError, ValueError) as e:
        # ValueError covers a body that is no JSON.
        raise EngineDown(f"{type(e).__name__}: {e}") from None


class Engine:
    """One model on one engine."""

    def __init__(self, engine: str, engine_url: str, model: str, keep_alive: str = "2m", http: Http = http_json):
        self.engine = engine
        self.url = engine_url
        self.model = model
        self.keep_alive = keep_alive
        self.http = http

    def ask(self, png: bytes) -> object:
        """What the model answers about the picture [png], parsed from JSON.

        Raises [EngineDown] when the engine cannot be asked, and [NoAnswer]
        when it refused the request or answered something that is no JSON.
        """
        picture = base64.b64encode(png).decode("ascii")
        if self.engine == "ollama":
            response = self.http("POST", f"{self.url}/api/chat", self._ollama_body(picture), ANSWER_TIMEOUT_SECONDS)
            content = _dig(response, "message", "content")
        else:
            response = self.http(
                "POST", f"{self.url}/v1/chat/completions", self._openai_body(picture), ANSWER_TIMEOUT_SECONDS
            )
            choices = _dig(response, "choices")
            content = _dig(choices[0], "message", "content") if isinstance(choices, list) and choices else None
        if not isinstance(content, str):
            raise NoAnswer("the engine's response holds no answer")
        try:
            return json.loads(content)
        except ValueError:
            raise NoAnswer("the answer is no JSON") from None

    def is_loaded(self) -> bool:
        """Whether the model sits on the graphics card already.

        Ollama loads a model for a question and drops it after `keep_alive`.
        llama.cpp's server holds its model for as long as it runs.
        """
        if self.engine != "ollama":
            return True
        try:
            running = _dig(self.http("GET", f"{self.url}/api/ps", None, STATUS_TIMEOUT_SECONDS), "models")
        except (EngineDown, NoAnswer):
            return False
        if not isinstance(running, list):
            return False
        return any(isinstance(m, dict) and self.model in (m.get("name"), m.get("model")) for m in running)

    def _ollama_body(self, picture: str) -> dict:
        return {
            "model": self.model,
            "messages": [{"role": "user", "content": QUESTION, "images": [picture]}],
            "format": SCHEMA,
            "stream": False,
            "think": False,
            "keep_alive": self.keep_alive,
            "options": {"temperature": TEMPERATURE},
        }

    def _openai_body(self, picture: str) -> dict:
        return {
            "model": self.model,
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": QUESTION},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{picture}"}},
                    ],
                }
            ],
            "temperature": TEMPERATURE,
            "response_format": {
                "type": "json_schema",
                "json_schema": {"name": "sticker_name", "strict": True, "schema": SCHEMA},
            },
            # llama.cpp hands this to the model's chat template. It switches a thinking model's thinking off.
            "chat_template_kwargs": {"enable_thinking": False},
        }


def _dig(value: object, *keys: str) -> object:
    """`value[k1][k2]…`, or `None` where a level is no object or lacks its key."""
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value
