"""Напоминания о встречах в Telegram.

Встреча — открытая задача с меткой «встреча». Расписание — в поле «Когда»
(шаблон задачи) или строкой «Когда: …» в описании. Понимает:
  каждый вторник 12:15 · пн, чт 19:00 · по будням 10:00 · каждый день 9:30
  каждые 2 недели по пятницам 16:00 с 07.11.2026 · 05.11.2026 18:00
  27.11.2026 (без времени — встреча на весь день, напомним накануне в 12:00 и в 9:00)
Время — Europe/Moscow. Напоминает за сутки, за час и за 15 минут (с тегами участников чата
и списком «кто что делает» с доски — board_status.py);
в начале встречи с полем «Протокол» — протокол (meeting_protocol.py); отправленные напоминания
помнит в sent.json (кэш Actions), чтобы не повторяться. Закрыли задачу — напоминаний нет.
"""
import datetime as dt
import json
import os
import re
import sys
import urllib.request
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(__file__))
import telegram_notify as tg  # noqa: E402
import board_status  # noqa: E402
import meeting_protocol  # noqa: E402

TZ = ZoneInfo("Europe/Moscow")
SENT = os.environ.get("SENT_STORE", "sent.json")
DAYS = [("пн", "понед"), ("вт", "вторн"), ("ср", "сред"), ("чт", "четв"),
        ("пт", "пятн"), ("сб", "субб"), ("вс", "воскр")]
WORDS = {"две": 2, "два": 2, "три": 3, "четыре": 4}


def field(body, name):
    """Значение поля из формы («### Когда» + строка) или строки «Когда: …»."""
    body = body or ""
    m = re.search(rf"^###\s*{name}\s*\n+(.+?)\s*(?=\n###|\Z)", body, re.M | re.S)
    if m and m.group(1).strip() != "_No response_":
        return m.group(1).strip()
    m = re.search(rf"^\s*\**{name}\**\s*:\s*(.+)$", body, re.M | re.I)
    return m.group(1).strip() if m else ""


def parse(when):
    """Расписание → dict(time, weekdays, every, start, once) или None."""
    s = when.lower().replace("ё", "е")
    t = re.search(r"(?<![.\d])(\d{1,2})[:.](\d{2})(?![.\d])", s)
    dates = [dt.date(int(y), int(m), int(d)) for d, m, y in re.findall(r"\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b", s)]
    tokens = re.findall(r"[а-я]+", s)
    days = set()
    for i, (short, stem) in enumerate(DAYS):
        if any(tok == short or tok.startswith(stem) for tok in tokens):
            days.add(i)
    if "будн" in s:
        days |= {0, 1, 2, 3, 4}
    if "каждый день" in s or "ежедневно" in s:
        days = set(range(7))
    every = 1
    m = re.search(r"(?:кажд\w+|раз в)\s+(\d+|две|два|три|четыре)\s+недел", s)
    if m:
        every = int(m.group(1)) if m.group(1).isdigit() else WORDS[m.group(1)]
    if not t:
        # без времени — только разовая встреча на весь день: напомним накануне и утром
        return {"time": None, "once": dates[0]} if dates and not days else None
    hh, mm = int(t.group(1)), int(t.group(2))
    if not days:
        return {"time": (hh, mm), "once": dates[0]} if dates else None
    return {"time": (hh, mm), "weekdays": days, "every": every, "start": dates[0] if dates else None}


