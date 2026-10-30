"""python3 .github/scripts/test_meeting_reminders.py"""
import datetime as dt
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import meeting_reminders as mr  # noqa: E402

TZ = mr.TZ
# пятница, 30.10.2026 14:00 МСК
NOW = dt.datetime(2026, 10, 30, 14, 0, tzinfo=TZ)


def at(s):
    return mr.next_at(mr.parse(s), NOW)


class Parse(unittest.TestCase):
    def test_weekly(self):
        self.assertEqual(at("каждый вторник 12:15"), dt.datetime(2026, 11, 3, 12, 15, tzinfo=TZ))
        self.assertEqual(at("пятница 16:00"), dt.datetime(2026, 10, 30, 16, 0, tzinfo=TZ))
        self.assertEqual(at("по пятницам 13:00"), dt.datetime(2026, 11, 6, 13, 0, tzinfo=TZ))

    def test_several_days(self):
        self.assertEqual(at("пн, чт 19:00"), dt.datetime(2026, 11, 2, 19, 0, tzinfo=TZ))
        self.assertEqual(at("по будням 10.30"), dt.datetime(2026, 11, 2, 10, 30, tzinfo=TZ))
        self.assertEqual(at("каждый день 9:30"), dt.datetime(2026, 10, 31, 9, 30, tzinfo=TZ))

    def test_every_two_weeks(self):
        r = "каждые 2 недели по пятницам 16:00 с 06.11.2026"
        self.assertEqual(at(r), dt.datetime(2026, 11, 6, 16, 0, tzinfo=TZ))
        after = mr.next_at(mr.parse(r), dt.datetime(2026, 11, 6, 17, 0, tzinfo=TZ))
        self.assertEqual(after, dt.datetime(2026, 11, 20, 16, 0, tzinfo=TZ))
        self.assertEqual(at("раз в две недели вт 12:15 с 03.11.2026"), dt.datetime(2026, 11, 3, 12, 15, tzinfo=TZ))

    def test_once(self):
        self.assertEqual(at("05.11.2026 18:00"), dt.datetime(2026, 11, 5, 18, 0, tzinfo=TZ))
        self.assertIsNone(at("01.10.2026 18:00"))
        self.assertEqual(mr.parse("27.11.2026")["time"], None)
        self.assertEqual(at("27.11.2026 (время уточнить)"), dt.datetime(2026, 11, 27, 0, 0, tzinfo=TZ))

    def test_garbage(self):
        self.assertIsNone(mr.parse("когда-нибудь"))
        self.assertIsNone(mr.parse("каждый вторник"))


class Fields(unittest.TestCase):
    def test_form_and_line(self):
        body = "### Когда\n\nкаждый вторник 12:15\n\n### Ссылка\n\n_No response_\n\n### Повестка\n\nстатус"
        self.assertEqual(mr.field(body, "Когда"), "каждый вторник 12:15")
        self.assertEqual(mr.field(body, "Ссылка"), "")
        self.assertEqual(mr.field("Когда: пт 16:00\nСсылка: https://x", "Ссылка"), "https://x")


class Remind(unittest.TestCase):
    issue = {"number": 1, "title": "Встреча: с куратором", "html_url": "u", "body": "Когда: пт 16:00\nСсылка: https://x"}

    def test_windows(self):
        start = dt.datetime(2026, 10, 30, 16, 0, tzinfo=TZ)
        text, key = mr.reminder(self.issue, start, start - dt.timedelta(hours=20), {})
        self.assertTrue(text.startswith("📅 Сегодня в 16:00") or text.startswith("📅 Завтра в 16:00"))
        self.assertTrue(key.endswith(":day"))
        self.assertEqual(mr.reminder(self.issue, start, start - dt.timedelta(hours=20), {key: "x"}), (None, None))
        text, key = mr.reminder(self.issue, start, start - dt.timedelta(minutes=50), {})
        self.assertIn("Через 50 мин", text)
        self.assertTrue(key.endswith(":hour"))
        text, key = mr.reminder(self.issue, start, start - dt.timedelta(minutes=14), {f"{key}": "x"})
        self.assertTrue(text.startswith("🔔 Через 14 мин"))
        self.assertTrue(key.endswith(":soon"))
        self.assertEqual(mr.reminder(self.issue, start, start - dt.timedelta(minutes=10), {key: "x"}), (None, None))
        self.assertIn("https://x", text)
        self.assertEqual(mr.reminder(self.issue, start, start - dt.timedelta(hours=30), {}), (None, None))

    def test_allday(self):
        day = dt.datetime(2026, 11, 27, 0, 0, tzinfo=TZ)
        self.assertEqual(mr.reminder(self.issue, day, day - dt.timedelta(hours=13), {}, True), (None, None))
        text, key = mr.reminder(self.issue, day, day - dt.timedelta(hours=11), {}, True)
        self.assertIn("Завтра, 27.11", text)
        text, key = mr.reminder(self.issue, day, day + dt.timedelta(hours=10), {}, True)
        self.assertIn("Сегодня, 27.11", text)


if __name__ == "__main__":
    unittest.main()
