import unittest

from app.core import connector_secrets
from app.core.config import settings


class ConnectorSecretTests(unittest.TestCase):
    def test_encrypt_and_decrypt(self):
        previous = settings.CONNECTOR_SECRET_KEY
        settings.CONNECTOR_SECRET_KEY = "unit-test-key"
        try:
            encrypted = connector_secrets.encrypt("secret-password")
            self.assertTrue(encrypted.startswith(connector_secrets.PREFIX))
            self.assertNotIn("secret-password", encrypted)
            self.assertEqual(connector_secrets.decrypt(encrypted), "secret-password")
        finally:
            settings.CONNECTOR_SECRET_KEY = previous

    def test_plaintext_rows_remain_readable_for_migration(self):
        self.assertEqual(connector_secrets.decrypt("legacy-password"), "legacy-password")


if __name__ == "__main__":
    unittest.main()
