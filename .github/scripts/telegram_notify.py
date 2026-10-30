"""Оповещения GitHub → Telegram во все чаты, где есть бот.

У Bot API нет метода «список чатов бота», поэтому список копится сам:
- обновления бота (getUpdates) читает только workflow «Встречи» раз в 5 минут, по одному
  запуску за раз; прочитанные подтверждаются, поэтому команды обрабатываются ровно один раз;
- он добавляет чаты, куда бота добавили или где ему писали;
- удаляет чаты, откуда бота убрали или куда отправка вернула 403;
- список хранится в кэше Actions между запусками (файл chats.json);
- TELEGRAM_CHAT_ID (необязательно) — чаты, которые получают оповещения всегда:
  через запятую, для темы в чате — id:тема.
Команды в чате (в группе с темами — внутри нужной темы):
  /github   — оповещения GitHub (и напоминания, если не задано иначе) писать в эту тему
  /meetings — напоминания о встречах писать в эту тему
  /join     — тегать меня за 15 минут до встреч (администраторов чата бот тегает сам)
  /leave    — не тегать меня
  /protocols — протоколы встреч писать в эту тему
  /only github meetings protocols — в этот чат слать только перечисленное; /only — снова всё
  /off      — не писать в этот чат вообще; /on — снова писать
Оповещения о событиях GitHub (этот скрипт) список только читают.
"""
import html
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = os.environ.get("TOKEN", "")
STATIC = os.environ.get("CHATS", "")
STORE = os.environ.get("STORE", "chats.json")
API = f"https://api.telegram.org/bot{TOKEN}/"