def next_at(rule, now):
    """Ближайшее начало встречи после now (aware datetime) или None."""
    if rule["time"] is None:
        end = dt.datetime.combine(rule["once"], dt.time(23, 59), TZ)
        return dt.datetime.combine(rule["once"], dt.time(0, 0), TZ) if end > now else None
    hh, mm = rule["time"]
    if rule.get("once"):
        at = dt.datetime.combine(rule["once"], dt.time(hh, mm), TZ)
        return at if at > now else None
    start = rule.get("start") or now.date()
    anchor = start - dt.timedelta(days=start.weekday())
    day = max(now.date(), start)
    for _ in range(7 * rule["every"] + 7):
        if day.weekday() in rule["weekdays"] and ((day - anchor).days // 7) % rule["every"] == 0:
            at = dt.datetime.combine(day, dt.time(hh, mm), TZ)
            if at > now:
                return at
        day += dt.timedelta(days=1)
    return None


def reminder(issue, at, now, sent, allday=False):
    """Текст напоминания и ключ, если пора напоминать, иначе (None, None).

    Обычная встреча — за сутки, за час и за 15 минут («soon», с тегами). Встреча на весь день (дата без времени) —
    накануне в 12:00 и в день встречи в 9:00."""
    left = (at - now).total_seconds() / 60
    key = f"{issue['number']}@{at.isoformat()}"
    if allday:
        if now >= at + dt.timedelta(hours=9):
            kind = "hour"
        elif now >= at - dt.timedelta(hours=12):
            kind = "day"
        else:
            return None, None
    elif left <= 20:
        kind = "soon"
    elif left <= 65:
        kind = "hour"
    elif left <= 24 * 60:
        kind = "day"
    else:
        return None, None
    order = ["day", "hour", "soon"]
    if any(f"{key}:{k}" in sent for k in order[order.index(kind):]):
        return None, None
    title = re.sub(r"^\s*встреча\s*:\s*", "", issue["title"], flags=re.I) or issue["title"]
    if allday:
        when = ("Сегодня" if kind == "hour" else "Завтра") + f", {at:%d.%m}"
    elif kind in ("hour", "soon"):
        when = f"Через {max(1, round(left))} мин"
    else:
        when = ("Сегодня" if at.date() == now.date() else "Завтра") + f" в {at:%H:%M}"
    body = issue.get("body")
    icon = {"day": "📅", "hour": "⏰", "soon": "🔔"}[kind]
    lines = [f"{icon} {when} — {tg.link(issue['html_url'], title)}"]
    for label, name in (("Кто", "Кто участвует"), ("Длительность", "Длительность")):
        if field(body, name):
            lines.append(f"{label}: {tg.e(field(body, name))}")
    link = field(body, "Ссылка")
    if link:
        lines.append(f"Ссылка: {tg.e(link)}")
    agenda = field(body, "Повестка")
    if agenda and kind == "day":
        lines.append(f"Повестка: {tg.short(agenda)}")
    return "\n".join(lines), f"{key}:{kind}"


def meetings():
    repo = os.environ["GITHUB_REPOSITORY"]
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/issues?labels=%D0%B2%D1%81%D1%82%D1%80%D0%B5%D1%87%D0%B0&state=open&per_page=100",
        headers={"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return [i for i in json.load(r) if "pull_request" not in i]


def main():
    if not tg.TOKEN:
        print("::notice::Секрет TELEGRAM_BOT_TOKEN не задан — напоминания пропущены")
        return
    try:
        with open(SENT) as f:
            sent = json.load(f)
    except (OSError, ValueError):
        sent = {}
    now = dt.datetime.now(TZ)
    chats = tg.load()
    tg.discover(chats)
    targets = tg.targets(chats, "meetings")
    for issue in meetings():
        when = field(issue.get("body"), "Когда")
        rule = parse(when) if when else None
        if not rule:
            print(f"#{issue['number']}: не понял расписание «{when}»")
            continue
        protocol_for = field(issue.get("body"), "Протокол")
        if protocol_for and rule["time"] is not None:
            started = next_at(rule, now - dt.timedelta(minutes=30))
            pkey = f"{issue['number']}@{started.isoformat()}:protocol" if started else None
            if started and started <= now and pkey not in sent:
                text = meeting_protocol.build(started, protocol_for, os.environ["GITHUB_TOKEN"])
                to = tg.targets(chats, "protocols")
                n = sum(tg.send(t, tg.e(text), chats) for t in to)
                print(f"  протокол отправлен в {n} из {len(to)} чатов")
                try:
                    meeting_protocol.comment(issue["number"], text, os.environ["GITHUB_TOKEN"])
                except Exception as ex:  # noqa: BLE001 — протокол в чате важнее комментария
                    print("  комментарий с протоколом:", ex)
                sent[pkey] = now.isoformat()
        at = next_at(rule, now)
        print(f"#{issue['number']} {issue['title']}: следующая {at:%d.%m %H:%M}" if at else f"#{issue['number']}: встреч впереди нет")
        if not at:
            continue
        text, key = reminder(issue, at, now, sent, allday=rule["time"] is None)
        if text:
            if key.endswith(":soon"):
                status = board_status.summary()
                text = f"{text}\n\n{status}" if status else text
                n = sum(tg.send(t, f"{text}\n\n{tg.mentions(t.partition(':')[0], chats)}".rstrip(), chats) for t in targets)
            else:
                n = sum(tg.send(t, text, chats) for t in targets)
            print(f"  напоминание отправлено в {n} из {len(targets)} чатов")
            sent[key] = now.isoformat()
    week_ago = (now - dt.timedelta(days=7)).isoformat()
    sent = {k: v for k, v in sent.items() if v > week_ago}
    with open(SENT, "w") as f:
        json.dump(sent, f)
    tg.save(chats)
    tg.print_chats(chats)


if __name__ == "__main__":
    main()
