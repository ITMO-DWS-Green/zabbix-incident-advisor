"""Выборка протокола и повтор при недоступной доске. Без сети и отправки сообщений."""
import datetime as dt
import os
import unittest
from unittest.mock import patch

import board_status as board
import meeting_protocol as protocol
import meeting_reminders as reminders

REPO = "example/advisor"
DATE = dt.datetime(2026, 11, 17, 12, 15, tzinfo=reminders.TZ)
MEMBERS = [
    {"github": "Alice", "first": "Алиса", "last": "Иванова"},
    {"github": "Bob", "first": "Боб", "last": "Петров"},
    {"github": "Carol", "first": "Кэрол", "last": "Сидорова"},
]


def issue(number, status, *, repo=REPO, state="OPEN", assignees=("Alice",), labels=(), kind="Issue"):
    return {
        "status": {"name": status} if status else None,
        "content": {
            "__typename": kind, "number": number, "title": f"Задача {number}",
            "url": f"https://github.com/{repo}/issues/{number}", "state": state,
            "repository": {"nameWithOwner": repo},
            "assignees": {"nodes": [{"login": a} for a in assignees]},
            "labels": {"nodes": [{"name": label} for label in labels]},
        },
    }


def page(rows, cursor=None):
    return {"data": {"organization": {"projectV2": {"items": {
        "nodes": rows, "pageInfo": {"hasNextPage": cursor is not None, "endCursor": cursor},
    }}}}}


class ProtocolTasks(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(board, "REPO", REPO))
        self.enterContext(patch.object(board, "OWNER", "example"))
        self.enterContext(patch.dict(os.environ, {"PROJECT_NUMBER": "7", "PROJECT_TOKEN": "project-token",
                                                "GITHUB_TOKEN": "repo-token"}))
        self.enterContext(patch.object(protocol, "team", return_value=MEMBERS))
        # Любой неожиданный REST-запрос к issues — регрессия старой выборки.
        self.rest = self.enterContext(patch.object(protocol, "api", side_effect=AssertionError("REST fallback")))

    def test_protocol_uses_both_active_columns_across_pages_and_all_assignees(self):
        shared = issue(2, " In Review ", assignees=("ALICE", "Bob"))
        rows = [
            issue(1, "In progress"),
            issue(3, "Backlog"), issue(4, "Ready"), issue(5, "Done"), issue(6, None),
            issue(7, "Ready for review"), issue(8, "In progress", state="CLOSED"),
            issue(9, "In review", repo="example/other"),
            issue(10, "In progress", labels=("встреча",)),
            issue(11, "In review", kind="PullRequest"), issue(12, "In progress", kind="DraftIssue"),
            {"status": {"name": "In progress"}, "content": None},
            issue(13, "In progress", assignees=()),
            {**issue(14, "In progress"), "isArchived": True},
        ]
        with patch.object(board, "http", side_effect=[page(rows, "next"), page([shared, shared])]) as http:
            text = protocol.build(DATE, "@curator").comment
        self.assertIn("Алиса Иванова:\n1) Задача 1", text)
        self.assertIn("2) Задача 2", text)
        self.assertIn("Боб Петров:\n1) Задача 2", text)
        self.assertIn("Кэрол Сидорова:\nзадач нет", text)
        self.assertEqual(text.count("https://github.com/"), 3)
        self.assertEqual([c.args[2]["variables"]["after"] for c in http.call_args_list], [None, "next"])
        self.assertTrue(all(c.args[1] == "project-token" for c in http.call_args_list))
        self.rest.assert_not_called()

    def test_empty_board_means_no_active_tasks(self):
        with patch.object(board, "http", return_value=page([])):
            text = protocol.build(DATE, "@curator").comment
        self.assertEqual(text.count("задач нет"), len(MEMBERS))

    def test_more_than_five_assignees_are_preserved(self):
        users = [f"user{i}" for i in range(8)]
        with patch.object(board, "http", return_value=page([issue(1, "In progress", assignees=users)])):
            self.assertEqual(set(protocol.assigned("project-token")), set(users))

    def test_missing_project_token_does_not_fall_back_to_repository_token(self):
        with patch.dict(os.environ, {"PROJECT_TOKEN": ""}), patch.object(board, "http") as http:
            with self.assertRaisesRegex(RuntimeError, "PROJECT_TOKEN"):
                protocol.build(DATE, "@curator")
        http.assert_not_called()
        self.rest.assert_not_called()

    def test_graphql_failure_on_second_page_discards_partial_tasks(self):
        responses = [page([issue(1, "In progress")], "next"), {"errors": [{"message": "access denied"}]}]
        with patch.object(board, "http", side_effect=responses):
            with self.assertRaisesRegex(RuntimeError, "access denied"):
                protocol.build(DATE, "@curator")
        self.rest.assert_not_called()

    def test_unavailable_project_has_actionable_error(self):
        with patch.object(board, "http", return_value={"data": {"organization": {"projectV2": None}}}):
            with self.assertRaisesRegex(RuntimeError, "PROJECT_TOKEN.*PROJECT_NUMBER"):
                protocol.build(DATE, "@curator")

    def test_reminder_still_groups_board_work_and_review(self):
        with patch.object(board, "http", return_value=page([issue(1, "In progress"), issue(2, "In review")])):
            work, review, source = board.from_board("project-token")
        self.assertEqual([i["number"] for i in work["Alice"]], [1])
        self.assertEqual([(i["number"], who) for i, who in review], [(2, "Alice")])
        self.assertEqual(source, "с доски")


class ProtocolDelivery(unittest.TestCase):
    def test_board_failure_does_not_send_or_mark_protocol_and_next_attempt_can_succeed(self):
        for error in (OSError("offline"), RuntimeError("no project access"), ValueError("bad project number")):
            with self.subTest(error=error), patch.dict(os.environ, {"GITHUB_TOKEN": "repo-token"}), \
                    patch.object(protocol, "build", side_effect=[error, protocol.Protocol("Протокол", "карточка")]) as build, \
                    patch.object(reminders.tg, "targets", return_value=["chat"]), \
                    patch.object(reminders.tg, "send", return_value=True) as send, \
                    patch.object(protocol, "comment") as comment:
                sent = {}
                reminders.send_protocol({"number": 60}, DATE, "@curator", {}, sent, "key", DATE)
                send.assert_not_called()
                comment.assert_not_called()
                self.assertNotIn("key", sent)
                reminders.send_protocol({"number": 60}, DATE, "@curator", {}, sent, "key", DATE)
                send.assert_called_once_with("chat", "карточка", {})
                comment.assert_called_once_with(60, "Протокол", "repo-token")
                self.assertEqual(sent["key"], DATE.isoformat())
                build.assert_called_with(DATE, "@curator", {"number": 60})


if __name__ == "__main__":
    unittest.main()