def tg(method, **params):
    """Вызов Bot API. Сетевые сбои не роняют запуск: 3 попытки, потом {"ok": False}."""
    data = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None}).encode()
    for attempt in range(3):
        try:
            with urllib.request.urlopen(API + method, data, timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            try:
                return json.load(e)
            except ValueError:
                return {"ok": False, "error_code": e.code, "description": str(e)}
        except (OSError, ValueError) as e:  # таймаут, обрыв соединения, битый ответ
            print(f"{method}: попытка {attempt + 1} — {e}")
            time.sleep(2 * (attempt + 1))
    return {"ok": False, "description": "Telegram не ответил"}


META = {"last_update": 0}


def load():
    try:
        with open(STORE) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    if isinstance(data, dict):
        META["last_update"] = data.get("last_update", 0)
        data = data.get("chats", [])
    return {str(c["id"]): c for c in data}


def save(chats):
    with open(STORE, "w") as f:
        json.dump({"chats": list(chats.values()), "last_update": META["last_update"]}, f, ensure_ascii=False)


COMMANDS = {"/github": ("thread", "Оповещения GitHub буду писать в эту тему."),
            "/meetings": ("meetings_thread", "Напоминания о встречах буду писать в эту тему."),
            "/protocols": ("protocols_thread", "Протоколы встреч буду писать в эту тему.")}
KINDS = {"github": "оповещения GitHub", "meetings": "напоминания о встречах", "protocols": "протоколы встреч"}


def command(chat, msg, fresh):
    """Команды /github, /meetings — тема для оповещений; /join, /leave — кого тегать перед встречей."""
    word = (msg.get("text") or "").split()[0].split("@")[0].lower() if msg.get("text") else ""
    thread = msg.get("message_thread_id") if msg.get("is_topic_message") else None
    user = msg.get("from") or {}
    if word in ("/join", "/leave") and user and not user.get("is_bot"):
        members = chat.setdefault("members", {})
        name = user.get("first_name") or user.get("username") or str(user["id"])
        if word == "/join":
            members[str(user["id"])] = {"name": name, "username": user.get("username")}
            answer = f"{name}, буду тегать тебя за 15 минут до встреч."
        else:
            members.pop(str(user["id"]), None)
            answer = f"{name}, больше не тегаю."
        if fresh:
            tg("sendMessage", chat_id=chat["id"], message_thread_id=thread, text=answer)
        return
    if word == "/only":
        wanted = [w.lower() for w in msg["text"].split()[1:] if w.lower() in KINDS]
        if wanted:
            chat["only"] = wanted
            answer = "В этот чат пишу только: " + ", ".join(KINDS[w] for w in wanted) + ". Вернуть всё — /only"
        else:
            chat.pop("only", None)
            answer = "В этот чат снова пишу всё."
        if fresh:
            tg("sendMessage", chat_id=chat["id"], message_thread_id=thread, text=answer)
        return
    if word in ("/off", "/on"):
        chat["off"] = word == "/off"
        if fresh:
            tg("sendMessage", chat_id=chat["id"], message_thread_id=thread,
               text="Больше не пишу в этот чат. Вернуть — /on" if chat["off"] else "Снова пишу в этот чат.")
        return
    if word not in COMMANDS:
        return
    field, answer = COMMANDS[word]
    if thread:
        chat[field] = thread
    else:
        chat.pop(field, None)
        answer = answer.replace("в эту тему", "в общий чат")
    if fresh:
        tg("sendMessage", chat_id=chat["id"], message_thread_id=thread, text=answer)


def discover(chats):
    res = tg("getUpdates", offset=META["last_update"] + 1 if META["last_update"] else None,
             allowed_updates=json.dumps(["my_chat_member", "message", "channel_post"]))
    if not res.get("ok"):
        print("getUpdates:", res.get("description"))
        return
    last = META["last_update"]
    for u in res["result"]:
        fresh = u["update_id"] > last
        META["last_update"] = max(META["last_update"], u["update_id"])
        if "my_chat_member" in u:
            m = u["my_chat_member"]
            chat, status = m["chat"], m["new_chat_member"]["status"]
            if status in ("left", "kicked"):
                chats.pop(str(chat["id"]), None)
            else:
                chats.setdefault(str(chat["id"]), {"id": chat["id"]})["title"] = title_of(chat)
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
            entry = chats.setdefault(str(chat["id"]), {"id": chat["id"], "title": title_of(chat)})
            command(entry, msg, fresh)


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
            if any(lb.get("name") == "встреча" for lb in i.get("labels", [])):
                return f"📅 Новая встреча {t}\nот {who}"
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


def mentions(chat_id, chats):
    """Кого тегнуть в чате: администраторы-люди и все, кто написал /join."""
    people = dict((chats.get(chat_id) or {}).get("members", {}))
    res = tg("getChatAdministrators", chat_id=chat_id)
    for m in res.get("result", []) if res.get("ok") else []:
        u = m["user"]
        if not u.get("is_bot"):
            people.setdefault(str(u["id"]), {"name": u.get("first_name") or u.get("username"), "username": u.get("username")})
    return " ".join(f"@{p['username']}" if p.get("username") else f'<a href="tg://user?id={uid}">{e(p["name"])}</a>'
                    for uid, p in people.items())


def targets(chats, kind="github"):
    """Куда слать: чаты из TELEGRAM_CHAT_ID и все известные чаты бота, с темой."""
    out = [s.strip() for s in STATIC.split(",") if s.strip()]
    static_ids = {t.partition(":")[0] for t in out}
    for key, c in chats.items():
        if key in static_ids or c.get("off") or (c.get("only") and kind not in c["only"]):
            continue
        thread = {"meetings": c.get("meetings_thread"),
                  "protocols": c.get("protocols_thread") or c.get("meetings_thread")}.get(kind)
        thread = thread or c.get("thread")
        out.append(f"{key}:{thread}" if thread else key)
    return out


def send(target, text, chats):
    chat_id, _, thread = target.partition(":")
    res = tg("sendMessage", chat_id=chat_id, message_thread_id=thread or None, text=text,
             parse_mode="HTML", disable_web_page_preview="true")
    if res.get("ok"):
        return True
    new = (res.get("parameters") or {}).get("migrate_to_chat_id")
    if new:
        old = chats.pop(chat_id, {})
        chats[str(new)] = {**old, "id": new}
        return send(f"{new}:{thread}" if thread else str(new), text, chats)
    print(f"{chat_id}: {res.get('error_code')} {res.get('description')}")
    if res.get("error_code") == 403 or "chat not found" in str(res.get("description")):
        chats.pop(chat_id, None)
    return False


def main():
    if not TOKEN:
        print("::notice::Секрет TELEGRAM_BOT_TOKEN не задан — оповещение пропущено")
        return
    chats = load()
    name = os.environ["GITHUB_EVENT_NAME"]
    text = None
    if name not in ("schedule", "workflow_dispatch"):
        with open(os.environ["GITHUB_EVENT_PATH"]) as f:
            text = message(name, json.load(f))
    if text:
        to = targets(chats)
        sent = sum(send(t, text, chats) for t in to)
        print(f"Отправлено в {sent} из {len(to)} чатов")
    else:
        print("Нечего отправлять")
    print_chats(chats)


def print_chats(chats):
    def one(c):
        extra = [f"тема {c['thread']}" if c.get("thread") else "", f"встречи — тема {c['meetings_thread']}" if c.get("meetings_thread") else ""]
        extra = ", ".join(x for x in extra if x)
        return f"{c.get('title', c['id'])} ({c['id']}{', ' + extra if extra else ''})"
    print("Чаты бота:", "; ".join(one(c) for c in chats.values()) or "нет")


if __name__ == "__main__":
    sys.exit(main())
