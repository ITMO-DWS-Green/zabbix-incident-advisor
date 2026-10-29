"""Однократная настройка Zabbix для разработки расширения. Только стандартная библиотека Python.

1. Агент мониторит сам Zabbix server (интерфейс → контейнер zabbix-agent2).
2. Токен API для сервиса → файл /secrets/zabbix-api-token (сервис читает его при каждом вызове).
3. Коннекторы: события → сервис; значения элементов с тегом advisor → сервис.
4. Модуль интерфейса incident-advisor зарегистрирован и включён.
5. Хосты стенда test-server с тегом depends_on (карта связей), метриками и триггерами.

Повторный запуск безопасен: существующее не дублируется, токен пересоздаётся.
"""
import json
import os
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
STAND_URL = os.environ.get("STAND_URL_PATTERN", "http://{service}:8080")

# Стенд test-server: кто кого вызывает (совпадает с compose.yaml репозитория test-server).
STAND = {
    "gateway": ["orders"],
    "orders": ["payments", "database"],
    "payments": ["database"],
    "database": [],
}

HTTP_AGENT, DEPENDENT = 19, 18
FLOAT, CHAR, TEXT = 0, 1, 4
JSONPATH, JAVASCRIPT = 12, 21
BEARER = 5
EVENTS, ITEM_VALUES = 1, 0
TAG_EXISTS = 12

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


def create_stand_host(service, deps, groupid):
    if call("host.get", {"filter": {"host": [service]}}):
        print(f"Хост {service} уже есть")
        return
    tags = [{"tag": "stand", "value": "test-server"}]
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
    print(f"Хост {service} → {base}, зависит от: {', '.join(deps) or '—'}")


def main():
    global token
    wait_for_api()
    token = call("user.login", {"username": USER, "password": PASSWORD})
    point_server_host_to_agent()
    issue_service_token()
    ensure_connector("incident-advisor: события", EVENTS, "/zabbix/connector/events")
    ensure_connector("incident-advisor: значения", ITEM_VALUES, "/zabbix/connector/values",
                     tags=[{"tag": "advisor", "operator": TAG_EXISTS, "value": ""}])
    ensure_module()
    if os.environ.get("STAND_HOSTS", "1") == "1":
        groupid = ensure_group("test-server")
        for service, deps in STAND.items():
            create_stand_host(service, deps, groupid)
    print("Готово: http://localhost:8070 (Admin / zabbix) → Monitoring → Incident advisor")


if __name__ == "__main__":
    main()
