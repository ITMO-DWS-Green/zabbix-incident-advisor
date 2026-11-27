#!/usr/bin/env python3
"""Run a test-server playbook only after explicit human exclusive-access confirmation.

Default is network-free dry run. See docs/playbook-evaluation.md for limitations.
"""
import argparse
import datetime as dt
import decimal
import email.utils
import ipaddress
import json
import math
import os
import re
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PLAYBOOKS = {
    "cascade-database": (1, 180),
    "two-incidents": (2, 240),
    "payments-errors-then-crash": (2, 240),
    "all-fault-types": (7, 840),
}
WARNING = ("Human exclusive access is required for the entire run; GET/current and POST are not atomic. "
           "This tool does not acquire a lock. POST/run stops any previous run.")
LIMIT = 2 * 1024 * 1024
UTC = dt.timezone.utc
# Operational assumption, not a measurement: all event clocks must be within
# this much of the local UTC clock throughout the run and observation tail.
MAX_CLOCK_SKEW = dt.timedelta(seconds=5)


class Failure(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Transport:
    def __init__(self, timeout):
        self.timeout = timeout
        # No environment proxy: an unintended proxy must not receive the advisor token.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                  urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def request(self, method, url, body=None, token=None, timeout=None):
        request_started = time.monotonic()
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token is not None:
            headers["X-Advisor-Token"] = token
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        timeout = self.timeout if timeout is None else min(self.timeout, timeout)
        # Socket timeouts are per operation, not total deadlines (slow headers/body can
        # keep resetting them). A daemon worker bounds the caller's entire request.
        done = threading.Event()
        result = []

        def perform():
            try:
                try:
                    response = self.opener.open(req, timeout=timeout)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    raw = response.read(LIMIT + 1)
                    if len(raw) > LIMIT:
                        raise Failure("response_too_large", "HTTP response exceeds the size limit")
                    result.append((response.status, raw, response.headers.get("Date")))
            except Failure as exc:
                result.append(exc)
            except Exception:
                result.append(Failure("transport_error", "HTTP request failed; details suppressed"))
            finally:
                done.set()

        remaining = timeout - (time.monotonic() - request_started)
        if remaining <= 0:
            raise Failure("transport_error", "HTTP request timed out; outcome may be uncertain")
        threading.Thread(target=perform, daemon=True).start()
        remaining = timeout - (time.monotonic() - request_started)
        if remaining <= 0 or not done.wait(remaining):
            raise Failure("transport_error", "HTTP request timed out; outcome may be uncertain")
        if isinstance(result[0], Failure):
            raise result[0]
        return result[0]


class Clock:
    def now(self):
        return dt.datetime.now(UTC)

    def monotonic(self):
        return time.monotonic()

    def sleep(self, seconds):
        time.sleep(seconds)


def base_url(value):
    if not isinstance(value, str) or not value or value != value.strip() or any(ord(c) < 33 or ord(c) == 127 for c in value):
        raise Failure("invalid_url", "Invalid base URL")
    try:
        p = urllib.parse.urlsplit(value)
        port = p.port
        host = p.hostname
    except ValueError:
        raise Failure("invalid_url", "Invalid base URL") from None
    if (p.scheme not in ("http", "https") or not host or p.username is not None or p.password is not None
            or p.path not in ("", "/") or p.query or p.fragment or "\\" in value
            or (port is not None and not 1 <= port <= 65535)):
        raise Failure("invalid_url", "Use an HTTP(S) origin without credentials, path, query or fragment")
    if p.scheme == "http":
        try:
            local = ipaddress.ip_address(host).is_loopback
        except ValueError:
            local = host.lower() == "localhost"
        if not local:
            raise Failure("invalid_url", "Plain HTTP is allowed only for localhost or loopback addresses")
    return value.rstrip("/")


def json_body(raw):
    try:
        def unique(pairs):
            obj = {}
            for key, value in pairs:
                if key in obj:
                    raise ValueError("duplicate key")
                obj[key] = value
            return obj

        def finite_decimal(value):
            number = decimal.Decimal(value)
            if not number.is_finite():
                raise ValueError("non-finite")
            return number

        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique, parse_float=finite_decimal,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite")))
    except (UnicodeError, ValueError, TypeError, RecursionError, decimal.InvalidOperation):
        raise Failure("invalid_response", "Invalid JSON response") from None


def output_json(value):
    """Serialize parsed JSON decimals exactly without converting them through float."""
    if isinstance(value, decimal.Decimal):
        if not value.is_finite():
            raise ValueError("Non-finite JSON number")
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(key, ensure_ascii=False) + ":" + output_json(item)
                              for key, item in sorted(value.items())) + "}"
    if isinstance(value, list):
        return "[" + ",".join(output_json(item) for item in value) + "]"
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def instant(value, *, decimal_seconds=False):
    """Return a UTC microsecond floor and the remaining 0..999 nanoseconds."""
    if decimal_seconds and isinstance(value, decimal.Decimal):
        # Jackson's fractional Instant timestamps are epoch seconds; integral
        # JSON timestamps are ambiguous with milliseconds and are rejected.
        try:
            if not value.is_finite() or value < 0 or value > 4102444800:
                raise ValueError()
            sign, digits, exponent = value.as_tuple()
            if len(digits) > 32 or exponent < -9 or exponent > 10:
                raise ValueError()
            coefficient = 0
            for digit in digits:
                coefficient = coefficient * 10 + digit
            nanos = coefficient * 10 ** (exponent + 9)
            micros, remainder = divmod(nanos, 1000)
            return dt.datetime(1970, 1, 1, tzinfo=UTC) + dt.timedelta(microseconds=micros), remainder
        except (ValueError, OverflowError):
            raise Failure("invalid_response", "Invalid decimal timestamp: expected epoch seconds with at most 9 fractional digits") from None
    if not isinstance(value, str):
        message = ("Ambiguous or invalid numeric timestamp; expected ISO-8601 or decimal epoch seconds"
                   if decimal_seconds else "Invalid timestamp; expected ISO-8601")
        raise Failure("invalid_response", message)
    match = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)", value)
    if not match:
        raise Failure("invalid_response", "Invalid timestamp")
    try:
        fraction = (match.group(2) or "").ljust(9, "0")
        parsed = dt.datetime.fromisoformat(match.group(1) + "." + fraction[:6] + match.group(3).replace("Z", "+00:00"))
        return parsed.astimezone(UTC), int(fraction[6:])
    except (ValueError, OverflowError):
        raise Failure("invalid_response", "Invalid timestamp") from None


