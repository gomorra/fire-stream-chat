import subprocess

import pytest

import card
import config
from config import ConfigError


def test_the_free_memory_is_read_from_nvidia_smi():
    assert card.parse_free_mb("15021\n") == 15021


def test_with_two_cards_the_one_with_the_most_counts():
    assert card.parse_free_mb("812\n 9000 \n") == 9000


@pytest.mark.parametrize("output", ["", "\n", "N/A\n", "No devices were found\n"])
def test_output_without_a_number_is_no_answer(output):
    assert card.parse_free_mb(output) is None


def test_a_missing_nvidia_smi_is_no_answer(monkeypatch):
    def missing(*args, **kwargs):
        raise FileNotFoundError("nvidia-smi")

    monkeypatch.setattr(subprocess, "run", missing)
    assert card.free_vram_mb() is None


def test_a_failing_nvidia_smi_is_no_answer(monkeypatch):
    def failing(*args, **kwargs):
        raise subprocess.CalledProcessError(9, "nvidia-smi")

    monkeypatch.setattr(subprocess, "run", failing)
    assert card.free_vram_mb() is None


def test_the_env_file_is_key_value_lines():
    values = config.parse_env(
        "# a comment\n"
        "\n"
        "BUCKET=my-project.firebasestorage.app\n"
        "MODEL='gemma4:12b'\n"
        'ENGINE="ollama"\n'
        "KEY_FILE = /home/me/key.json \n"
        "NOTE='it'\\''s'\n"
        "no equals sign\n"
    )
    assert values == {
        "BUCKET": "my-project.firebasestorage.app",
        "MODEL": "gemma4:12b",
        "ENGINE": "ollama",
        "KEY_FILE": "/home/me/key.json",
        "NOTE": "it's",
    }


def env_file(tmp_path, text, key_mode=0o600):
    key = tmp_path / "key.json"
    key.write_text("{}")
    key.chmod(key_mode)
    file = tmp_path / "env"
    file.write_text(text.replace("@KEY@", str(key)))
    return file


def test_the_defaults():
    loaded = config.engine_settings({})
    assert loaded == {
        "engine": "ollama",
        "engine_url": "http://127.0.0.1:11434",
        "model": "gemma4:26b",
        "keep_alive": "2m",
    }


def test_a_full_env_file_loads(tmp_path):
    file = env_file(
        tmp_path,
        "KEY_FILE=@KEY@\nBUCKET=b\nENGINE=openai\nENGINE_URL=http://127.0.0.1:8080/\nMODEL=qwen\nMIN_FREE_VRAM_MB=0\n",
    )
    loaded = config.load(file, environ={})
    assert loaded.bucket == "b"
    assert loaded.engine == "openai"
    assert loaded.engine_url == "http://127.0.0.1:8080"
    assert loaded.model == "qwen"
    assert loaded.min_free_vram_mb == 0
    assert loaded.key_file == tmp_path / "key.json"


def test_a_variable_of_the_process_wins_over_the_file(tmp_path):
    file = env_file(tmp_path, "KEY_FILE=@KEY@\nBUCKET=b\nMODEL=gemma4:26b\n")
    assert config.load(file, environ={"MODEL": "gemma4:12b"}).model == "gemma4:12b"


@pytest.mark.parametrize(
    "text, message",
    [
        ("BUCKET=b\n", "KEY_FILE is not set"),
        ("KEY_FILE=@KEY@\n", "BUCKET is not set"),
        ("KEY_FILE=@KEY@\nBUCKET=b\nENGINE=cloud\n", "ENGINE is"),
        ("KEY_FILE=@KEY@\nBUCKET=b\nENGINE_URL=localhost:11434\n", "ENGINE_URL is"),
        ("KEY_FILE=@KEY@\nBUCKET=b\nMIN_FREE_VRAM_MB=lots\n", "MIN_FREE_VRAM_MB is"),
        ("KEY_FILE=@KEY@\nBUCKET=b\nMIN_FREE_VRAM_MB=-1\n", "must not be negative"),
        ("KEY_FILE=@KEY@.missing\nBUCKET=b\n", "does not exist"),
    ],
)
def test_a_setting_that_is_missing_or_wrong_is_named(tmp_path, text, message):
    with pytest.raises(ConfigError, match=message):
        config.load(env_file(tmp_path, text), environ={})


def test_no_env_file_names_the_first_missing_setting(tmp_path):
    with pytest.raises(ConfigError, match="KEY_FILE is not set"):
        config.load(tmp_path / "none", environ={})


@pytest.mark.parametrize("mode", [0o640, 0o604, 0o660])
def test_a_key_that_others_can_read_is_refused(tmp_path, mode):
    with pytest.raises(ConfigError, match="open to other users"):
        config.load(env_file(tmp_path, "KEY_FILE=@KEY@\nBUCKET=b\n", key_mode=mode), environ={})
