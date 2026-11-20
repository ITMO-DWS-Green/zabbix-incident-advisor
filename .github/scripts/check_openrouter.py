"""Проверка OpenRouter API на синтетическом инциденте.

Токен берётся из OPENROUTER_API_KEY или ADVISOR_LLM_API_KEY. Локально можно положить его в .env
(файл не коммитится). Скрипт не печатает токен и проверяет не просто HTTP 200, а ответ модели по
короткому тестовому инциденту: источник должен быть database.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("ADVISOR_LLM_BASE_URL", "https://openrouter.ai/api/v1").rstrip("/")
MODEL = os.environ.get("ADVISOR_LLM_MODEL", "meta-llama/llama-3.2-3b-instruct:free")
KEY = os.environ.get("OPENROUTER_API_KEY") or os.environ.get("ADVISOR_LLM_API_KEY")

SYSTEM = """Ты помощник SRE. По фактам инцидента назови вероятный источник сбоя.
Ответь только JSON без markdown: {"source":"service","confidence":0..1,"reason":"..."}."""
INCIDENT = """
Проблема: выросла доля 5xx на gateway, orders, payments и database.
Карта зависимостей: gateway -> orders -> payments -> database; orders -> database.
Таймлайн:
- 10:00 database начал отвечать 500 на 100% запросов.
- 10:01 payments получил ошибки downstream database.
- 10:02 orders получил ошибки downstream payments и database.
- 10:03 gateway получил ошибки downstream orders.
Логи:
- database: Unhandled exception: connection pool exhausted.
- payments: downstream database responded 500.
- orders: downstream payments responded 500.
Назови первопричину.
"""


def load_dotenv():
    """Минимальное чтение .env без зависимостей; переменные окружения имеют приоритет."""
    if not os.path.exists(".env"):
        return
    with open(".env", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def extract_json(text):
    text = re.sub(r"```(?:json)?|```", "", text).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.S)
        if match:
            return json.loads(match.group(0))
        raise


def main():
    global KEY
    load_dotenv()
    KEY = KEY or os.environ.get("OPENROUTER_API_KEY") or os.environ.get("ADVISOR_LLM_API_KEY")
    if not KEY:
        sys.exit("Нет OPENROUTER_API_KEY/ADVISOR_LLM_API_KEY. Добавьте секрет репозитория или .env локально.")

    body = {
        "model": MODEL,
        "temperature": 0.0,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": INCIDENT},
        ],
    }
    request = urllib.request.Request(
        BASE_URL + "/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            answer = json.load(response)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:1000]
        sys.exit(f"OpenRouter вернул HTTP {e.code}: {detail}")

    text = answer.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
    if not text:
        sys.exit(f"В ответе нет choices[0].message.content: {json.dumps(answer, ensure_ascii=False)[:1000]}")
    parsed = extract_json(text)
    source = str(parsed.get("source", "")).lower()
    if "database" not in source:
        sys.exit(f"Модель ответила, но источник неверный: {parsed}")
    print(f"OK: OpenRouter отвечает, модель {answer.get('model', MODEL)} разобрала тестовый инцидент: {parsed}")


if __name__ == "__main__":
    main()