def iso(value):
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def iso_instant(value):
    floor, nanos = value
    return iso(floor) if not nanos else iso(floor)[:-1] + f"{nanos:03d}Z"


def current(status, raw):
    if status == 204:
        if raw:
            raise Failure("invalid_current", "204 current response has a body")
        return None
    if status != 200:
        raise Failure("invalid_current", "Current endpoint did not return 200 or 204")
    obj = json_body(raw)
    if not isinstance(obj, dict):
        raise Failure("invalid_current", "Current response is not an object")
    if (not isinstance(obj.get("playbook"), str) or not obj["playbook"].strip()
            or not isinstance(obj.get("scenarioId"), str) or not obj["scenarioId"].strip()
            or len(obj["scenarioId"]) > 512 or type(obj.get("running")) is not bool):
        raise Failure("invalid_current", "Current response lacks valid identity or running state")
    instant(obj.get("startedAt"))
    for key in ("stepsDone", "stepsTotal", "elapsedSeconds", "totalSeconds"):
        if type(obj.get(key)) is not int or obj[key] < 0:
            raise Failure("invalid_current", "Current response has invalid progress")
    if obj["stepsTotal"] < 1 or obj["totalSeconds"] < 1 or obj["stepsDone"] > obj["stepsTotal"]:
        raise Failure("invalid_current", "Current response has inconsistent progress")
    return obj


