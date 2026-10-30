"""Кто что делает — для напоминания перед встречей.

С доски GitHub Projects (нужен секрет PROJECT_TOKEN — токен с правом читать проекты
организации): карточки этого репозитория в колонках «In progress» и «In review».
Номер доски — переменная PROJECT_NUMBER; если не задана, берётся первая доска
организации, где есть задачи этого репозитория.
Без токена — по самим задачам: открытые назначенные задачи и открытые PR.
"""
import json
import os
import urllib.request

import telegram_notify as tg

REPO = os.environ.get("GITHUB_REPOSITORY", "")
OWNER, _, NAME = REPO.partition("/")
LIMIT = 3500  # запас до лимита сообщения Telegram в 4096 символов


def http(url, token, payload=None):
    req = urllib.request.Request(url, json.dumps(payload).encode() if payload else None,
                                 {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


ITEMS = """
query($org: String!, $num: Int!, $after: String) {
  organization(login: $org) { projectV2(number: $num) { title
    items(first: 100, after: $after) { pageInfo { hasNextPage endCursor } nodes {
      status: fieldValueByName(name: "Status") { ... on ProjectV2ItemFieldSingleSelectValue { name } }
      content {
        ... on Issue { number title url state repository { nameWithOwner } assignees(first: 5) { nodes { login } } }
        ... on PullRequest { number title url state isDraft repository { nameWithOwner } author { login } }
      } } } } } }"""

PROJECTS = """
query($org: String!) { organization(login: $org) { projectsV2(first: 20, orderBy: {field: UPDATED_AT, direction: DESC}) {
  nodes { number closed items(first: 50) { nodes { content { ... on Issue { repository { nameWithOwner } }
                                                            ... on PullRequest { repository { nameWithOwner } } } } } } } } }"""


def gql(token, query, **variables):
    res = http("https://api.github.com/graphql", token, {"query": query, "variables": variables})
    if res.get("errors"):
        raise RuntimeError(res["errors"][0].get("message"))
    return res["data"]


def project_number(token):
    if os.environ.get("PROJECT_NUMBER"):
        return int(os.environ["PROJECT_NUMBER"])
    for p in gql(token, PROJECTS, org=OWNER)["organization"]["projectsV2"]["nodes"]:
        if not p["closed"] and any((n.get("content") or {}).get("repository", {}).get("nameWithOwner") == REPO
                                   for n in p["items"]["nodes"]):
            return p["number"]
    raise RuntimeError("не нашёл доску с задачами этого репозитория — задайте переменную PROJECT_NUMBER")


def from_board(token):
    num, after, rows = project_number(token), None, []
    while True:
        items = gql(token, ITEMS, org=OWNER, num=num, after=after)["organization"]["projectV2"]["items"]
        rows += items["nodes"]
        if not items["pageInfo"]["hasNextPage"]:
            break
        after = items["pageInfo"]["endCursor"]
    work, review = {}, []
    for n in rows:
        c, status = n.get("content") or {}, ((n.get("status") or {}).get("name") or "").lower()
        if c.get("repository", {}).get("nameWithOwner") != REPO or c.get("state") != "OPEN":
            continue
        if "review" in status or "ревью" in status:
            review.append((c, (c.get("author") or {}).get("login") or ", ".join(a["login"] for a in c.get("assignees", {}).get("nodes", []))))
        elif "progress" in status or "работ" in status:
            who = [a["login"] for a in c.get("assignees", {}).get("nodes", [])] or [(c.get("author") or {}).get("login") or "без исполнителя"]
            for w in who:
                work.setdefault(w, []).append(c)
    return work, review, "с доски"


def from_issues(token):
    base = f"https://api.github.com/repos/{REPO}"
    work, review = {}, []
    for i in http(f"{base}/issues?state=open&assignee=*&per_page=100", token):
        if "pull_request" in i or any(lb["name"] == "встреча" for lb in i["labels"]):
            continue
        for a in i["assignees"]:
            work.setdefault(a["login"], []).append({"number": i["number"], "title": i["title"], "url": i["html_url"]})
    for p in http(f"{base}/pulls?state=open&per_page=100", token):
        if not p["draft"]:
            review.append(({"number": p["number"], "title": p["title"], "url": p["html_url"]}, p["user"]["login"]))
    return work, review, "по назначенным задачам и открытым PR"


def summary():
    """Текст «кто что делает» в HTML для Telegram или пустая строка."""
    try:
        if os.environ.get("PROJECT_TOKEN"):
            try:
                work, review, src = from_board(os.environ["PROJECT_TOKEN"])
            except Exception as ex:  # noqa: BLE001 — доска недоступна, берём задачи
                print("доска:", ex)
                work, review, src = from_issues(os.environ["GITHUB_TOKEN"])
        else:
            work, review, src = from_issues(os.environ["GITHUB_TOKEN"])
    except Exception as ex:  # noqa: BLE001 — без статуса напоминание всё равно уходит
        print("статус задач:", ex)
        return ""
    lines = []
    if work:
        lines.append(f"<b>В работе</b> ({src}):")
        for who, items in sorted(work.items()):
            lines.append(f"• {tg.e(who)}: " + ", ".join(tg.link(c["url"], "#%s %s" % (c["number"], c["title"])) for c in items))
    if review:
        lines.append("<b>На ревью:</b>")
        lines += ["• %s — %s" % (tg.link(c["url"], "#%s %s" % (c["number"], c["title"])), tg.e(who)) for c, who in review]
    if not lines:
        return f"В работе ничего нет ({src})."
    text = "\n".join(lines)
    return text if len(text) <= LIMIT else text[:LIMIT].rsplit("\n", 1)[0] + "\n…"
