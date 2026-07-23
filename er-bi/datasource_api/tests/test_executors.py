import unittest
from unittest.mock import patch

from fastapi import HTTPException

from app.core.config import settings
from app.executors import http, python, sql


CONNECTOR = {
    "id": 1,
    "name": "test",
    "dbType": "MySQL",
    "host": "127.0.0.1",
    "port": 3306,
    "database": "test",
    "username": "test",
    "password": "test",
    "status": 1,
}


class ExecutorTests(unittest.TestCase):
    def test_python_runs_in_child_process(self):
        self.assertEqual(python.execute("result = sum([1, 2, 3])", CONNECTOR), 6)

    def test_python_timeout_terminates_child(self):
        previous = settings.PYTHON_TIMEOUT_SECONDS
        settings.PYTHON_TIMEOUT_SECONDS = 0.2
        try:
            with self.assertRaises(HTTPException) as raised:
                python.execute("while True:\n    pass", CONNECTOR)
            self.assertEqual(raised.exception.status_code, 408)
        finally:
            settings.PYTHON_TIMEOUT_SECONDS = previous

    def test_python_query_uses_parent_broker(self):
        with patch.object(python, "query", return_value=[{"value": 1}]) as mocked:
            result = python.execute(
                'def main(connector, query, params):\n    return query("SELECT 1")',
                CONNECTOR,
            )
        self.assertEqual(result, [{"value": 1}])
        mocked.assert_called_once_with(CONNECTOR, "SELECT 1", None)

    def test_http_private_address_policy(self):
        previous = settings.HTTP_ALLOW_PRIVATE_NETWORKS
        settings.HTTP_ALLOW_PRIVATE_NETWORKS = False
        try:
            with self.assertRaises(HTTPException):
                http._validate_url("http://127.0.0.1/private")
        finally:
            settings.HTTP_ALLOW_PRIVATE_NETWORKS = previous

    def test_sql_read_only_policy(self):
        previous = settings.SQL_READ_ONLY
        settings.SQL_READ_ONLY = True
        try:
            with self.assertRaises(HTTPException):
                sql._prepare("DELETE FROM users")
            prepared, _ = sql._prepare("SELECT 1")
            self.assertEqual(prepared, "SELECT 1")
        finally:
            settings.SQL_READ_ONLY = previous


if __name__ == "__main__":
    unittest.main()
