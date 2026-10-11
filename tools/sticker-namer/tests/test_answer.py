import pytest

from answer import (
    MAX_NAME_LENGTH,
    MAX_TEXT_LENGTH,
    SCHEMA,
    AnswerError,
    clean,
    cut,
    fields_of,
    is_emoji,
    utf16_length,
)


def model_answer(**changes):
    answer = {
        "name_en": "Waving cat",
        "name_de": "Winkende Katze",
        "words_en": ["cat", "wave", "hello", "happy", "greeting"],
        "words_de": ["Katze", "winken", "Hallo", "fröhlich", "Begrüßung"],
        "text": "Hallo!",
        "emojis": ["🐱", "👋"],
    }
    answer.update(changes)
    return answer


def test_a_good_answer_becomes_the_documents_fields():
    assert fields_of(model_answer()) == {
        "nameEn": "Waving cat",
        "nameDe": "Winkende Katze",
        "words": ["cat", "wave", "hello", "happy", "greeting", "Katze", "winken", "Hallo", "fröhlich", "Begrüßung"],
        "text": "Hallo!",
        "emojis": ["🐱", "👋"],
    }


def test_the_schema_requires_the_six_fields_and_no_other():
    assert sorted(SCHEMA["required"]) == sorted(model_answer())
    assert sorted(SCHEMA["properties"]) == sorted(model_answer())
    assert SCHEMA["additionalProperties"] is False


@pytest.mark.parametrize(
    "changes",
    [
        {"name_en": "x" * (MAX_NAME_LENGTH + 1)},
        {"name_de": ""},
        {"name_de": " \n "},
        {"name_en": "Cat Sticker"},
        {"name_de": "Katzensticker"},
        {"name_en": 7},
        {"name_en": None},
        {"words_en": "cat"},
        {"words_de": ["Katze", 3]},
        {"emojis": "🐱"},
        {"text": ["Hallo"]},
    ],
)
def test_an_answer_the_model_is_asked_again_for(changes):
    with pytest.raises(AnswerError):
        fields_of(model_answer(**changes))


@pytest.mark.parametrize("raw", [None, "name", ["name_en"], 3])
def test_an_answer_that_is_no_object_is_refused(raw):
    with pytest.raises(AnswerError):
        fields_of(raw)


def test_a_missing_name_is_refused():
    answer = model_answer()
    del answer["name_de"]
    with pytest.raises(AnswerError):
        fields_of(answer)


def test_a_name_of_exactly_the_limit_is_kept():
    assert fields_of(model_answer(name_en="x" * MAX_NAME_LENGTH))["nameEn"] == "x" * MAX_NAME_LENGTH


def test_a_long_text_is_cut_to_the_limit():
    text = fields_of(model_answer(text="a" * 500))["text"]
    assert text == "a" * MAX_TEXT_LENGTH


def test_a_text_is_cut_at_a_whole_character_counted_as_the_app_counts():
    # Each of these is two UTF-16 units, so 64 of them fill the 128.
    text = fields_of(model_answer(text="😀" * 100))["text"]
    assert text == "😀" * 64
    assert utf16_length(text) == MAX_TEXT_LENGTH


def test_a_word_that_does_not_fit_is_dropped_and_the_rest_kept():
    fields = fields_of(model_answer(words_en=["cat", "x" * 41, "", "funny sticker", "cat"]))
    assert fields["words"][:1] == ["cat"]
    assert "x" * 41 not in fields["words"]
    assert "funny sticker" not in fields["words"]
    assert fields["words"].count("cat") == 1


def test_too_many_words_leave_each_language_its_share():
    fields = fields_of(
        model_answer(words_en=[f"en{i}" for i in range(20)], words_de=[f"de{i}" for i in range(20)])
    )
    assert fields["words"] == [f"en{i}" for i in range(8)] + [f"de{i}" for i in range(8)]


def test_a_word_in_both_languages_is_kept_once():
    fields = fields_of(model_answer(words_en=["Party", "cat"], words_de=["Party", "Katze"]))
    assert fields["words"] == ["Party", "cat", "Katze"]


def test_an_emoji_that_is_a_word_is_dropped():
    assert fields_of(model_answer(emojis=["cat", "🐱", ":smile:", "🐱"]))["emojis"] == ["🐱"]


def test_at_most_three_emojis_are_kept():
    assert fields_of(model_answer(emojis=["🐱", "👋", "😀", "🎉"]))["emojis"] == ["🐱", "👋", "😀"]


def test_control_characters_leave_every_string():
    fields = fields_of(model_answer(name_en="Waving\x00 cat\n", text="\tHal\x1blo\x7f", words_en=["ca\x07t"]))
    assert fields["nameEn"] == "Waving cat"
    assert fields["text"] == "Hallo"
    assert fields["words"][0] == "cat"


@pytest.mark.parametrize("tag", ["🐱", "👋🏽", "👨‍👩‍👧", "❤️", "ℹ️", "1️⃣", "🇩🇪"])
def test_what_is_an_emoji(tag):
    assert is_emoji(tag)


@pytest.mark.parametrize("tag", ["", "cat", "Katze", "ä", "🐱 🐱", "🐱cat", ":)", "🐱\n", "🐱" * 17])
def test_what_is_no_emoji(tag):
    assert not is_emoji(tag)


def test_clean_and_cut():
    assert clean("  a\x00b  ") == "ab"
    assert cut("abc def", 4) == "abc"
    assert utf16_length("a😀") == 3
