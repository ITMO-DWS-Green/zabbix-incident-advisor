import contextlib
import datetime as dt
import decimal
import io
import json
import types
import unittest
from unittest import mock

import playbook_runner as r

START = dt.datetime(2026, 1, 1, tzinfo=dt.timezone.utc)
DATE = "Thu, 01 Jan 2026 00:00:00 GMT"
SCENARIO = "cascade-database-20260101-000000"
INCIDENT = "8ed7a811-8c74-49bd-b64e-4419b51f2b99"


def view(**changes):
    result = dict(playbook="cascade-database", scenarioId=SCENARIO, startedAt="2026-01-01T00:00:00Z",
                  running=True, stepsDone=0, stepsTotal=1, elapsedSeconds=0, totalSeconds=180)
    result.update(changes)
    return result


def row(fault="f1", scenario=SCENARIO, status="MATCHED", candidates=None):
    if candidates is None:
        candidates = [dict(incidentId=INCIDENT, analyzed=True, ambiguous=False)] if status == "MATCHED" else []
    return dict(faultId=fault, scenarioId=scenario, type="LATENCY", expectedSource="db",
                matchStatus=status, candidates=candidates, incidentId=INCIDENT if status == "MATCHED" else None)


def label(fault="f1", scenario=SCENARIO):
    return dict(scenarioId=scenario, faultId=fault, type="LATENCY", source="db")


def reply(status, value=None, date=DATE):
    return status, b"" if value is None else json.dumps(value).encode(), date


def snap(window=None, rows=None, incidents=None, labels=None):
    return dict(id="12345678-1234-5678-1234-567812345678", scope="window_approximation",
                schemaVersion=1, methodologyVersion="time-overlap-v1",
                **(window or {"from": "2026-01-01T00:00:00.000000Z", "to": "2026-01-01T00:03:01.000001Z"}),
                labels=labels if labels is not None else [label()],
                incidents=incidents if incidents is not None else [dict(id=INCIDENT, startedAt="2026-01-01T00:00:00Z",
                    analysisStatus="DONE", analysisPresent=True, completedAt="2026-01-01T00:00:10Z",
                    rankedSources=["db"], problems=[])],
                report=dict(faults=len(rows if rows is not None else [row()]), rows=rows if rows is not None else [row()]))


class FakeClock:
    def __init__(self):
        self.seconds = 0
    def now(self):
        return START + dt.timedelta(seconds=self.seconds)
    def monotonic(self):
        return self.seconds
    def sleep(self, seconds):
        self.seconds += seconds


class FakeTransport:
    def __init__(self, responses, clock=None):
        self.responses = list(responses)
        self.calls = []
        self.timeouts = []
        self.clock = clock
    def request(self, method, url, body=None, token=None, timeout=None):
        self.calls.append((method, url, body, token))
        self.timeouts.append(timeout)
        if not self.responses:
            raise AssertionError("Unexpected request")
        value = self.responses.pop(0)
        if isinstance(value, Exception):
            raise value
        if callable(value):
            return value(method, url, body, token)
        if self.clock is not None and value[0] == 200 and value[1]:
            data = json.loads(value[1])
            if isinstance(data, dict) and data.get("scenarioId") == SCENARIO and data.get("running") is False:
                self.clock.sleep(max(0, 180 - self.clock.monotonic()))
        return value


def args(**changes):
    values = dict(playbook="cascade-database", execute=True, stand_url="http://localhost:8090",
                  advisor_url="http://localhost:8095", poll_interval=1, http_timeout=10,
                  run_grace=1, labels_timeout=2, tail_seconds=0, collision_wait=2)
    values.update(changes)
    return types.SimpleNamespace(**values)


def complete():
    return view(running=False, stepsDone=1, elapsedSeconds=180)


def snapshot_reply(method, url, body, token):
    return reply(201, snap(window=body))


def run_with(responses, opts=None):
    clock = FakeClock()
    transport = FakeTransport(responses, clock)
    result = r.run(opts or args(), transport, clock, "secret-token")
    for method, url, body, token in transport.calls:
        if "/chaos/" in url:
            assert token is None
        assert not url.endswith("/stop")
    assert "secret-token" not in json.dumps(result)
    return result, transport, clock


def successful_prefix(old=None):
    return [reply(204) if old is None else reply(200, old), reply(200, view()), reply(200, complete()),
            reply(200, {"faults": 1, "rows": [row()]}), reply(200, complete())]


