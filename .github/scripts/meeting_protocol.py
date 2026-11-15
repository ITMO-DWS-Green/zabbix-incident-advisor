"""Протокол встречи: присутствовавшие (по умолчанию вся команда) и распределение по задачам.

Отправляется в начале встречи, если в задаче встречи заполнено поле «Протокол» —
кого отметить в Telegram (например, @AVEroshkin). Команда — .github/team.json,
задачи — открытые задачи репозитория из In progress и In review на доске (кроме встреч).
PROJECT_TOKEN обязателен: при недоступной доске протокол откладывается, а не подменяется
списком всех назначенных задач.
"""
import json
import os
import urllib.request
from dataclasses import dataclass

import board_status
import telegram_cards

ROOT = os.path.join(os.path.dirname(__file__), "..")
ACTIVE_STATUSES = {"in progress", "in review"}


@dataclass(frozen=True)
class Task:
    number: int
    title: str
    url: str
    status: str


@dataclass(frozen=True)
class Protocol:
    comment: str
    card: telegram_cards.Card


def team():
    with open(os.path.join(ROOT, "team.json")) as f:
        return json.load(f)["members"]


def api(path, token, payload=None, method=None):
    req = urllib.request.Request(f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}{path}",
                                 json.dumps(payload).encode() if payload else None,
                                 {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"},
                                 method=method)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def assigned(token):
    """github-логин → задачи исполнителя только из двух активных колонок доски."""
    if not token:
        raise RuntimeError("для протокола нужен PROJECT_TOKEN с правом чтения доски")
    out, seen = {}, set()
    for row in board_status.board_items(token):
        i = row.get("content") or {}
        status = ((row.get("status") or {}).get("name") or "").strip().casefold()
        if (row.get("isArchived") or status not in ACTIVE_STATUSES or i.get("__typename") != "Issue"
                or i.get("state") != "OPEN"
                or i.get("repository", {}).get("nameWithOwner") != board_status.REPO
                or any(lb["name"] == "встреча" for lb in i.get("labels", {}).get("nodes", []))):
            continue
        if i["url"] in seen:
            continue
        seen.add(i["url"])
        for a in i.get("assignees", {}).get("nodes", []):
            out.setdefault(a["login"].lower(), []).append(Task(i["number"], i["title"], i["url"], status))
    return out


def text(date, tag, members, tasks):
    lines = [tag, f"Протокол {date:%d.%m.%Y}:", "Присутствовали:"]
    lines += [f"{m['last']} {m['first']}" for m in members]
    lines += ["", "Распределение по задачам:"]
    for m in members:
        items = tasks.get(m["github"].lower(), [])
        lines.append(f"{m['first']} {m['last']}:")
        lines += [f"{n}) {t.title} [{t.status}] — {t.url}" for n, t in enumerate(items, 1)] or ["задач нет"]
        lines.append("")
    return "\n".join(lines).rstrip()


def build(date, tag, meeting=None):
    members, tasks = team(), assigned(os.environ.get("PROJECT_TOKEN"))
    return Protocol(text(date, tag, members, tasks), telegram_cards.protocol_card(date, tag, members, tasks, meeting))


def comment(issue_number, body, token):
    api(f"/issues/{issue_number}/comments", token, {"body": body}, "POST")