def check_run(view, identity, duration):
    if (view is None or view["playbook"] != identity["playbook"]
            or view["scenarioId"] != identity["scenarioId"]
            or instant(view["startedAt"]) != instant(identity["startedAt"])
            or view["stepsTotal"] != identity["stepsTotal"] or view["totalSeconds"] != duration):
        raise Failure("identity_changed", "Current run identity or declared duration changed")
    return view


def evaluation(raw, scenario, expected):
    obj = json_body(raw) if isinstance(raw, bytes) else raw
    if not isinstance(obj, dict) or type(obj.get("faults")) is not int or not isinstance(obj.get("rows"), list):
        raise Failure("invalid_evaluation", "Evaluation response has invalid shape")
    rows = obj["rows"]
    if obj["faults"] != len(rows):
        raise Failure("invalid_evaluation", "Evaluation count disagrees with rows")
    identities = {}
    warnings = []
    for row in rows:
        if (not isinstance(row, dict) or row.get("scenarioId") != scenario
                or any(not isinstance(row.get(field), str) or not row[field].strip()
                       for field in ("faultId", "type", "expectedSource"))):
            raise Failure("invalid_evaluation", "Evaluation row identity is invalid")
        if row["faultId"] in identities:
            raise Failure("duplicate_labels", "Duplicate faultId in evaluation rows")
        identities[row["faultId"]] = (row["scenarioId"], row["faultId"], row["type"], row["expectedSource"])
        status = row.get("matchStatus")
        if status not in ("MATCHED", "NO_INCIDENT", "NO_ANALYSIS", "AMBIGUOUS"):
            raise Failure("invalid_evaluation", "Unknown evaluation match status")
        candidates = row.get("candidates")
        if not isinstance(candidates, list):
            raise Failure("invalid_evaluation", "Evaluation candidates are missing")
        if status != "MATCHED":
            warnings.append({"faultId": row["faultId"], "matchStatus": status})
        for candidate in candidates:
            if (not isinstance(candidate, dict) or not isinstance(candidate.get("incidentId"), str)
                    or not candidate["incidentId"].strip() or type(candidate.get("analyzed")) is not bool
                    or type(candidate.get("ambiguous")) is not bool):
                raise Failure("invalid_evaluation", "Invalid evaluation candidate")
            if not candidate["analyzed"] or candidate["ambiguous"]:
                warnings.append({"faultId": row["faultId"], "incidentId": candidate["incidentId"],
                                 "analyzed": candidate["analyzed"], "ambiguous": candidate["ambiguous"]})
        if status == "MATCHED":
            selected = row.get("incidentId")
            matches = [candidate for candidate in candidates if candidate["incidentId"] == selected]
            if (not isinstance(selected, str) or not selected.strip() or len(matches) != 1
                    or not matches[0]["analyzed"] or matches[0]["ambiguous"]):
                warnings.append({"faultId": row["faultId"], "matchStatus": status,
                                 "issue": "invalid_selected_candidate"})
    if len(identities) > expected:
        raise Failure("extra_labels", "More labels than expected for the scenario")
    return identities, warnings


def response(transport, method, url, body=None, token=None, *, clock=None, deadline=None, timeout_code=None):
    timeout = None
    if deadline is not None:
        timeout = deadline - clock.monotonic()
        if timeout <= 0:
            raise Failure(timeout_code, "Polling deadline expired")
    try:
        result = transport.request(method, url, body, token, timeout=timeout)
    except Failure:
        if deadline is not None and clock.monotonic() >= deadline:
            raise Failure(timeout_code, "Polling deadline expired") from None
        raise
    if deadline is not None and clock.monotonic() >= deadline:
        raise Failure(timeout_code, "Polling deadline expired")
    if not isinstance(result, tuple) or len(result) != 3:
        raise Failure("invalid_response", "Invalid HTTP transport response")
    status, raw, server_date = result
    if type(status) is not int or not isinstance(raw, bytes):
        raise Failure("invalid_response", "Invalid HTTP transport response")
    if len(raw) > LIMIT:
        raise Failure("response_too_large", "HTTP response exceeds the size limit")
    if 300 <= status < 400:
        raise Failure("redirect", "HTTP redirects are forbidden")
    return status, raw, server_date


