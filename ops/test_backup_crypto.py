import json
from pathlib import Path
import secrets
import subprocess
import unittest

HELPER = Path(__file__).resolve().parent / "backup_crypto.js"


def transform(action, data, key):
    header = json.dumps({"action": action, "keyHex": key}).encode() + b"\n"
    return subprocess.run(["node", str(HELPER)], input=header + data, capture_output=True)


class BackupCryptoTests(unittest.TestCase):
    def test_aes_gcm_roundtrip_does_not_store_plaintext(self):
        key = secrets.token_hex(32)
        plain = "테스트용 개인 집중 기록".encode() * 5
        encrypted = transform("encrypt", plain, key)
        self.assertEqual(encrypted.returncode, 0)
        self.assertTrue(encrypted.stdout.startswith(b"STINTBK1"))
        self.assertNotIn(plain, encrypted.stdout)
        self.assertEqual(transform("decrypt", encrypted.stdout, key).stdout, plain)

    def test_tampered_ciphertext_or_wrong_key_emits_no_plaintext(self):
        key = secrets.token_hex(32)
        encrypted = transform("encrypt", b"private focus records", key).stdout
        tampered = bytearray(encrypted)
        tampered[-1] ^= 1
        for data, decrypt_key in [(bytes(tampered), key), (encrypted, secrets.token_hex(32))]:
            result = transform("decrypt", data, decrypt_key)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b"")


if __name__ == "__main__":
    unittest.main()
