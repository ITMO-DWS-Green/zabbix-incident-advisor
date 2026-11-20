"""Однократная настройка Zabbix для разработки расширения. Только стандартная библиотека Python.

1. Агент мониторит сам Zabbix server (интерфейс → контейнер zabbix-agent2).
2. Токен API для сервиса → файл /secrets/zabbix-api-token (сервис читает его при каждом вызове).
3. Коннекторы: события → сервис; значения элементов с тегом advisor → сервис.
4. Модуль интерфейса incident-advisor зарегистрирован и включён.
5. Хосты стенда test-server с тегом depends_on (карта связей), метриками, триггерами и лог-элементом
   (агент читает файл, который пишет сервис).
6. Оповещение о разборе в Telegram (FR-08): тег team у хостов, тип оповещения, пользователь на команду
   и действие, которое при комментарии к проблеме шлёт разбор в чат ответственной команды.

Повторный запуск безопасен: существующее не дублируется, токен пересоздаётся.
"""
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

URL = os.environ.get("ZABBIX_URL", "http://localhost:8070").rstrip("/") + "/api_jsonrpc.php"
USER = os.environ.get("ZABBIX_USER", "Admin")
PASSWORD = os.environ.get("ZABBIX_PASSWORD", "zabbix")
ADVISOR_URL = os.environ.get("ADVISOR_URL", "http://advisor:8080").rstrip("/")
CONNECTOR_TOKEN = os.environ.get("ADVISOR_CONNECTOR_TOKEN", "dev-connector-token")
TOKEN_FILE = os.environ.get("TOKEN_FILE", "/secrets/zabbix-api-token")
RETENTION_TOKEN_FILE = os.environ.get("RETENTION_TOKEN_FILE")
STAND_URL = os.environ.get("STAND_URL_PATTERN", "http://{service}:8080")
STAND_LOG_DIR = os.environ.get("STAND_LOG_DIR", "/var/log/stand")  # общий том сервисов стенда и zabbix-agent2
# Оповещение в Telegram (FR-08). Токен бота в репозитории не хранится — только в окружении.
# Без токена стенд поднимается как раньше, тип оповещения создаётся выключенным.
TELEGRAM_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_API_URL = os.environ.get("TELEGRAM_API_URL", "https://api.telegram.org/bot")  # на стенде — заглушка
WEBHOOK_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "telegram_webhook.js")

# Стенд test-server: кто кого вызывает (совпадает с compose.yaml репозитория test-server).
STAND = {
    "gateway": ["orders"],
    "orders": ["payments", "database"],
    "payments": ["database"],
    "database": [],
}

# Справочник «сервис — команда» (D-006): тег team у хоста. Одной команде — один групповой чат.
TEAMS = {
    "gateway": "platform",
    "orders": "orders",
    "payments": "payments",
    "database": "data",
}

HTTP_AGENT, DEPENDENT, ZABBIX_ACTIVE = 19, 18, 7
FLOAT, CHAR, LOG, TEXT = 0, 1, 2, 4
JSONPATH, JAVASCRIPT = 12, 21
BEARER = 5
EVENTS, ITEM_VALUES = 1, 0
TAG_EXISTS = 12
MEDIA_TYPE_WEBHOOK = 4
EVENT_SOURCE_TRIGGERS = 0
CONDITION_TYPE_EVENT_TAG = 25
CONDITION_TYPE_EVENT_TAG_VALUE = 26
CONDITION_OPERATOR_EQUAL = 0
# Условие «значение тега события»: имя тега — в value2, значение — в value (иначе API вернёт ошибку).
EVENT_TAG_NAME = "team"
OPERATION_TYPE_MESSAGE = 0
PERM_READ = 2

token = None
request_id = 0


def call(method, params):
    global request_id
    request_id += 1
    body = json.dumps({"jsonrpc": "2.0", "method": method, "params": params, "id": request_id}).encode()
    headers = {"Content-Type": "application/json-rpc"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(URL, data=body, headers=headers), timeout=20) as resp:
        answer = json.load(resp)
    if "error" in answer:
        raise RuntimeError(f"{method}: {answer['error'].get('data') or answer['error']}")
    return answer["result"]


def wait_for_api():
    for attempt in range(90):
        try:
            print(f"Zabbix API {call('apiinfo.version', {})}")
            return
        except (urllib.error.URLError, ConnectionError, RuntimeError, TimeoutError) as e:
            print(f"Жду API ({attempt + 1}/90): {e}")
            time.sleep(5)
    sys.exit("Zabbix API так и не поднялся")