def http_date(value):
    try:
        if not isinstance(value, str) or len(value) > 128:
            raise ValueError()
        parsed = email.utils.parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            raise ValueError()
        return parsed.astimezone(UTC)
    except (ValueError, TypeError, OverflowError):
        raise Failure("collision_risk", "Server Date is unavailable or invalid; cannot exclude scenarioId collision") from None


def poll_delay(clock, deadline, interval):
    remaining = deadline - clock.monotonic()
    if remaining <= 0:
        return False
    clock.sleep(min(interval, remaining))
    return True


def run(args, transport, clock, token):
    expected, duration = PLAYBOOKS[args.playbook]
    report = {"status": "incomplete", "playbook": args.playbook, "expectedLabels": expected,
              "durationSeconds": duration, "warnings": [WARNING,
              "Run completion does not prove steps succeeded; evaluation is not an automatic success verdict.",
              "Snapshot is a time-window approximation across all scenarios, not attribution to this run."]}
    try:
        stand = base_url(args.stand_url)
        advisor = base_url(args.advisor_url)
        if not args.execute:
            report.update(status="dry_run", message="No network requests were made")
            return report
        if not token or not isinstance(token, str) or not token.strip() or any(ord(c) < 32 or ord(c) > 126 for c in token):
            raise Failure("missing_token", "Set a valid ADVISOR_UI_TOKEN environment variable")
        status, raw, server_date = response(transport, "GET", stand + "/chaos/playbooks/current")
        old = current(status, raw)
        server_start_floor = None
        if old and old["running"]:
            raise Failure("active_run", "Another playbook is running; no POST sent")
        # Date must advance beyond the previous start, independently of the client clock.
        # This relies on Date and scenario IDs sharing the server clock; proxies must preserve it.
        if old:
            deadline = clock.monotonic() + args.collision_wait
            old_time = instant(old["startedAt"])
            while http_date(server_date) < old_time[0].replace(microsecond=0) + dt.timedelta(seconds=2):
                if not poll_delay(clock, deadline, args.poll_interval):
                    raise Failure("collision_risk", "Cannot exclude a same-second scenarioId collision")
                status, raw, server_date = response(transport, "GET", stand + "/chaos/playbooks/current",
                                                    clock=clock, deadline=deadline, timeout_code="collision_risk")
                again = current(status, raw)
                if (again is None or any(again[k] != old[k] for k in ("playbook", "scenarioId", "stepsTotal", "totalSeconds"))
                        or instant(again["startedAt"]) != old_time or again["running"]):
                    raise Failure("current_changed", "Current run changed before POST")
            server_start_floor = http_date(server_date)
        started_local = clock.now()
        started_monotonic = clock.monotonic()
        try:
            status, raw, server_date = response(transport, "POST", stand + "/chaos/playbooks/" + args.playbook + "/run")
        except Failure as exc:
            raise Failure("post_uncertain", "POST outcome is uncertain; do not retry or stop automatically") from exc
        if status >= 500:
            raise Failure("post_uncertain", "POST returned 5xx; outcome is uncertain; do not retry or stop automatically")
        if status not in (200, 201, 202):
            raise Failure("post_rejected", "POST was not accepted; verify the stand manually")
        try:
            view = current(200, raw)
            if (view["playbook"] != args.playbook or view["totalSeconds"] != duration
                    or (old is not None and view["scenarioId"] == old["scenarioId"])):
                raise Failure("identity_changed", "POST response has unexpected playbook, duration or scenario collision")
            started_server = instant(view["startedAt"])
            expected_scenario = args.playbook + "-" + started_server[0].strftime("%Y%m%d-%H%M%S")
            if view["scenarioId"] != expected_scenario:
                raise Failure("identity_changed", "POST scenarioId does not match its server UTC start time")
            if not ((started_local - MAX_CLOCK_SKEW, 0) <= started_server
                    <= (started_local + MAX_CLOCK_SKEW, 0)):
                raise Failure("identity_changed", "POST start time differs too much from the local clock")
            if server_start_floor is not None and started_server < (server_start_floor, 0):
                raise Failure("identity_changed", "POST start time precedes the server Date used for collision protection")
        except Failure as exc:
            raise Failure("post_uncertain", "POST was accepted but its run identity is invalid; inspect manually") from exc
        identity = view
        report["scenarioId"] = view["scenarioId"]
        report["startedAt"] = iso_instant(started_server)
        deadline = started_monotonic + duration + args.run_grace
        while True:
            status, raw, server_date = response(transport, "GET", stand + "/chaos/playbooks/current",
                                                clock=clock, deadline=deadline, timeout_code="run_timeout")
            view = check_run(current(status, raw), identity, duration)
            if not view["running"]:
                if (view["stepsDone"] != view["stepsTotal"] or view["elapsedSeconds"] + 2 < view["totalSeconds"]
                        or clock.monotonic() - started_monotonic + 2 < duration
                        or (clock.now() - started_local).total_seconds() + 2 < duration):
                    raise Failure("run_incomplete", "Run stopped before all steps or before its expected duration")
                break
            if not poll_delay(clock, deadline, args.poll_interval):
                raise Failure("run_timeout", "Playbook is still running at the polling deadline")
        if args.tail_seconds:
            clock.sleep(args.tail_seconds)
        deadline = clock.monotonic() + args.labels_timeout
        path = "/api/evaluation?" + urllib.parse.urlencode({"scenarioId": identity["scenarioId"]})
        while True:
            status, raw, server_date = response(transport, "GET", advisor + path, token=token,
                                                clock=clock, deadline=deadline, timeout_code="labels_timeout")
            if status != 200:
                raise Failure("evaluation_error", "Evaluation request did not return 200")
            preliminary_labels, issues = evaluation(raw, identity["scenarioId"], expected)
            report["labelsFound"] = len(preliminary_labels)
            report["analysisIssues"] = issues
            if len(preliminary_labels) == expected:
                break
            if not poll_delay(clock, deadline, args.poll_interval):
                raise Failure("labels_timeout", "Expected labels did not arrive before the deadline")
        # Recheck identity after the observation tail and label wait.
        status, raw, server_date = response(transport, "GET", stand + "/chaos/playbooks/current")
        final = check_run(current(status, raw), identity, duration)
        if final["running"] or final["stepsDone"] != final["stepsTotal"] or final["elapsedSeconds"] + 2 < duration:
            raise Failure("run_incomplete", "Final run progress is incomplete")
        # Floor the exact start to microseconds and pad both sides for clock skew.
        # The half-open upper bound must strictly include the padded boundary.
        start = instant(identity["startedAt"])[0] - MAX_CLOCK_SKEW
        end = clock.now() + MAX_CLOCK_SKEW + dt.timedelta(microseconds=1)
        if not (dt.datetime(1970, 1, 1, tzinfo=UTC) <= start < end <= dt.datetime(2100, 1, 1, tzinfo=UTC)
                and end - start <= dt.timedelta(days=31)):
            raise Failure("invalid_window", "Snapshot window is outside API limits")
        window = {"from": iso(start), "to": iso(end)}
        report["snapshotWindow"] = window
        try:
            status, raw, server_date = response(transport, "POST", advisor + "/api/evaluation/runs", body=window, token=token)
        except Failure as exc:
            if exc.code == "response_too_large":
                warning = "Snapshot response exceeded the 2 MiB limit; the snapshot may have been created. Do not retry automatically; inspect GET /api/evaluation/runs manually using snapshotWindow and fetch the matching snapshot by ID before deciding on recovery"
                report["warnings"].append(warning)
                raise Failure("snapshot_uncertain", warning) from exc
            raise Failure("snapshot_uncertain", "Snapshot POST outcome is uncertain; do not retry automatically") from exc
        if status >= 500:
            raise Failure("snapshot_uncertain", "Snapshot POST returned 5xx; outcome is uncertain")
        if status != 201:
            raise Failure("snapshot_error", "Snapshot POST did not return 201")
        try:
            snap = json_body(raw)
            if (not isinstance(snap, dict) or snap.get("scope") != "window_approximation"
                    or type(snap.get("schemaVersion")) is not int or snap["schemaVersion"] != 1
                    or snap.get("methodologyVersion") != "time-overlap-v1"):
                raise ValueError()
            snap_id = str(uuid.UUID(snap["id"]))
            if (instant(snap["from"], decimal_seconds=True) != (start, 0)
                    or instant(snap["to"], decimal_seconds=True) != (end, 0)):
                raise ValueError()
            labels, incidents, snap_report = snap["labels"], snap["incidents"], snap["report"]
            if not isinstance(labels, list) or not isinstance(incidents, list) or not isinstance(snap_report, dict):
                raise ValueError()
            # Every saved label must have exactly one corresponding report row.
            # A faultId is scoped by scenario; other scenarios can reuse it.
            saved_labels = {}
            for label in labels:
                if (not isinstance(label, dict) or any(
                        not isinstance(label.get(field), str) or not label[field].strip()
                        for field in ("scenarioId", "faultId", "type", "source"))):
                    raise ValueError()
                key = (label["scenarioId"], label["faultId"])
                if key in saved_labels:
                    raise ValueError()
                saved_labels[key] = (label["type"], label["source"])
            snapshot_labels = {(scenario, fault, *details)
                               for (scenario, fault), details in saved_labels.items()
                               if scenario == identity["scenarioId"]}
            if snapshot_labels != set(preliminary_labels.values()):
                raise Failure("label_identity_changed", "Snapshot labels differ from preliminary evaluation in scenarioId, faultId, type or source")
            snapshot_issues = []
            # Snapshot includes all scenarios. Validate its rows and inspect every candidate separately.
            rows = snap_report.get("rows")
            if not isinstance(rows, list) or type(snap_report.get("faults")) is not int or snap_report["faults"] != len(rows):
                raise ValueError()
            incident_ids = set()
            incident_issues = []
            for incident in incidents:
                if (not isinstance(incident, dict) or not isinstance(incident.get("id"), str)
                        or not incident["id"].strip() or incident["id"] in incident_ids
                        or incident.get("analysisStatus") not in ("PENDING", "RUNNING", "DONE", "FAILED")
                        or type(incident.get("analysisPresent")) is not bool):
                    raise ValueError()
                incident_ids.add(incident["id"])
                if incident["analysisStatus"] != "DONE" or not incident["analysisPresent"]:
                    incident_issues.append({"incidentId": incident["id"], "analysisStatus": incident["analysisStatus"], "analysisPresent": incident["analysisPresent"]})
            row_ids = set()
            for r in rows:
                if (not isinstance(r, dict) or any(
                        not isinstance(r.get(field), str) or not r[field].strip()
                        for field in ("scenarioId", "faultId", "type", "expectedSource"))):
                    raise ValueError()
                key = (r["scenarioId"], r["faultId"])
                if key in row_ids or saved_labels.get(key) != (r["type"], r["expectedSource"]):
                    raise ValueError()
                row_ids.add(key)
                _, row_issues = evaluation({"faults": 1, "rows": [r]}, r["scenarioId"], 1)
                if any(candidate["incidentId"] not in incident_ids for candidate in r["candidates"]):
                    raise ValueError()
                if r["matchStatus"] == "MATCHED":
                    selected = r.get("incidentId")
                    if not isinstance(selected, str) or selected not in incident_ids:
                        raise ValueError()
                snapshot_issues.extend(row_issues)
            if row_ids != set(saved_labels):
                raise ValueError()
        except Failure as exc:
            raise Failure("snapshot_invalid", "Snapshot was created but its contents are invalid (" + exc.message + "); inspect manually; do not retry automatically") from None
        except (ValueError, TypeError, KeyError, AttributeError):
            raise Failure("snapshot_invalid", "Snapshot was created but its contents are incomplete or invalid; inspect manually; do not retry automatically") from None
        report["snapshotId"] = snap_id
        # The complete snapshot remains available by ID; this is its report over the
        # entire observation window, not a report attributed solely to this playbook.
        report["snapshotReport"] = snap_report
        report["snapshotAnalysisIssues"] = snapshot_issues
        report["snapshotIncidentIssues"] = incident_issues
        report["status"] = "incomplete" if issues or snapshot_issues or incident_issues else "observed"
        report["message"] = "Observation captured; manual review required, not a success verdict"
    except Failure as exc:
        report["reason"] = exc.code
        report["message"] = exc.message
    # A hostile or misconfigured server could echo the token in any returned field.
    # Redact recursively before serialization, including identifiers and warning details.
    if token:
        def redact(value):
            if isinstance(value, str):
                return value.replace(token, "[REDACTED]")
            if isinstance(value, list):
                return [redact(item) for item in value]
            if isinstance(value, dict):
                return {key: redact(item) for key, item in value.items()}
            return value
        report = redact(report)
    return report


