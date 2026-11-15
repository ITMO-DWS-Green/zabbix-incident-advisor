"""Карточки Bot API Rich HTML и совместимый sendMessage-вариант.

Формат: https://core.telegram.org/bots/api#rich-html-style (Bot API 10.4).
Только представление: модуль не обращается к сети и не отправляет сообщения.
"""
from dataclasses import dataclass
import html


def escape(value):
    return html.escape(str(value or ""), quote=True)


def link(url, title):
    return f'<a href="{escape(url)}">{escape(title)}</a>'


def button(url, title, style="primary"):
    return (f'<tg-button-row><tg-button type="url" style="{style}" url="{escape(url)}">'
            f'{escape(title)}</tg-button></tg-button-row>')


@dataclass(frozen=True)
class Card:
    rich_html: str
    fallback_parts: tuple[str, ...]


def card(rich, lines):
    """Упаковать целые HTML-строки, не разрезая теги или entities резервного сообщения."""
    parts, current = [], ""
    for line in lines:
        # Строки формируются ниже из коротких полей; длинный протокол состоит из многих строк.
        if len(line.encode("utf-16-le")) // 2 > 3500:
            raise ValueError("слишком длинная строка карточки")
        candidate = current + ("\n" if current else "") + line
        if len(candidate.encode("utf-16-le")) // 2 > 3500:
            parts.append(current)
            current = line
        else:
            current = candidate
    if current:
        parts.append(current)
    return Card("".join(rich), tuple(parts))


def preview(value, limit=500):
    value = " ".join(str(value or "").split())
    return value if len(value) <= limit else value[:limit].rstrip() + "…"


def protocol_card(date, tag, members, tasks, meeting=None):
    title = f"Протокол · {date:%d.%m.%Y}"
    visible = {t.url: t for m in members for t in tasks.get(m["github"].lower(), [])}
    progress = sum(t.status == "in progress" for t in visible.values())
    review = sum(t.status == "in review" for t in visible.values())
    stats = f"Участников: {len(members)} · В работе: {progress} · На ревью: {review}"
    rich = [f"<h2>📋 {title}</h2>", f"<p>{escape(tag)}</p>"]
    lines = [f"<b>📋 {title}</b>", escape(tag)]
    if meeting:
        rich.append(f"<p>{escape(meeting['title'])}</p>")
        lines.append(escape(meeting["title"]))
    rich.append('<table compact><tr><th>Участники</th><th>В работе</th><th>На ревью</th></tr>'
                f'<tr><td>{len(members)}</td><td>{progress}</td><td>{review}</td></tr></table>')
    attendees = ", ".join(f"{m['first']} {m['last']}" for m in members)
    rich.append(f"<details><summary>Присутствовали</summary><p>{escape(attendees)}</p></details><hr/>")
    lines += [stats, "", "<b>Присутствовали</b>"]
    lines += [escape(f"{m['first']} {m['last']}") for m in members]
    for member in members:
        name = f"{member['first']} {member['last']}"
        items = tasks.get(member["github"].lower(), [])
        rich.append(f"<h3>{escape(name)}</h3>")
        lines += ["", f"<b>{escape(name)}</b>"]
        if not items:
            rich.append("<p><i>Нет задач в работе или на ревью</i></p>")
            lines.append("<i>Нет задач в работе или на ревью</i>")
            continue
        rich.append("<ul>")
        for task in items:
            status = "🟦 В работе" if task.status == "in progress" else "🟨 На ревью"
            ref = link(task.url, f"#{task.number} {task.title}")
            rich.append(f"<li><p>{ref}<br/><i>{status}</i></p></li>")
            lines.append(f"{status} · {ref}")
        rich.append("</ul>")
    rich.append("<hr/><footer>Срез доски на момент протокола · In progress / In review</footer>")
    if meeting:
        rich.append(button(meeting["html_url"], "Открыть встречу в GitHub"))
        lines += ["", link(meeting["html_url"], "Открыть встречу в GitHub")]
    return card(rich, lines)


def issue_card(event_name, event):
    """Карточки событий issues и комментариев к задачам; остальные события оформляет старый путь."""
    issue = event.get("issue") or {}
    if not issue or issue.get("pull_request"):
        return None
    if any(lb.get("name") == "встреча" for lb in issue.get("labels", [])):
        return None
    action = event.get("action")
    who = (event.get("sender") or {}).get("login", "")
    target = issue["html_url"]
    body = issue.get("body")
    style = "primary"
    if event_name == "issue_comment" and action == "created":
        heading, detail = "💬 Комментарий к задаче", f"Автор: {who}"
        body = event["comment"].get("body")
        target = event["comment"]["html_url"]
    elif event_name == "issues":
        if action == "opened":
            heading, detail = "Новая задача", f"Создал: {who}"
        elif action == "assigned":
            assignee = event["assignee"]["login"]
            heading, detail = "Задача назначена", f"Исполнитель: {assignee} · Назначил: {who}"
        elif action == "closed":
            planned = issue.get("state_reason") != "not_planned"
            heading = "✅ Задача выполнена" if planned else "Задача закрыта как неактуальная"
            detail, style = f"Закрыл: {who}", "success" if planned else "primary"
        elif action == "reopened":
            heading, detail = "Задача снова открыта", f"Открыл: {who}"
        else:
            return None
    else:
        return None
    title = f"#{issue['number']} {issue['title']}"
    fields = [("Состояние", "Закрыта" if issue.get("state") == "closed" else "Открыта")]
    assignees = ", ".join(a["login"] for a in issue.get("assignees", []))
    fields.append(("Исполнители", assignees or "Не назначены"))
    milestone = issue.get("milestone")
    if milestone:
        fields.append(("Спринт", milestone["title"]))
    labels = [lb["name"] for lb in issue.get("labels", [])]
    priorities = [name for name in labels if name in ("P1", "P2", "P3")]
    if priorities:
        fields.append(("Приоритет", ", ".join(priorities)))
    areas = [name for name in labels if name not in priorities]
    if areas:
        fields.append(("Метки", preview(" · ".join(areas), 200)))
    rich = [f"<footer>{escape(heading)}</footer>", f"<h2>{escape(title)}</h2>",
            f"<p>{escape(detail)}</p>", "<table compact>"]
    rich += [f"<tr><td><b>{escape(k)}</b></td><td>{escape(v)}</td></tr>" for k, v in fields]
    rich.append("</table>")
    lines = [f"<b>{escape(heading)}</b>", link(issue["html_url"], title), escape(detail), ""]
    lines += [f"<b>{escape(k)}:</b> {escape(v)}" for k, v in fields]
    snippet = preview(body)
    if snippet:
        rich.append(f"<blockquote>{escape(snippet)}</blockquote>")
        lines += ["", escape(snippet)]
    rich.append(button(target, "Открыть комментарий" if event_name == "issue_comment" else "Открыть задачу", style))
    lines += ["", link(target, "Открыть в GitHub")]
    return card(rich, lines)
