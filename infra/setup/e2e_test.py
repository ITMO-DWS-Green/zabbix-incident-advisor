"""Сквозная проверка всей цепочки на поднятом compose (с профилем stand):

сбой на test-server → триггер Zabbix → коннектор → инцидент в сервисе → разбор →
комментарий к проблеме в Zabbix → разметка сбоя от test-server → отчёт о точности.
"""
import json
import os
import sys
import time
import urllib.request

ZABBIX = os.environ.get("ZABBIX_URL", "http://localhost:8070")
ADVISOR = os.environ.get("ADVISOR_URL", "http://localhost:8095")
STAND = os.environ.get("STAND_URL", "http://localhost:8090")
UI_TOKEN = os.environ.get("ADVISOR_UI_TOKEN", "dev-ui-token")
FAULT_SECONDS = 150
PREFIX = "[incident-advisor]"


def http(method, url, body=None, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=20) as resp:
        text = resp.read().decode()
        return json.loads(text) if text else None


def advisor(path):
    return http("GET", ADVISOR + path, headers={"X-Advisor-Token": UI_TOKEN})


def zabbix(method, params, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    body = {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}
    req = urllib.request.Request(ZABBIX + "/api_jsonrpc.php", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json-rpc", **headers})
    with urllib.request.urlopen(req, timeout=20) as resp:
        answer = json.load(resp)
    if "error" in answer:
        raise RuntimeError(answer["error"])
    return answer["result"]


def wait(what, check, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            result = check()
            if result:
                print(f"OK: {what}")
                return result
        except Exception as e:  # noqa: BLE001 — стенд ещё поднимается
            print(f"  ...{what}: {e}")
        time.sleep(10)
    sys.exit(f"НЕ ДОЖДАЛИСЬ: {what}")


def check_module_page():
    """Входим в веб-интерфейс как человек и открываем страницу модуля и его прокси."""
    import http.cookiejar
    import urllib.parse
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    form = urllib.parse.urlencode({"name": "Admin", "password": "zabbix", "enter": "Sign in"}).encode()
    opener.open(ZABBIX + "/index.php", form, timeout=20).read()
    page = opener.open(ZABBIX + "/zabbix.php?action=incidentadvisor.view", timeout=20).read().decode()
    assert 'id="incident-advisor"' in page, "страница модуля не отрисовалась"
    assert "incident-advisor.js" in page, "JS модуля не подключился"
    proxied = json.loads(opener.open(
        ZABBIX + "/zabbix.php?action=incidentadvisor.api.get&path=/api/info", timeout=20).read())
    assert proxied.get("zabbixVersion"), f"прокси модуля не дошёл до сервиса: {proxied}"
    return True


def main():
    info = wait("сервис видит Zabbix", lambda: (i := advisor("/api/info")).get("zabbixVersion") and i, 300)
    print(f"  {info}")
    wait("страница модуля и прокси в сервис работают", check_module_page, 120)
    wait("стенд отвечает", lambda: http("GET", STAND + "/chaos/status")["stats"]["requests"] > 0, 300)

    started = time.time()
    http("POST", STAND + "/chaos/scenario?target=database",
         {"type": "ERRORS_5XX", "rate": 1.0, "durationSeconds": FAULT_SECONDS, "scenarioId": "ci-e2e"})
    print("Включён сбой: database отвечает 500 на все запросы")

    def analyzed():
        done = [i for i in advisor("/api/incidents") if i["analysisStatus"] == "DONE"
                and i["startedAt"] and "5xx" in i["triggerName"]]
        return done[0] if done else None

    incident = wait("инцидент разобран", analyzed, 480)
    print(f"  {incident['triggerName']}: источник {incident['topSuspect']}; {incident['summary']}")

    token = zabbix("user.login", {"username": "Admin", "password": "zabbix"})

    def commented():
        events = zabbix("event.get", {"eventids": [str(incident["problemEventId"])],
                                      "select_acknowledges": "extend"}, token)
        return any(PREFIX in a["message"] for e in events for a in e["acknowledges"])

    wait("разбор записан комментарием к проблеме в Zabbix", commented, 120)

    remaining = FAULT_SECONDS - (time.time() - started) + 20
    if remaining > 0:
        time.sleep(remaining)
    report = wait("разметка сбоя пришла и учтена в отчёте",
                  lambda: (r := advisor("/api/evaluation?scenarioId=ci-e2e"))["faults"] >= 1 and r, 180)
    print(f"  отчёт: {report}")
    print("ВСЯ ЦЕПОЧКА РАБОТАЕТ")


if __name__ == "__main__":
    main()