def point_server_host_to_agent():
    hosts = call("host.get", {"filter": {"host": ["Zabbix server"]}, "selectInterfaces": ["interfaceid", "dns", "useip"]})
    for iface in hosts[0]["interfaces"] if hosts else []:
        if not (iface["useip"] == "0" and iface["dns"] == "zabbix-agent2"):
            call("hostinterface.update", {"interfaceid": iface["interfaceid"], "useip": 0, "dns": "zabbix-agent2", "ip": ""})
    print("Zabbix server опрашивает zabbix-agent2")


def issue_service_token():
    userid = call("user.get", {"filter": {"username": [USER]}, "output": ["userid"]})[0]["userid"]
    old = call("token.get", {"filter": {"name": ["incident-advisor"]}, "output": ["tokenid"]})
    if old:
        call("token.delete", [t["tokenid"] for t in old])
    tokenid = call("token.create", {"name": "incident-advisor", "userid": userid,
                                    "description": "Токен Java-сервиса, создан setup.py"})["tokenids"][0]
    value = call("token.generate", [tokenid])[0]["token"]
    os.makedirs(os.path.dirname(TOKEN_FILE), exist_ok=True)
    with open(TOKEN_FILE, "w") as f:
        f.write(value)
    print(f"Токен API для сервиса записан в {TOKEN_FILE}")


def ensure_connector(name, data_type, path, tags=None):
    if call("connector.get", {"filter": {"name": [name]}}):
        print(f"Коннектор «{name}» уже есть")
        return
    params = {"name": name, "data_type": data_type, "url": ADVISOR_URL + path, "authtype": BEARER,
              "token": CONNECTOR_TOKEN, "timeout": "5s", "max_attempts": 3, "status": 1,
              "description": "Поток для incident-advisor"}
    if tags:
        params["tags"] = tags
    call("connector.create", params)
    print(f"Коннектор «{name}» → {ADVISOR_URL + path}")


def ensure_module():
    if call("module.get", {"filter": {"id": ["incident-advisor"]}}):
        print("Модуль incident-advisor уже зарегистрирован")
        return
    call("module.create", {"id": "incident-advisor", "relative_path": "modules/incident-advisor", "status": 1})
    print("Модуль incident-advisor включён: Monitoring → Incident advisor")


def ensure_group(name):
    groups = call("hostgroup.get", {"filter": {"name": [name]}})
    return groups[0]["groupid"] if groups else call("hostgroup.create", {"name": name})["groupids"][0]


def ratio(field):
    return f"var s = JSON.parse(value).stats; return s.requests > 0 ? s.{field} / s.requests : 0;"


def ensure_log_item(hostid, service):
    """Активная проверка агента: имя хоста в Zabbix = Hostname агента (compose.yaml). 100 строк/с — запас на поток ошибок."""
    key = f"log[{STAND_LOG_DIR}/{service}.log,,,100]"
    if call("item.get", {"hostids": [hostid], "filter": {"key_": [key]}}):
        return
    call("item.create", {
        "hostid": hostid, "name": "Лог сервиса", "key_": key, "type": ZABBIX_ACTIVE, "value_type": LOG,
        "delay": "1s", "history": "1d", "tags": [{"tag": "component", "value": "logs"}],
    })
    print(f"Хост {service}: лог-элемент {key}")