def positive(value):
    try:
        number = float(value)
        if not math.isfinite(number) or number <= 0 or number > 86400:
            raise ValueError()
        return number
    except ValueError:
        raise argparse.ArgumentTypeError("must be a finite number > 0 and <= 86400") from None


def nonnegative(value):
    try:
        number = float(value)
        if not math.isfinite(number) or number < 0 or number > 86400:
            raise ValueError()
        return number
    except ValueError:
        raise argparse.ArgumentTypeError("must be a finite number from 0 to 86400") from None


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Failure("arguments", "Invalid command line arguments")


def main(argv=None):
    parser = Parser(description=__doc__, add_help=False)
    parser.add_argument("playbook", choices=PLAYBOOKS, nargs="?")
    parser.add_argument("-h", "--help", action="store_true")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--stand-url", default="http://localhost:8090")
    parser.add_argument("--advisor-url", default="http://localhost:8095")
    parser.add_argument("--poll-interval", type=positive, default=5)
    parser.add_argument("--http-timeout", type=positive, default=10)
    parser.add_argument("--run-grace", type=nonnegative, default=120)
    parser.add_argument("--labels-timeout", type=nonnegative, default=180)
    parser.add_argument("--tail-seconds", type=nonnegative, default=120)
    parser.add_argument("--collision-wait", type=nonnegative, default=5)
    try:
        args = parser.parse_args(argv)
        if args.help:
            print(json.dumps({"status": "help", "usage": parser.format_help(), "warning": WARNING}, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
            return 0
        if args.playbook is None:
            raise Failure("arguments", "A playbook name is required")
        base_url(args.stand_url)
        base_url(args.advisor_url)
        if args.execute:
            if not sys.stdin.isatty() or not sys.stderr.isatty():
                raise Failure("tty_required", "Execution requires an interactive terminal")
            print(WARNING, file=sys.stderr)
            print("Type the exact test-server origin, then playbook name, then EXCLUSIVE ACCESS CONFIRMED.", file=sys.stderr)
            def ask(prompt):
                print(prompt, end="", file=sys.stderr, flush=True)
                answer = sys.stdin.readline()
                if not answer:
                    raise EOFError()
                return answer.rstrip("\r\n")
            address = ask("Test-server origin: ")
            name = ask("Playbook: ")
            consent = ask("Confirmation: ")
            if address != args.stand_url or name != args.playbook or consent != "EXCLUSIVE ACCESS CONFIRMED":
                raise Failure("confirmation_failed", "Interactive confirmation did not match; no network requests made")
        result = run(args, Transport(args.http_timeout) if args.execute else None, Clock(), os.environ.get("ADVISOR_UI_TOKEN"))
    except (EOFError, KeyboardInterrupt):
        result = {"status": "incomplete", "reason": "interrupted", "message": "Interrupted; inspect the stand manually; no automatic stop"}
    except Failure as exc:
        result = {"status": "incomplete", "reason": exc.code, "message": exc.message}
    print(output_json(result))
    return 0 if result["status"] in ("dry_run", "observed") else 1


if __name__ == "__main__":
    sys.exit(main())
