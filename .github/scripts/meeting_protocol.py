"""Протокол встречи: присутствовавшие (по умолчанию вся команда) и распределение по задачам.

Отправляется в начале встречи, если в задаче встречи заполнено поле «Протокол» —
кого отметить в Telegram (например, @AVEroshkin). Команда — .github/team.json,
задачи — открытые задачи репозитория по исполнителям (кроме встреч).
"""
import json
import os
import urllib.request

ROOT = os.path.join(os.path.dirname(__file__), "..")


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
    """github-логин → список (название, URL) открытых задач, где он исполнитель."""
    out = {}
    for i in api("/issues?state=open&assignee=*&per_page=100&sort=created&direction=asc", token):
        if "pull_request" in i or any(lb["name"] == "встреча" for lb in i["labels"]):
            continue
        for a in i["assignees"]:
            out.setdefault(a["login"].lower(), []).append((i["title"], i["html_url"]))
    return out


def text(date, tag, members, tasks):
    lines = [tag, f"Протокол {date:%d.%m.%Y}:", "Присутствовали:"]
    lines += [f"{m['last']} {m['first']}" for m in members]
    lines += ["", "Распределение по задачам:"]
    for m in members:
        items = tasks.get(m["github"].lower(), [])
        lines.append(f"{m['first']} {m['last']}:")
        lines += [f"{n}) {title} — {url}" for n, (title, url) in enumerate(items, 1)] or ["задач нет"]
        lines.append("")
    return "\n".join(lines).rstrip()


def build(date, tag, token):
    return text(date, tag, team(), assigned(token))


def comment(issue_number, body, token):
    api(f"/issues/{issue_number}/comments", token, {"body": body}, "POST")
