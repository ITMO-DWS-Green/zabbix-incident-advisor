"""Заглушка Telegram Bot API для стенда. Только стандартная библиотека Python.

Принимает то, что вебхук Zabbix шлёт вместо настоящего api.telegram.org:
POST /bot<token>/sendMessage с JSON {chat_id, text, ...}. Каждое сообщение дописывается
строкой JSON в файл (TELEGRAM_STUB_FILE), чтобы сквозной тест проверил доставку разбора
в чат нужной команды, не выходя в интернет. Ответ — как у Telegram, иначе вебхук сочтёт отправку ошибкой.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("TELEGRAM_STUB_PORT", "8080"))
OUT = os.environ.get("TELEGRAM_STUB_FILE", "/tmp/telegram-messages.jsonl")


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            body = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            body = {"_raw": raw}

        record = {"path": self.path, "body": body}
        os.makedirs(os.path.dirname(OUT) or ".", exist_ok=True)
        with open(OUT, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(f"sendMessage -> chat {body.get('chat_id')}: {str(body.get('text', ''))[:80]}", flush=True)

        reply = json.dumps({"ok": True, "result": {"message_id": 1, "chat": {"id": body.get("chat_id")}}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(reply)))
        self.end_headers()
        self.wfile.write(reply)

    def do_GET(self):
        """Проверка живости и чтение принятого — удобно из e2e-теста."""
        if self.path.startswith("/messages"):
            try:
                with open(OUT, encoding="utf-8") as fh:
                    payload = fh.read().encode()
            except FileNotFoundError:
                payload = b""
            content_type = "application/x-ndjson"
        else:
            payload = b"ok"
            content_type = "text/plain"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        return  # свои сообщения пишем в do_POST, журнал http.server только шумит


if __name__ == "__main__":
    print(f"Заглушка Telegram слушает :{PORT}, сообщения -> {OUT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
