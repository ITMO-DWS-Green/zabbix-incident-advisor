"""Оповещения GitHub → Telegram во все чаты, где есть бот.

У Bot API нет метода «список чатов бота», поэтому список копится сам:
- каждый запуск читает последние обновления бота (getUpdates, без подтверждения —
  Telegram хранит их 24 часа) и добавляет чаты, куда бота добавили или где ему писали;
- удаляет чаты, откуда бота убрали или куда отправка вернула 403;
- список хранится в кэше Actions между запусками (файл chats.json);
- TELEGRAM_CHAT_ID (необязательно) — чаты, которые получают оповещения всегда:
  через запятую, для темы в чате — id:тема.
Расписание раз в 6 часов в workflow подхватывает новые чаты и не даёт кэшу протухнуть.
"""
import html
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("TOKEN", "")
STATIC = os.environ.get("CHATS", "")
STORE = os.environ.get("STORE", "chats.json")
API = f"https://api.telegram.org/bot{TOKEN}/"


def tg(method, **params):
    data = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None}).encode()
    try:
        with urllib.request.urlopen(API + method, data, timeout=20) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return json.load(e)
        except ValueError:
            return {"ok": False, "error_code": e.code, "description": str(e)}


def load():
    try:
        with open(STORE) as f:
            return {str(c["id"]): c for c in json.load(f)}
    except (OSError, ValueError):
        return {}


def discover(chats):
    res = tg("getUpdates", allowed_updates=json.dumps(["my_chat_member", "message", "channel_post"]))
    if not res.get("ok"):
        print("getUpdates:", res.get("description"))
        return
    for u in res["result"]:
        if "my_chat_member" in u:
            m = u["my_chat_member"]
            chat, status = m["chat"], m["new_chat_member"]["status"]
            if status in ("left", "kicked"):
                chats.pop(str(chat["id"]), None)
            else:
                chats[str(chat["id"])] = {"id": chat["id"], "title": title_of(chat)}
            continue
        msg = u.get("message") or u.get("channel_post")
        if not msg:
            continue
        chat = msg["chat"]
        if "migrate_to_chat_id" in msg:
            chats.pop(str(chat["id"]), None)
            new = msg["migrate_to_chat_id"]
            chats[str(new)] = {"id": new, "title": title_of(chat)}
        elif "left_chat_member" not in msg:
            chats.setdefault(str(chat["id"]), {"id": chat["id"], "title": title_of(chat)})


def title_of(chat):
    return chat.get("title") or chat.get("username") or chat.get("first_name") or str(chat["id"])


# ---------- текст оповещения ----------

def e(s):
    return html.escape(str(s or ""), quote=False)


def link(url, text):
    return f'<a href="{html.escape(url or "")}">{e(text)}</a>'


def short(s):
    s = " ".join(str(s or "").split())
    return e(s[:300] + ("…" if len(s) > 300 else ""))


def message(name, ev):
    who = (ev.get("sender") or {}).get("login", "")
    action = ev.get("action")
    if name == "issues":
        i = ev["issue"]
        t = link(i["html_url"], f"#{i['number']} {i['title']}")
        if action == "opened":
            return f"🆕 Новая задача {t}\nот {who}"
        if action == "closed":
            how = "закрыта как неактуальная" if i.get("state_reason") == "not_planned" else "выполнена"
            return f"✅ Задача {how}: {t}\nзакрыл {who}" if how == "выполнена" else f"🚫 Задача {how}: {t}\nзакрыл {who}"
        if action == "reopened":
            return f"↩️ Задача снова открыта: {t}\n{who}"
        if action == "assigned":
            a = ev["assignee"]["login"]
            return f"👤 {a} взял задачу {t}" if a == who else f"👤 {a} назначен на задачу {t}\nназначил {who}"
    if name == "issue_comment" and action == "created":
        i, c = ev["issue"], ev["comment"]
        kind = "PR" if i.get("pull_request") else "задаче"
        ref = link(c["html_url"], "#%s %s" % (i["number"], i["title"]))
        return f"💬 {who} в {kind} {ref}:\n{short(c['body'])}"
    if name == "pull_request":
        p = ev["pull_request"]
        t = link(p["html_url"], f"PR #{p['number']} {p['title']}")
        if action == "opened":
            return f"📝 Черновик {t} от {who}" if p.get("draft") else f"🔀 Новый {t}\nот {who}, нужно ревью"
        if action == "ready_for_review":
            return f"🔀 {t} готов к ревью\nот {who}"
        if action == "review_requested" and ev.get("requested_reviewer"):
            return f"👀 {ev['requested_reviewer']['login']}, посмотри {t}\nпросит {who}"
        if action == "reopened":
            return f"↩️ {t} снова открыт"
        if action == "closed":
            return f"🟣 {t} слит в main\n{who}" if p.get("merged") else f"✖️ {t} закрыт без слияния"
    if name == "pull_request_review":
        r, p = ev["review"], ev["pull_request"]
        t = link(r["html_url"], f"PR #{p['number']} {p['title']}")
        body = short(r.get("body"))
        head = {"approved": f"👍 {who} одобрил {t}",
                "changes_requested": f"✋ {who} просит доработать {t}",
                "commented": f"💬 {who} оставил ревью к {t}" if body else None}.get(r["state"])
        return head and (f"{head}\n{body}" if body else head)
    if name == "workflow_run":
        w = ev["workflow_run"]
        if w.get("conclusion") != "failure" or w.get("event") != "pull_request":
            return None
        prs = w.get("pull_requests") or []
        where = f"CI в PR #{prs[0]['number']}" if prs else "CI"
        ref = link(w["html_url"], "%s (%s)" % (where, w["head_branch"]))
        return f"❌ Упал {ref}\nавтор {w['actor']['login']}"
    return None


def send(target, text, chats):
    chat_id, _, thread = target.partition(":")
    res = tg("sendMessage", chat_id=chat_id, message_thread_id=thread or None, text=text,
             parse_mode="HTML", disable_web_page_preview="true")
    if res.get("ok"):
        return True
    new = (res.get("parameters") or {}).get("migrate_to_chat_id")
    if new:
        old = chats.pop(chat_id, {})
        chats[str(new)] = {"id": new, "title": old.get("title", str(new))}
        return send(str(new), text, chats)
    print(f"{chat_id}: {res.get('error_code')} {res.get('description')}")
    if res.get("error_code") == 403 or "chat not found" in str(res.get("description")):
        chats.pop(chat_id, None)
    return False


def main():
    if not TOKEN:
        print("::notice::Секрет TELEGRAM_BOT_TOKEN не задан — оповещение пропущено")
        return
    chats = load()
    discover(chats)
    name = os.environ["GITHUB_EVENT_NAME"]
    text = None
    if name not in ("schedule", "workflow_dispatch"):
        with open(os.environ["GITHUB_EVENT_PATH"]) as f:
            text = message(name, json.load(f))
    if text:
        targets = [s.strip() for s in STATIC.split(",") if s.strip()]
        static_ids = {t.partition(":")[0] for t in targets}
        targets += [k for k in list(chats) if k not in static_ids]
        sent = sum(send(t, text, chats) for t in targets)
        print(f"Отправлено в {sent} из {len(targets)} чатов")
    else:
        print("Нечего отправлять")
    with open(STORE, "w") as f:
        json.dump(list(chats.values()), f, ensure_ascii=False)
    print("Чаты бота:", ", ".join(f"{c['title']} ({c['id']})" for c in chats.values()) or "нет")


if __name__ == "__main__":
    sys.exit(main())