def create_stand_host(service, deps, groupid):
    existing = call("host.get", {"filter": {"host": [service]}, "output": ["hostid"]})
    if existing:
        hostid = existing[0]["hostid"]
        ensure_log_item(hostid, service)
        ensure_team_tag(hostid, service)
        print(f"Хост {service} уже есть")
        return
    tags = [{"tag": "stand", "value": "test-server"}, {"tag": "team", "value": TEAMS[service]}]
    if deps:
        tags.append({"tag": "depends_on", "value": ",".join(deps)})
    hostid = call("host.create", {"host": service, "groups": [{"groupid": groupid}], "tags": tags})["hostids"][0]
    base = STAND_URL.format(service=service)
    stream_tag = [{"tag": "advisor", "value": "stream"}]

    call("item.create", {
        "hostid": hostid, "name": "Здоровье", "key_": "testserver.health", "type": HTTP_AGENT, "value_type": CHAR,
        "url": f"{base}/actuator/health", "status_codes": "200,503", "delay": "10s", "timeout": "3s",
        "preprocessing": [{"type": JSONPATH, "params": "$.status", "error_handler": 0, "error_handler_params": ""}],
    })
    master = call("item.create", {
        "hostid": hostid, "name": "Статус test-server (JSON)", "key_": "testserver.status", "type": HTTP_AGENT,
        "value_type": TEXT, "url": f"{base}/chaos/status", "delay": "5s", "timeout": "3s", "history": "1d",
    })["itemids"][0]
    for name, key, units, prep_type, prep in [
        ("Запросов в секунду", "testserver.rps", "rps", JSONPATH, "$.stats.requests"),
        ("Доля 5xx", "testserver.error_rate", "", JAVASCRIPT, ratio("serverErrors")),
        ("Доля 4xx", "testserver.client_error_rate", "", JAVASCRIPT, ratio("clientErrors")),
        ("Средняя задержка", "testserver.latency", "ms", JSONPATH, "$.stats.avgLatencyMs"),
    ]:
        call("item.create", {
            "hostid": hostid, "name": name, "key_": key, "type": DEPENDENT, "master_itemid": master,
            "value_type": FLOAT, "units": units, "tags": stream_tag,
            "preprocessing": [{"type": prep_type, "params": prep, "error_handler": 0, "error_handler_params": ""}],
        })
    triggers = [
        (f"{service}: сервис недоступен", f'last(/{service}/testserver.health)<>"UP"', 4),
        (f"{service}: нет ответа больше минуты", f"nodata(/{service}/testserver.health,1m)=1", 4),
        (f"{service}: доля 5xx выше 10%", f"avg(/{service}/testserver.error_rate,1m)>0.1", 3),
        (f"{service}: доля 4xx выше 20%", f"avg(/{service}/testserver.client_error_rate,1m)>0.2", 2),
        (f"{service}: задержка выше 1 с", f"avg(/{service}/testserver.latency,1m)>1000", 2),
    ]
    call("trigger.create", [{"description": d, "expression": e, "priority": p,
                             "tags": [{"tag": "service", "value": service}]} for d, e, p in triggers])
    ensure_log_item(hostid, service)
    print(f"Хост {service} → {base}, зависит от: {', '.join(deps) or '—'}")


def ensure_team_tag(hostid, service):
    """Тег team нужен и на хостах, созданных прошлым запуском setup, — иначе оповещение не найдёт команду."""
    tags = call("host.get", {"hostids": [hostid], "selectTags": "extend"})[0]["tags"]
    if any(t["tag"] == "team" for t in tags):
        return
    call("host.update", {"hostid": hostid, "tags": tags + [{"tag": "team", "value": TEAMS[service]}]})
    print(f"Хост {service}: добавлен тег team={TEAMS[service]}")


def telegram_parameters():
    return [
        {"name": "api_token", "value": TELEGRAM_TOKEN or "<не задан TELEGRAM_BOT_TOKEN>"},
        {"name": "api_chat_id", "value": "{ALERT.SENDTO}"},
        {"name": "api_url", "value": TELEGRAM_API_URL},
        {"name": "alert_subject", "value": "{ALERT.SUBJECT}"},
        {"name": "alert_message", "value": "{ALERT.MESSAGE}"},
        {"name": "api_parse_mode", "value": ""},
    ]


def ensure_telegram_mediatype():
    """Тип оповещения — вебхук. Токен берётся из окружения и в репозиторий не попадает; без него тип выключен.

    Повторный запуск не только включает тип, но и обновляет токен, адрес и скрипт: иначе тип,
    созданный без токена, навсегда остаётся с заглушкой «<не задан …>», и отправка падает.
    """
    with open(WEBHOOK_SCRIPT, encoding="utf-8") as fh:
        script = fh.read()
    found = call("mediatype.get", {"filter": {"name": ["Telegram"]}, "output": ["mediatypeid"]})
    if found:
        call("mediatype.update", {"mediatypeid": found[0]["mediatypeid"], "status": 0 if TELEGRAM_TOKEN else 1,
                                  "script": script, "parameters": telegram_parameters()})
        state = "обновлён и включён" if TELEGRAM_TOKEN else "обновлён, оставлен выключенным — нет токена"
        print(f"Тип оповещения Telegram уже был — {state}")
        return found[0]["mediatypeid"]
    mediatypeid = call("mediatype.create", {
        "name": "Telegram", "type": MEDIA_TYPE_WEBHOOK, "status": 0 if TELEGRAM_TOKEN else 1,
        "script": script, "timeout": "10s", "process_tags": 0,
        "parameters": telegram_parameters(),
    })["mediatypeids"][0]
    state = "включён" if TELEGRAM_TOKEN else "создан выключенным — нет TELEGRAM_BOT_TOKEN"
    print(f"Тип оповещения Telegram {state}")
    return mediatypeid


def parse_chat_ids():
    """TELEGRAM_CHAT_IDS='platform:-1001,orders:-1002' — id группового чата на команду. Незаданные → отрицательные id-заглушки."""
    raw = os.environ.get("TELEGRAM_CHAT_IDS", "")
    chats = {}
    for pair in filter(None, (p.strip() for p in raw.split(","))):
        team, _, chat = pair.partition(":")
        if team and chat:
            chats[team.strip()] = chat.strip()
    for team in sorted(set(TEAMS.values())):
        chats.setdefault(team, str(-(10_000 + sum(ord(c) for c in team))))
    return chats