class RunnerTests(unittest.TestCase):
    def test_dry_run_does_not_use_transport(self):
        result = r.run(args(execute=False), None, FakeClock(), None)
        self.assertEqual(result["status"], "dry_run")

    def test_successful_observation_is_not_success_verdict(self):
        result, tr, _ = run_with(successful_prefix() + [snapshot_reply])
        self.assertEqual(result["status"], "observed")
        self.assertIn("not a success verdict", result["message"])
        self.assertEqual(result["snapshotReport"]["faults"], 1)
        self.assertEqual(result["snapshotReport"]["rows"][0]["faultId"], "f1")
        self.assertEqual(sum(c[0] == "POST" and "/chaos/" in c[1] for c in tr.calls), 1)
        self.assertEqual(tr.calls[-1][2]["from"], "2025-12-31T23:59:55.000000Z")
        self.assertEqual(tr.calls[-1][3], "secret-token")

    def test_fractional_snapshot_report_is_json_serializable_without_losing_precision(self):
        def snapshot_with_fractional_report(method, url, body, token):
            value = snap(window=body)
            value["report"]["meanTimeToAnswer"] = "FRACTIONAL_DURATION"
            data = json.dumps(value).encode().replace(b'"FRACTIONAL_DURATION"', b'123.123456789')
            return 201, data, DATE

        clock = FakeClock()
        transport = FakeTransport(successful_prefix() + [snapshot_with_fractional_report], clock)
        result = r.run(args(), transport, clock, "secret-token")
        self.assertEqual(result["status"], "observed")
        encoded = r.output_json(result)
        self.assertIn('"meanTimeToAnswer":123.123456789', encoded)
        self.assertEqual(json.loads(encoded, parse_float=decimal.Decimal)["snapshotReport"]["meanTimeToAnswer"],
                         decimal.Decimal("123.123456789"))

    def test_snapshot_report_rejects_decimal_exponents_outside_decimal_range(self):
        def snapshot_with_huge_exponent(method, url, body, token):
            value = snap(window=body)
            value["report"]["meanTimeToAnswer"] = "HUGE_NUMBER"
            return 201, json.dumps(value).encode().replace(b'"HUGE_NUMBER"', b'1e99999999999999999999'), DATE

        result, _, _ = run_with(successful_prefix() + [snapshot_with_huge_exponent])
        self.assertEqual(result["reason"], "snapshot_invalid")
        self.assertIn("do not retry", result["message"])

    def test_snapshot_report_redacts_echoed_token(self):
        def snapshot_with_echo(method, url, body, token):
            value = snap(window=body)
            value["report"]["unexpected"] = {"secret": "prefix-" + token}
            return reply(201, value)

        result, _, _ = run_with(successful_prefix() + [snapshot_with_echo])
        self.assertEqual(result["status"], "observed")
        self.assertEqual(result["snapshotReport"]["unexpected"]["secret"], "prefix-[REDACTED]")

    def test_active_invalid_and_redirect_prevent_post(self):
        for preflight in [reply(200, view()), reply(200, {"running": False}), reply(302), reply(204, {})]:
            with self.subTest(preflight=preflight):
                result, tr, _ = run_with([preflight])
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(len(tr.calls), 1)

    def test_completed_old_current_allowed_after_server_clock_advances(self):
        old = complete() | {"scenarioId": "old", "startedAt": "2025-12-31T23:59:55Z"}
        result, tr, _ = run_with(successful_prefix(old) + [snapshot_reply])
        self.assertEqual(result["status"], "observed")
        self.assertEqual(sum(c[0] == "POST" and "/chaos/" in c[1] for c in tr.calls), 1)

    def test_same_second_date_wait_and_timeout(self):
        old = complete() | {"scenarioId": "old"}
        recent = "Thu, 01 Jan 2026 00:00:00 GMT"
        result, tr, _ = run_with([reply(200, old, recent)] * 4)
        self.assertEqual(result["reason"], "collision_risk")
        self.assertTrue(all(c[0] == "GET" for c in tr.calls))
        old["startedAt"] = "2025-12-31T23:59:59Z"
        result, tr, _ = run_with([reply(200, old, "Wed, 31 Dec 2025 23:59:59 GMT"),
                                  reply(200, old, "Thu, 01 Jan 2026 00:00:01 GMT"),
                                  reply(200, view(startedAt="2026-01-01T00:00:01Z", scenarioId="cascade-database-20260101-000001")),
                                  lambda *a: reply(200, complete() | {"startedAt": "2026-01-01T00:00:01Z", "scenarioId": "cascade-database-20260101-000001"})], args(tail_seconds=1))
        self.assertEqual(result["reason"], "run_incomplete")
        self.assertEqual(len(tr.calls), 4)

    def test_collision_wait_compares_nanosecond_instants_not_timestamp_spelling(self):
        old = complete() | {"scenarioId": "old", "startedAt": "2026-01-01T00:00:00.000000001Z"}
        equivalent = old | {"startedAt": "2026-01-01T03:00:00.000000001+03:00"}
        changed = old | {"startedAt": "2026-01-01T00:00:00.000000002Z"}
        for next_view, expected in ((equivalent, "post_rejected"), (changed, "current_changed")):
            result, tr, _ = run_with([reply(200, old),
                                      reply(200, next_view, "Thu, 01 Jan 2026 00:00:02 GMT"),
                                      reply(400)])
            self.assertEqual(result["reason"], expected)
            self.assertEqual(len(tr.calls), 3 if next_view is equivalent else 2)

    def test_missing_date_or_changed_current_prevents_post(self):
        old = complete() | {"scenarioId": "old"}
        result, tr, _ = run_with([reply(200, old, None)])
        self.assertEqual(result["reason"], "collision_risk")
        result, tr, _ = run_with([reply(200, old, "Thu, 01 Jan 2026 00:00:00 GMT"), reply(200, view())])
        self.assertEqual(result["reason"], "current_changed")
        self.assertTrue(all(c[0] == "GET" for c in tr.calls))

    def test_post_outcomes_never_retry_or_stop(self):
        for response, reason in [(reply(400), "post_rejected"), (reply(500), "post_uncertain"),
                                 (reply(302), "post_uncertain"), (r.Failure("transport_error", "no"), "post_uncertain"),
                                 (reply(200, {}), "post_uncertain")]:
            result, tr, _ = run_with([reply(204), response])
            self.assertEqual(result["reason"], reason)
            self.assertEqual(len(tr.calls), 2)

    def test_post_collision_is_uncertain_and_no_snapshot(self):
        old = complete() | {"scenarioId": SCENARIO, "startedAt": "2025-12-31T23:59:00Z"}
        result, tr, _ = run_with([reply(200, old), reply(200, view())])
        self.assertEqual(result["reason"], "post_uncertain")
        self.assertEqual(len(tr.calls), 2)

    def test_identity_change_204_incomplete_and_timeout(self):
        cases = [([reply(204)], "identity_changed"),
                 ([reply(200, complete() | {"scenarioId": "other"})], "identity_changed"),
                 ([reply(200, complete() | {"startedAt": "2026-01-01T00:00:01Z"})], "identity_changed"),
                 ([reply(200, complete() | {"stepsDone": 0})], "run_incomplete"),
                 ([reply(200, complete() | {"elapsedSeconds": 30})], "run_incomplete")]
        for suffix, reason in cases:
            result, tr, _ = run_with([reply(204), reply(200, view())] + suffix)
            self.assertEqual(result["reason"], reason)
            self.assertTrue(all("evaluation" not in c[1] for c in tr.calls))
        result, _, _ = run_with([reply(204), reply(200, view())] + [reply(200, view())] * 182)
        self.assertEqual(result["reason"], "run_timeout")

    def test_label_count_scenario_duplicates_and_timeout(self):
        prefix = [reply(204), reply(200, view()), reply(200, complete())]
        cases = [({"faults": 2, "rows": [row(), row()]}, "duplicate_labels"),
                 ({"faults": 2, "rows": [row(), row("f2")]}, "extra_labels"),
                 ({"faults": 1, "rows": [row(scenario="other")]}, "invalid_evaluation"),
                 ({"faults": 2, "rows": [row()]}, "invalid_evaluation")]
        for evaluation, reason in cases:
            result, tr, _ = run_with(prefix + [reply(200, evaluation)])
            self.assertEqual(result["reason"], reason)
            self.assertTrue(all(not c[1].endswith("/api/evaluation/runs") for c in tr.calls))
        result, tr, _ = run_with(prefix + [reply(200, {"faults": 0, "rows": []})] * 3)
        self.assertEqual(result["reason"], "labels_timeout")
        self.assertEqual(result["labelsFound"], 0)

    def test_unfinished_ambiguous_analyses_are_visible(self):
        issue = row(status="NO_ANALYSIS", candidates=[dict(incidentId="i", analyzed=False, ambiguous=True)])
        prefix = successful_prefix()
        prefix[3] = reply(200, {"faults": 1, "rows": [issue]})
        def snapshot(method, url, body, token):
            return reply(201, snap(window=body, rows=[issue], incidents=[dict(id="i", analysisStatus="PENDING", analysisPresent=False)]))
        result, _, _ = run_with(prefix + [snapshot])
        self.assertEqual(result["status"], "incomplete")
        self.assertTrue(result["analysisIssues"])
        self.assertTrue(result["snapshotAnalysisIssues"])
        self.assertTrue(result["snapshotIncidentIssues"])

    def test_snapshot_failure_uncertainty_and_invalid_response(self):
        for response, reason in [(reply(400), "snapshot_error"), (reply(500), "snapshot_uncertain"),
                                 (reply(302), "snapshot_uncertain"), (r.Failure("transport_error", "no"), "snapshot_uncertain"),
                                 (reply(201, {}), "snapshot_invalid")]:
            result, tr, _ = run_with(successful_prefix() + [response])
            self.assertEqual(result["reason"], reason)
            self.assertEqual(sum(c[1].endswith("/api/evaluation/runs") for c in tr.calls), 1)

    def test_recheck_detects_replacement_before_snapshot(self):
        prefix = successful_prefix()
        prefix[-1] = reply(200, complete() | {"scenarioId": "other"})
        result, tr, _ = run_with(prefix)
        self.assertEqual(result["reason"], "identity_changed")
        self.assertFalse(any(c[1].endswith("/api/evaluation/runs") for c in tr.calls))

    def test_tail_and_snapshot_end_use_current_clock(self):
        result, tr, clock = run_with(successful_prefix() + [snapshot_reply], args(tail_seconds=42))
        self.assertEqual(result["status"], "observed")
        self.assertEqual(clock.seconds, 222)
        self.assertEqual(tr.calls[-1][2]["to"], "2026-01-01T00:03:47.000001Z")

    def test_nanosecond_instants_compare_exactly_and_normalize_offsets(self):
        first = r.instant("2026-01-01T00:00:00.123456001Z")
        self.assertEqual(first, r.instant("2026-01-01T03:00:00.123456001+03:00"))
        self.assertLess(first, r.instant("2026-01-01T00:00:00.123456002Z"))
        self.assertEqual(r.instant("2026-01-01T00:00:00.1Z"),
                         r.instant("2026-01-01T00:00:00.100000000Z"))
        for invalid in ("2026-01-01T00:00:00.1234567890Z", "2026-01-01T00:00:00.Z",
                        "2026-01-01T00:00:00.123456789", "2026-02-30T00:00:00Z"):
            with self.subTest(invalid=invalid), self.assertRaises(r.Failure):
                r.instant(invalid)

    def test_nanosecond_start_is_preserved_and_snapshot_floor_is_outward(self):
        for fraction in ("000000001", "123456789", "999999999"):
            with self.subTest(fraction=fraction):
                started = "2026-01-01T00:00:00." + fraction + "Z"
                prefix = successful_prefix()
                prefix[1] = reply(200, view(startedAt=started))
                equivalent = "2026-01-01T03:00:00." + fraction + "+03:00"
                prefix[2] = reply(200, complete() | {"startedAt": equivalent})
                prefix[4] = reply(200, complete() | {"startedAt": equivalent})
                result, tr, _ = run_with(prefix + [snapshot_reply])
                self.assertEqual(result["status"], "observed")
                self.assertEqual(result["startedAt"], started)
                self.assertEqual(tr.calls[-1][2]["from"], "2025-12-31T23:59:55." + fraction[:6] + "Z")

    def test_identity_change_within_one_microsecond_is_detected(self):
        prefix = [reply(204), reply(200, view(startedAt="2026-01-01T00:00:00.123456001Z")),
                  reply(200, complete() | {"startedAt": "2026-01-01T00:00:00.123456002Z"})]
        result, tr, _ = run_with(prefix)
        self.assertEqual(result["reason"], "identity_changed")
        self.assertFalse(any("evaluation" in call[1] for call in tr.calls))

    def test_snapshot_echo_must_match_at_nanosecond_precision(self):
        for field in ("from", "to"):
            def snapshot(method, url, body, token):
                altered = dict(body)
                altered[field] = altered[field][:-1] + "001Z"
                return reply(201, snap(window=altered))
            result, _, _ = run_with(successful_prefix() + [snapshot])
            self.assertEqual(result["reason"], "snapshot_invalid")

    def test_snapshot_end_covers_server_ahead_by_four_or_five_seconds(self):
        for skew in (4, 5):
            with self.subTest(skew=skew):
                started = f"2026-01-01T00:00:0{skew}Z"
                scenario = f"cascade-database-20260101-00000{skew}"
                active = view(startedAt=started, scenarioId=scenario)
                done = active | {"running": False, "stepsDone": 1, "elapsedSeconds": 180}
                clock = FakeClock()
                def finish(*unused):
                    clock.sleep(180)
                    return reply(200, done)
                def snapshot(method, url, body, token):
                    end = r.instant(body["to"])
                    # Include even an event exactly on the +5-second boundary.
                    self.assertGreater(end, (clock.now() + dt.timedelta(seconds=skew), 0))
                    return reply(201, snap(window=body, rows=[row(scenario=scenario)],
                                           labels=[label(scenario=scenario)]))
                tr = FakeTransport([reply(204), reply(200, active), finish,
                                    reply(200, {"faults": 1, "rows": [row(scenario=scenario)]}),
                                    reply(200, done), snapshot], clock)
                result = r.run(args(), tr, clock, "secret-token")
                self.assertEqual(result["status"], "observed")
                self.assertEqual(tr.calls[-1][2]["to"], "2026-01-01T00:03:05.000001Z")

    def test_snapshot_start_covers_events_from_slower_clock(self):
        started = "2026-01-01T00:00:05.000000001Z"
        scenario = "cascade-database-20260101-000005"
        clock = FakeClock()
        # POST is observed just after +5 seconds, keeping the server start in tolerance.
        clock.seconds = 0.000001
        active = view(startedAt=started, scenarioId=scenario)
        done = active | {"running": False, "stepsDone": 1, "elapsedSeconds": 180}
        def finish(*unused):
            clock.sleep(180)
            return reply(200, done)
        def snapshot(method, url, body, token):
            self.assertEqual(body["from"], "2026-01-01T00:00:00.000000Z")
            self.assertLessEqual(r.instant(body["from"]), r.instant("2026-01-01T00:00:00.000000001Z"))
            return reply(201, snap(window=body, rows=[row(scenario=scenario)],
                                   labels=[label(scenario=scenario)]))
        tr = FakeTransport([reply(204), reply(200, active), finish,
                            reply(200, {"faults": 1, "rows": [row(scenario=scenario)]}),
                            reply(200, done), snapshot], clock)
        self.assertEqual(r.run(args(), tr, clock, "secret-token")["status"], "observed")

    def test_snapshot_matched_incident_must_exist_and_ids_must_be_unique(self):
        valid = dict(id=INCIDENT, analysisStatus="DONE", analysisPresent=True)
        for incidents in ([], [dict(valid, id="other")], [valid, valid], [dict(valid, id="")]):
            with self.subTest(incidents=incidents):
                def snapshot(method, url, body, token):
                    return reply(201, snap(window=body, incidents=incidents))
                result, _, _ = run_with(successful_prefix() + [snapshot])
                self.assertEqual(result["reason"], "snapshot_invalid")
        for selected in (None, "missing", [], {}):
            issue = row() | {"incidentId": selected}
            def snapshot(method, url, body, token):
                return reply(201, snap(window=body, rows=[issue]))
            result, _, _ = run_with(successful_prefix() + [snapshot])
            self.assertEqual(result["reason"], "snapshot_invalid")

    def test_decimal_snapshot_timestamps_preserve_nanos_and_reject_ambiguous_units(self):
        def snapshot_with(from_value, to_value):
            def snapshot(method, url, body, token):
                # JSON numeric literals must not pass through a binary float.
                data = json.dumps(snap(window=body)).encode()
                data = data.replace(json.dumps(body["from"]).encode(), from_value.encode(), 1)
                data = data.replace(json.dumps(body["to"]).encode(), to_value.encode(), 1)
                return 201, data, DATE
            return snapshot
        result, _, _ = run_with(successful_prefix() + [snapshot_with("1767225595.000000000", "1767225785.000001000")])
        self.assertEqual(result["status"], "observed")
        for value in ("1767225595", "1767225595000", "1767225595.000000001",
                      "1767225595.0000000000", "1e999", "-1.0", "true"):
            with self.subTest(value=value):
                result, _, _ = run_with(successful_prefix() + [snapshot_with(value, "1767225785.000001000")])
                self.assertEqual(result["reason"], "snapshot_invalid")
                if value in ("1767225595", "1767225595000"):
                    self.assertIn("Ambiguous", result["message"])
        self.assertEqual(r.instant(r.json_body(b'1767225595.000000001'), decimal_seconds=True),
                         r.instant("2025-12-31T23:59:55.000000001Z"))
        with self.assertRaises(r.Failure):
            r.instant(r.json_body(b'1767225595.000000001'))

    def test_start_skew_over_five_seconds_is_rejected_at_nanosecond_precision(self):
        started = "2026-01-01T00:00:05.000000001Z"
        scenario = "cascade-database-20260101-000005"
        result, tr, _ = run_with([reply(204), reply(200, view(startedAt=started, scenarioId=scenario))])
        self.assertEqual(result["reason"], "post_uncertain")
        self.assertEqual(len(tr.calls), 2)

    def test_invalid_url_and_token_fail_before_network(self):
        for url in ("http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com/?q=x"):
            result, tr, _ = run_with([], args(stand_url=url))
            self.assertEqual(result["reason"], "invalid_url")
            self.assertFalse(tr.calls)
        self.assertEqual(r.base_url("https://example.com"), "https://example.com")
        tr = FakeTransport([])
        self.assertEqual(r.run(args(), tr, FakeClock(), "bad\nsecret")["reason"], "missing_token")
        self.assertFalse(tr.calls)

    def test_cli_tty_required_and_single_json_stdout(self):
        out = io.StringIO()
        with mock.patch("sys.stdin", io.StringIO()), contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = r.main(["cascade-database", "--execute"])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out.getvalue())["reason"], "tty_required")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(r.main(["cascade-database"]), 0)
        self.assertEqual(json.loads(out.getvalue())["status"], "dry_run")

    def test_redacts_server_echo_of_token(self):
        issue = row(status="NO_ANALYSIS", candidates=[dict(incidentId="secret-token", analyzed=False, ambiguous=True)])
        prefix = successful_prefix()
        prefix[3] = reply(200, {"faults": 1, "rows": [issue]})
        def snapshot(method, url, body, token):
            return reply(201, snap(window=body, rows=[issue]))
        result, _, _ = run_with(prefix + [snapshot])
        self.assertIn("[REDACTED]", json.dumps(result))

    def test_snapshot_labels_must_match_observed_ids(self):
        def snapshot(method, url, body, token):
            return reply(201, snap(window=body, labels=[label(fault="different")]))
        result, _, _ = run_with(successful_prefix() + [snapshot])
        self.assertEqual(result["reason"], "snapshot_invalid")

    def test_preliminary_identity_is_required_before_snapshot(self):
        for field in ("scenarioId", "faultId", "type", "expectedSource"):
            for value in (None, "", "   ", 123, [], {}):
                with self.subTest(field=field, value=value):
                    preliminary = row() | {field: value}
                    prefix = successful_prefix()
                    prefix[3] = reply(200, {"faults": 1, "rows": [preliminary]})
                    result, tr, _ = run_with(prefix)
                    self.assertEqual(result["reason"], "invalid_evaluation")
                    self.assertFalse(any(call[1].endswith("/api/evaluation/runs") for call in tr.calls))
            preliminary = row()
            del preliminary[field]
            prefix = successful_prefix()
            prefix[3] = reply(200, {"faults": 1, "rows": [preliminary]})
            result, tr, _ = run_with(prefix)
            self.assertEqual(result["reason"], "invalid_evaluation")
            self.assertFalse(any(call[1].endswith("/api/evaluation/runs") for call in tr.calls))

    def test_snapshot_identity_must_match_complete_preliminary_identity(self):
        for field, value in (("type", "OTHER"), ("source", "other")):
            with self.subTest(field=field):
                changed_label = label() | {field: value}
                changed_row = row() | {"type" if field == "type" else "expectedSource": value}
                def snapshot(method, url, body, token):
                    return reply(201, snap(window=body, labels=[changed_label], rows=[changed_row]))
                result, tr, _ = run_with(successful_prefix() + [snapshot])
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(result["reason"], "snapshot_invalid")
                self.assertIn("preliminary evaluation", result["message"])
                self.assertIn("do not retry automatically", result["message"])
                self.assertNotIn("snapshotId", result)
                self.assertEqual(sum(call[1].endswith("/api/evaluation/runs") for call in tr.calls), 1)

    def test_snapshot_limit_is_uncertain_with_recovery_and_no_retry(self):
        for oversized in (r.Failure("response_too_large", "HTTP response exceeds the size limit"),
                          (201, b"x" * (r.LIMIT + 1), DATE)):
            with self.subTest(oversized=type(oversized).__name__):
                result, tr, _ = run_with(successful_prefix() + [oversized])
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(result["reason"], "snapshot_uncertain")
                self.assertIn("2 MiB", result["message"])
                self.assertIn("may have been created", result["message"])
                self.assertIn("do not retry automatically", result["message"].lower())
                self.assertIn("GET /api/evaluation/runs", result["message"])
                self.assertIn("snapshotWindow", result["message"])
                self.assertIn(result["message"], result["warnings"])
                self.assertIn("snapshotWindow", result)
                self.assertNotIn("snapshotId", result)
                self.assertEqual(sum(call[1].endswith("/api/evaluation/runs") for call in tr.calls), 1)

    def test_transport_enforces_two_mib_limit_without_network(self):
        class FakeResponse:
            status = 201
            headers = {"Date": DATE}
            def __init__(self, size):
                self.size = size
                self.read_size = None
            def __enter__(self):
                return self
            def __exit__(self, *unused):
                pass
            def read(self, size):
                self.read_size = size
                return b"x" * min(size, self.size)
        class FakeOpener:
            def __init__(self, response):
                self.response = response
            def open(self, request, timeout):
                return self.response
        for size in (r.LIMIT, r.LIMIT + 1):
            with self.subTest(size=size):
                fake = FakeResponse(size)
                transport = r.Transport(10)
                transport.opener = FakeOpener(fake)
                if size == r.LIMIT:
                    self.assertEqual(len(transport.request("POST", "http://localhost/")[1]), r.LIMIT)
                else:
                    with self.assertRaises(r.Failure) as error:
                        transport.request("POST", "http://localhost/")
                    self.assertEqual(error.exception.code, "response_too_large")
                self.assertEqual(fake.read_size, r.LIMIT + 1)

    def test_snapshot_rows_and_labels_must_correspond_for_all_scenarios(self):
        other_label = label(scenario="other")
        other_row = row(scenario="other")
        valid_labels = [label(), other_label]
        valid_rows = [row(), other_row]

        def run_snapshot(labels, rows):
            def snapshot(method, url, body, token):
                return reply(201, snap(window=body, labels=labels, rows=rows))
            return run_with(successful_prefix() + [snapshot])[0]

        self.assertEqual(run_snapshot(valid_labels, valid_rows)["status"], "observed")
        cases = [
            ([label()], [row(scenario="other")]),
            (valid_labels, [row()]),
            ([label()], valid_rows),
            ([label(), other_label, other_label], valid_rows),
            (valid_labels, [row(), other_row, other_row]),
            ([label(), other_label | {"type": "OTHER"}], valid_rows),
            ([label(), other_label | {"source": "other"}], valid_rows),
            (valid_labels, [row(), other_row | {"type": "OTHER"}]),
            (valid_labels, [row(), other_row | {"expectedSource": "other"}]),
            (valid_labels, [row() | {"type": "OTHER"}, other_row]),
            (valid_labels, [row() | {"expectedSource": "other"}, other_row]),
        ]
        for field in ("scenarioId", "faultId", "type", "source"):
            for invalid in (None, "", 123):
                cases.append(([label(), other_label | {field: invalid}], valid_rows))
            missing = dict(other_label)
            del missing[field]
            cases.append(([label(), missing], valid_rows))
        for field in ("scenarioId", "faultId", "type", "expectedSource"):
            missing = dict(other_row)
            del missing[field]
            cases.append((valid_labels, [row(), missing]))
            cases.append((valid_labels, [row(), other_row | {field: None}]))
        cases.extend([([label(), None], valid_rows), ([label(), {}], valid_rows)])
        for labels, rows in cases:
            with self.subTest(labels=labels, rows=rows):
                result = run_snapshot(labels, rows)
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(result["reason"], "snapshot_invalid")
                self.assertNotIn("snapshotId", result)

    def test_snapshot_all_candidates_must_reference_saved_incidents(self):
        candidate = dict(incidentId="missing", analyzed=True, ambiguous=False)
        cases = [row(candidates=[dict(incidentId=INCIDENT, analyzed=True, ambiguous=False), candidate]),
                 row(status="NO_ANALYSIS", candidates=[candidate | {"analyzed": False}]),
                 row(status="AMBIGUOUS", candidates=[candidate | {"ambiguous": True}])]
        for issue in cases:
            with self.subTest(issue=issue):
                def snapshot(method, url, body, token):
                    return reply(201, snap(window=body, rows=[issue]))
                result, tr, _ = run_with(successful_prefix() + [snapshot])
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(result["reason"], "snapshot_invalid")
                self.assertEqual(sum(call[1].endswith("/api/evaluation/runs") for call in tr.calls), 1)

        # A valid shared incident remains ambiguous, not an invalid reference.
        ambiguous = dict(incidentId=INCIDENT, analyzed=True, ambiguous=True)
        rows = [row(status="AMBIGUOUS", candidates=[ambiguous]),
                row(scenario="other", status="AMBIGUOUS", candidates=[ambiguous])]
        def snapshot(method, url, body, token):
            return reply(201, snap(window=body, rows=rows, labels=[label(), label(scenario="other")]))
        result, _, _ = run_with(successful_prefix() + [snapshot])
        self.assertEqual(result["status"], "incomplete")
        self.assertNotIn("reason", result)
        self.assertTrue(result["snapshotAnalysisIssues"])

    def test_snapshot_missing_or_invalid_row_identity_fails_closed_in_each_scenario(self):
        for scenario in (SCENARIO, "other"):
            for field in ("scenarioId", "faultId", "type", "expectedSource"):
                for value in ("missing", None, "", "   ", 123, [], {}):
                    with self.subTest(scenario=scenario, field=field, value=value):
                        bad_row = row(scenario=scenario)
                        if value == "missing":
                            del bad_row[field]
                        else:
                            bad_row[field] = value
                        rows = [bad_row, row(scenario="other")] if scenario == SCENARIO else [row(), bad_row]
                        def snapshot(method, url, body, token):
                            return reply(201, snap(window=body, labels=[label(), label(scenario="other")], rows=rows))
                        result, tr, _ = run_with(successful_prefix() + [snapshot])
                        self.assertEqual(result["status"], "incomplete")
                        self.assertEqual(result["reason"], "snapshot_invalid")
                        self.assertNotIn("snapshotId", result)
                        self.assertEqual(sum(call[1].endswith("/api/evaluation/runs") for call in tr.calls), 1)

    def test_snapshot_other_scenario_missing_candidate_is_rejected_for_all_statuses(self):
        valid = dict(incidentId=INCIDENT, analyzed=True, ambiguous=False)
        missing = dict(incidentId="missing", analyzed=True, ambiguous=False)
        cases = [row(scenario="other", candidates=[valid, missing]),
                 row(scenario="other", status="NO_INCIDENT", candidates=[missing]),
                 row(scenario="other", status="NO_ANALYSIS", candidates=[missing | {"analyzed": False}]),
                 row(scenario="other", status="AMBIGUOUS", candidates=[missing | {"ambiguous": True}])]
        for other in cases:
            with self.subTest(status=other["matchStatus"]):
                def snapshot(method, url, body, token):
                    return reply(201, snap(window=body, rows=[row(), other],
                                           labels=[label(), label(scenario="other")]))
                result, _, _ = run_with(successful_prefix() + [snapshot])
                self.assertEqual(result["status"], "incomplete")
                self.assertEqual(result["reason"], "snapshot_invalid")
                self.assertNotIn("snapshotId", result)

    def test_snapshot_other_scenario_shared_candidate_makes_matched_rows_incomplete(self):
        shared = dict(incidentId=INCIDENT, analyzed=True, ambiguous=True)
        unique = dict(incidentId="unique", analyzed=True, ambiguous=False)
        rows = [row(candidates=[unique, shared]) | {"incidentId": "unique"},
                row(scenario="other", status="AMBIGUOUS", candidates=[shared])]
        incidents = [dict(id=INCIDENT, analysisStatus="DONE", analysisPresent=True),
                     dict(id="unique", analysisStatus="DONE", analysisPresent=True)]
        def snapshot(method, url, body, token):
            return reply(201, snap(window=body, rows=rows, incidents=incidents,
                                   labels=[label(), label(scenario="other")]))
        result, _, _ = run_with(successful_prefix() + [snapshot])
        self.assertEqual(result["status"], "incomplete")
        self.assertNotIn("reason", result)
        self.assertEqual(result["snapshotId"], "12345678-1234-5678-1234-567812345678")
        self.assertEqual(sum(issue.get("ambiguous") is True for issue in result["snapshotAnalysisIssues"]), 2)

    def test_untrusted_server_date_cannot_make_older_start_valid(self):
        old = complete() | {"scenarioId": "old", "startedAt": "2025-12-31T23:59:00Z"}
        result, tr, _ = run_with([reply(200, old, "Thu, 01 Jan 2026 00:00:02 GMT"), reply(200, view())])
        self.assertEqual(result["reason"], "post_uncertain")
        self.assertEqual(len(tr.calls), 2)

    def test_redirect_handler_never_creates_redirect_request(self):
        self.assertIsNone(r.NoRedirect().redirect_request(None, None, 302, "found", {}, "https://evil.example"))

    def test_json_rejects_duplicate_keys_and_nonfinite_values(self):
        for raw in (b'{"running":true,"running":false}', b'{"value":NaN}'):
            with self.assertRaises(r.Failure):
                r.json_body(raw)

    def test_post_scenario_id_is_derived_from_server_utc_start(self):
        for invalid in ("other-20260101-000000", "cascade-database-20260101-000001", "arbitrary"):
            result, tr, _ = run_with([reply(204), reply(200, view(scenarioId=invalid))])
            self.assertEqual(result["reason"], "post_uncertain")
            self.assertEqual(len(tr.calls), 2)
        clock = FakeClock()
        clock.seconds = 0.5
        transport = FakeTransport([reply(204), reply(200, view(startedAt="2026-01-01T03:00:00.500+03:00")),
                                   reply(200, complete() | {"startedAt": "2026-01-01T03:00:00.500+03:00"}),
                                   reply(200, {"faults": 1, "rows": [row()]}),
                                   reply(200, complete() | {"startedAt": "2026-01-01T03:00:00.500+03:00"}), snapshot_reply], clock)
        self.assertEqual(r.run(args(), transport, clock, "secret-token")["status"], "observed")

    def test_early_completion_rejected_independently_of_server_progress(self):
        for monotonic, wall in ((0, 0), (180, 0), (0, 180)):
            clock = FakeClock()
            def completed(*unused):
                clock.seconds = monotonic
                clock.now = lambda: START + dt.timedelta(seconds=wall)
                return reply(200, complete())
            tr = FakeTransport([reply(204), reply(200, view()), completed])
            result = r.run(args(), tr, clock, "secret-token")
            self.assertEqual(result["reason"], "run_incomplete")
            self.assertFalse(any("evaluation" in call[1] for call in tr.calls))

    def test_matched_selected_candidate_must_be_unique_analyzed_and_unambiguous(self):
        valid = dict(incidentId=INCIDENT, analyzed=True, ambiguous=False)
        cases = [(INCIDENT, []), (INCIDENT, [valid, valid]),
                 (INCIDENT, [dict(valid, analyzed=False)]), (INCIDENT, [dict(valid, ambiguous=True)])]
        for selected, candidates in cases:
            issue = row(candidates=candidates) | {"incidentId": selected}
            prefix = successful_prefix()
            prefix[3] = reply(200, {"faults": 1, "rows": [issue]})
            def snapshot(method, url, body, token):
                return reply(201, snap(window=body, rows=[issue]))
            result, _, _ = run_with(prefix + [snapshot])
            self.assertEqual(result["status"], "incomplete")
            self.assertTrue(any(x.get("issue") == "invalid_selected_candidate" for x in result["analysisIssues"]))
            self.assertTrue(any(x.get("issue") == "invalid_selected_candidate" for x in result["snapshotAnalysisIssues"]))

    def test_strict_poll_deadlines_reject_late_results_and_clamp_timeout(self):
        for mode in ("run", "labels", "collision"):
            clock = FakeClock()
            def late(*unused):
                clock.sleep(2)
                if mode == "run":
                    return reply(200, complete())
                if mode == "labels":
                    return reply(200, {"faults": 1, "rows": [row()]})
                return reply(200, complete() | {"scenarioId": "old"}, "Thu, 01 Jan 2026 00:00:02 GMT")
            if mode == "run":
                opts = args(run_grace=0)
                def delayed(*unused):
                    clock.sleep(179)
                    return reply(200, view())
                responses = [reply(204), delayed, late]
                reason = "run_timeout"
            elif mode == "labels":
                opts = args(labels_timeout=1)
                responses = [reply(204), reply(200, view()), reply(200, complete()), late]
                reason = "labels_timeout"
            else:
                opts = args(collision_wait=2, poll_interval=1)
                responses = [reply(200, complete() | {"scenarioId": "old"}), late]
                reason = "collision_risk"
            tr = FakeTransport(responses, clock)
            result = r.run(opts, tr, clock, "secret-token")
            self.assertEqual(result["reason"], reason)
            self.assertLessEqual(tr.timeouts[-1], 1)
            self.assertFalse(any(call[1].endswith("/api/evaluation/runs") for call in tr.calls))
            if mode == "collision":
                self.assertTrue(all(call[0] == "GET" for call in tr.calls))

    def test_zero_label_deadline_makes_no_evaluation_request(self):
        result, tr, _ = run_with(successful_prefix(), args(labels_timeout=0))
        self.assertEqual(result["reason"], "labels_timeout")
        self.assertFalse(any("evaluation" in call[1] for call in tr.calls))

    def test_transport_total_timeout_bounds_a_stalled_fake_response(self):
        import threading
        gate = threading.Event()
        class Opener:
            def open(self, request, timeout):
                gate.wait(1)
                raise OSError()
        transport = r.Transport(0.02)
        transport.opener = Opener()
        try:
            with self.assertRaises(r.Failure) as error:
                transport.request("GET", "http://localhost/", timeout=0.01)
            self.assertEqual(error.exception.code, "transport_error")
        finally:
            gate.set()


if __name__ == "__main__":
    unittest.main()
