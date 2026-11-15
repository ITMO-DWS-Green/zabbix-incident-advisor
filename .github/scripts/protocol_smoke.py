"""One-off live protocol smoke test; --send delivers to configured protocol topics."""
import argparse
import datetime as dt
import os
from zoneinfo import ZoneInfo

import meeting_protocol
import telegram_notify as tg


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--send", action="store_true")
    args = parser.parse_args()
    protocol = meeting_protocol.build(
        dt.datetime.now(ZoneInfo("Europe/Moscow")),
        "🧪 ТЕСТОВЫЙ ПРОТОКОЛ — проверка оформления и задач с доски; это не реальная встреча.",
    )
    print(protocol.comment)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write("## Тестовый протокол\n\n" + protocol.comment + "\n\n")
    if not args.send:
        return
    if not tg.TOKEN:
        raise RuntimeError("TELEGRAM_BOT_TOKEN is missing")
    chats = tg.load()
    targets = tg.targets(chats, "protocols")
    if not targets:
        raise RuntimeError("No configured protocol destinations")
    original = tg.tg

    def observed(method, **params):
        result = original(method, **params)
        print(f"Telegram {method}: ok={result.get('ok')}, error_code={result.get('error_code')}")
        return result

    tg.tg = observed
    sent = sum(tg.send(target, protocol.card, chats) for target in targets)
    result = f"Тестовый протокол отправлен в {sent} из {len(targets)} настроенных чатов."
    print(result)
    if summary:
        with open(summary, "a") as f:
            f.write(result + "\n")
    if sent != len(targets):
        raise RuntimeError("Protocol delivery failed; inspect logs before retrying")


if __name__ == "__main__":
    main()
