"""Карточки и Bot API payload: все сетевые вызовы подменены."""
import datetime as dt
import json
import os
import unittest
from unittest.mock import patch

import meeting_protocol as protocol
import telegram_cards as cards
import telegram_notify as notify


def event(action="opened"):
    return {
        "action": action, "sender": {"login": "alice"}, "assignee": {"login": "bob"},
        "issue": {
            "number": 86, "title": 'Задача <script> & "кавычки"',
            "html_url": 'https://github.com/example/advisor/issues/86?a=1&b=2',
            "body": "Проверить фильтр задач", "state": "open",
            "assignees": [{"login": "bob"}, {"login": "carol"}],
            "milestone": {"title": "Спринт 3"}, "labels": [{"name": "P1"}, {"name": "infra"}],
        },
        "comment": {"body": "Проверил <b>вход</b> & выход", "html_url": "https://github.com/example/advisor/issues/86#issuecomment-1"},
    }


class Formatting(unittest.TestCase):
    def test_issue_card_contains_metadata_buttons_and_escaped_external_text(self):
        result = notify.message("issues", event())
        self.assertIsInstance(result, cards.Card)
        self.assertIn("<h2>#86 Задача &lt;script&gt; &amp; &quot;кавычки&quot;</h2>", result.rich_html)
        for value in ("Спринт 3", "P1", "infra", "bob, carol", '<table compact>', '<tg-button type="url"'):
            self.assertIn(value, result.rich_html)
        self.assertNotIn("<script>", result.rich_html)
        self.assertIn("a=1&amp;b=2", result.rich_html)
        self.assertNotIn("<table", "".join(result.fallback_parts))

    def test_all_issue_events_have_explicit_event_titles_without_invented_board_status(self):
        for action, title in (("opened", "Новая задача"), ("assigned", "Задача назначена"),
                              ("reopened", "Задача снова открыта"), ("closed", "Задача выполнена")):
            with self.subTest(action=action):
                payload = event(action)
                payload["issue"]["state"] = "closed" if action == "closed" else "open"
                result = notify.message("issues", payload)
                self.assertIn(title, result.rich_html)
                self.assertNotIn("In progress", result.rich_html)
                if action == "closed":
                    self.assertIn('style="success"', result.rich_html)
        payload["issue"]["state_reason"] = "not_planned"
        self.assertIn("неактуальная", notify.message("issues", payload).rich_html)

    def test_comment_card_links_to_comment_and_escapes_body(self):
        result = notify.message("issue_comment", event("created"))
        self.assertIn("&lt;b&gt;вход&lt;/b&gt; &amp; выход", result.rich_html)
        self.assertIn("#issuecomment-1", result.rich_html)
        self.assertIn("Открыть комментарий", result.rich_html)

    def test_missing_optional_metadata_is_supported(self):
        payload = event()
        for field in ("assignees", "milestone", "labels", "body"):
            payload["issue"].pop(field)
        result = notify.message("issues", payload)
        self.assertIn("Не назначены", result.rich_html)
        self.assertNotIn("<blockquote>", result.rich_html)

    def test_meetings_and_pr_comments_keep_existing_message_path(self):
        payload = event()
        payload["issue"]["labels"] = [{"name": "встреча"}]
        self.assertIsInstance(notify.message("issues", payload), str)
        payload = event("created")
        payload["issue"]["pull_request"] = {"url": "https://example.com"}
        self.assertIsInstance(notify.message("issue_comment", payload), str)

    def test_protocol_counts_shared_tasks_once_and_keeps_both_statuses(self):
        members = [{"github": "alice", "first": "Алиса", "last": "Иванова"},
                   {"github": "bob", "first": "Боб", "last": "Петров"}]
        shared = protocol.Task(1, "Исправить & проверить", "https://github.com/example/repo/issues/1", "in review")
        other = protocol.Task(2, "Новая задача", "https://github.com/example/repo/issues/2", "in progress")
        result = cards.protocol_card(dt.date(2026, 11, 17), "@curator", members,
                                     {"alice": [shared, other], "bob": [shared]}, event()["issue"])
        self.assertIn("<td>2</td><td>1</td><td>1</td>", result.rich_html)
        self.assertIn("<details>", result.rich_html)
        self.assertIn("🟦 В работе", result.rich_html)
        self.assertIn("🟨 На ревью", result.rich_html)
        self.assertEqual(result.rich_html.count("Исправить &amp; проверить"), 2)
        self.assertIn("Открыть встречу в GitHub", result.rich_html)

    def test_long_protocol_fallback_is_split_without_losing_or_cutting_tasks(self):
        member = {"github": "alice", "first": "Алиса", "last": "Иванова"}
        tasks = [protocol.Task(n, "🟦 " + "A&B " * 40, f"https://github.com/example/repo/issues/{n}", "in progress")
                 for n in range(1, 101)]
        result = cards.protocol_card(dt.date(2026, 11, 17), "@curator", [member], {"alice": tasks})
        self.assertGreater(len(result.fallback_parts), 1)
        for part in result.fallback_parts:
            self.assertLessEqual(len(part.encode("utf-16-le")) // 2, 3500)
            self.assertEqual(part.count('<a href="'), part.count("</a>"))
        combined = "\n".join(result.fallback_parts)
        for task in tasks:
            self.assertEqual(combined.count(f'href="{task.url}"'), 1)


class Delivery(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.dict(os.environ, {"TELEGRAM_RICH_MESSAGES": "true"}))
        self.card = cards.card(["<h2>Карточка</h2>"], ["<b>Карточка</b>"])

    def test_rich_payload_uses_json_and_preserves_topic(self):
        with patch.object(notify, "tg", return_value={"ok": True}) as api:
            self.assertTrue(notify.send("-100:42", self.card, {}))
        self.assertEqual(api.call_count, 1)
        args, kwargs = api.call_args
        self.assertEqual(args, ("sendRichMessage",))
        self.assertEqual(kwargs["chat_id"], "-100")
        self.assertEqual(kwargs["message_thread_id"], "42")
        self.assertEqual(json.loads(kwargs["rich_message"]), {"html": self.card.rich_html})
        self.assertNotIn("parse_mode", kwargs)

    def test_explicit_rejection_falls_back_to_complete_html_parts(self):
        large = cards.card(["<h2>Карточка</h2>"], ["<b>" + "x" * 2000 + "</b>"] * 3)
        for code in (400, 404):
            with self.subTest(code=code), patch.object(notify, "tg", side_effect=[
                    {"ok": False, "error_code": code, "description": "unsupported rich message"},
                    *[{"ok": True}] * len(large.fallback_parts)]) as api:
                self.assertTrue(notify.send("-100:42", large, {}))
                calls = api.call_args_list[1:]
                self.assertEqual([c.kwargs["text"] for c in calls], list(large.fallback_parts))
                self.assertTrue(all(c.args == ("sendMessage",) for c in calls))
                self.assertTrue(all(c.kwargs["message_thread_id"] == "42" for c in calls))

    def test_network_server_and_rate_errors_do_not_fall_back(self):
        for result in ({"ok": False, "description": "Telegram не ответил"},
                       {"ok": False, "error_code": 500},
                       {"ok": False, "error_code": 429, "parameters": {"retry_after": 10}}):
            with self.subTest(result=result), patch.object(notify, "tg", return_value=result) as api:
                self.assertFalse(notify.send("-100:42", self.card, {}))
                self.assertEqual(api.call_count, 1)

    def test_forbidden_and_missing_chats_are_removed_without_fallback(self):
        for code, reason in ((403, "bot was kicked"), (400, "Bad Request: chat not found")):
            chats = {"-100": {"id": -100}}
            with patch.object(notify, "tg", return_value={"ok": False, "error_code": code, "description": reason}) as api:
                self.assertFalse(notify.send("-100:42", self.card, chats))
            self.assertEqual(api.call_count, 1)
            self.assertNotIn("-100", chats)

    def test_migration_retries_rich_card_in_same_topic(self):
        chats = {"-100": {"id": -100, "title": "Команда", "only": ["protocols"]}}
        with patch.object(notify, "tg", side_effect=[
                {"ok": False, "error_code": 400, "parameters": {"migrate_to_chat_id": -200}},
                {"ok": True}]) as api:
            self.assertTrue(notify.send("-100:42", self.card, chats))
        self.assertEqual([c.args[0] for c in api.call_args_list], ["sendRichMessage", "sendRichMessage"])
        self.assertEqual(api.call_args.kwargs["chat_id"], "-200")
        self.assertEqual(api.call_args.kwargs["message_thread_id"], "42")
        self.assertEqual(chats["-200"]["only"], ["protocols"])

    def test_manual_html_mode_skips_rich_api(self):
        with patch.dict(os.environ, {"TELEGRAM_RICH_MESSAGES": "false"}), \
                patch.object(notify, "tg", return_value={"ok": True}) as api:
            self.assertTrue(notify.send("-100", self.card, {}))
        self.assertEqual(api.call_args.args, ("sendMessage",))

    def test_failed_html_delivery_is_not_reported_as_success(self):
        with patch.object(notify, "tg", side_effect=[
                {"ok": False, "error_code": 400, "description": "unsupported rich message"},
                {"ok": False, "error_code": 500}]) as api:
            self.assertFalse(notify.send("-100:42", self.card, {}))
        self.assertEqual(api.call_count, 2)

    def test_legacy_notifications_still_use_send_message(self):
        with patch.object(notify, "tg", return_value={"ok": True}) as api:
            self.assertTrue(notify.send("-100", "<b>Напоминание</b>", {}))
        self.assertEqual(api.call_args.args, ("sendMessage",))


if __name__ == "__main__":
    unittest.main()