def ensure_team_user(team, chat_id, mediatypeid, usrgrpid, roleid):
    """Пользователь на команду: в медиа — id её группового чата. Действие шлёт разбор именно ему."""
    username = f"team-{team}"
    found = call("user.get", {"filter": {"username": [username]}, "output": ["userid"]})
    if found:
        return found[0]["userid"]
    userid = call("user.create", {
        "username": username, "passwd": os.urandom(18).hex(), "roleid": roleid,
        "usrgrps": [{"usrgrpid": usrgrpid}],
        "medias": [{"mediatypeid": mediatypeid, "sendto": [chat_id], "active": 0,
                    "severity": 63, "period": "1-7,00:00-24:00"}],
    })["userids"][0]
    print(f"Команда {team}: пользователь {username}, чат {chat_id}")
    return userid


def ensure_notification_action(team, userid, mediatypeid):
    """Действие по тегу team: комментарий к проблеме (наш разбор) уходит в чат этой команды.

    Оповещения о самой проблеме нет — только операция обновления, чтобы в чат попадал разбор,
    а не голый алерт. Текст — комментарий как есть ({EVENT.UPDATE.MESSAGE}), без шаблона типа оповещения.
    """
    name = f"incident-advisor: разбор → {team}"
    if call("action.get", {"filter": {"name": [name]}}):
        print(f"Действие «{name}» уже есть")
        return
    call("action.create", {
        "name": name, "eventsource": EVENT_SOURCE_TRIGGERS, "status": 0,
        "pause_suppressed": 0, "notify_if_canceled": 1,
        "filter": {"evaltype": 0, "conditions": [
            {"conditiontype": CONDITION_TYPE_EVENT_TAG, "operator": CONDITION_OPERATOR_EQUAL, "value": EVENT_TAG_NAME},
            {"conditiontype": CONDITION_TYPE_EVENT_TAG_VALUE, "operator": CONDITION_OPERATOR_EQUAL,
             "value": team, "value2": EVENT_TAG_NAME},
        ]},
        "update_operations": [{
            "operationtype": OPERATION_TYPE_MESSAGE,
            "opmessage_usr": [{"userid": userid}],
            "opmessage": {"default_msg": 0, "mediatypeid": mediatypeid,
                          "subject": "{EVENT.NAME}", "message": "{EVENT.UPDATE.MESSAGE}"},
        }],
    })
    print(f"Действие «{name}»: комментарий к проблеме → чат команды")


def ensure_telegram_notifications():
    mediatypeid = ensure_telegram_mediatype()
    roleid = call("role.get", {"filter": {"name": ["User role"]}, "output": ["roleid"]})[0]["roleid"]
    groupid = ensure_group("test-server")
    found = call("usergroup.get", {"filter": {"name": ["incident-advisor: команды"]}})
    if found:
        usrgrpid = found[0]["usrgrpid"]
    else:
        usrgrpid = call("usergroup.create", {
            "name": "incident-advisor: команды",
            "hostgroup_rights": [{"id": groupid, "permission": PERM_READ}],
        })["usrgrpids"][0]
        print("Группа пользователей «incident-advisor: команды» — чтение хостов стенда")
    chats = parse_chat_ids()
    for team in sorted(set(TEAMS.values())):
        userid = ensure_team_user(team, chats[team], mediatypeid, usrgrpid, roleid)
        ensure_notification_action(team, userid, mediatypeid)


def main():
    global token
    if RETENTION_TOKEN_FILE:
        try:
            with open(RETENTION_TOKEN_FILE, "x", encoding="ascii") as f:
                f.write(secrets.token_urlsafe(32))
        except FileExistsError:
            pass
    wait_for_api()
    token = call("user.login", {"username": USER, "password": PASSWORD})
    point_server_host_to_agent()
    issue_service_token()
    ensure_connector("incident-advisor: события", EVENTS, "/zabbix/connector/events")
    ensure_connector("incident-advisor: значения", ITEM_VALUES, "/zabbix/connector/values",
                     tags=[{"tag": "advisor", "operator": TAG_EXISTS, "value": ""}])
    ensure_module()
    ensure_telegram_notifications()
    if os.environ.get("STAND_HOSTS", "1") == "1":
        groupid = ensure_group("test-server")
        for service, deps in STAND.items():
            create_stand_host(service, deps, groupid)
    print("Готово: http://localhost:8070 (Admin / zabbix) → Monitoring → Incident advisor")


if __name__ == "__main__":
    main()
