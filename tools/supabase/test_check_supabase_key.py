"""Regression tests for the Supabase key safeguard (check_supabase_key.py and app/build.gradle.kts).

Every key here is synthetic: built in this file, signed with nothing, valid for no project.

    python3 -m unittest discover -s tools/supabase -p "test_*.py"

The Gradle tests configure the real build once per key, so they take a minute and run only when
GOATTV_GRADLE_KEY_TESTS=1 is set.
"""
import base64
import json
import os
import subprocess
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))

from check_supabase_key import key_problem  # noqa: E402


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def legacy_jwt(claims):
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    return f"{header}.{b64url(json.dumps(claims).encode())}.{b64url(b'not-a-real-signature')}"


PUBLISHABLE = "sb_publishable_TESTONLYabcdefghijklmnop_0123456789"
SECRET = "sb_secret_TESTONLYabcdefghijklmnop_0123456789"
ANON_JWT = legacy_jwt({"iss": "supabase", "ref": "testonlyproject", "role": "anon", "iat": 1, "exp": 2})
SERVICE_JWT = legacy_jwt({"iss": "supabase", "ref": "testonlyproject", "role": "service_role", "iat": 1, "exp": 2})
AUTHENTICATED_JWT = legacy_jwt({"iss": "supabase", "role": "authenticated"})
ROLELESS_JWT = legacy_jwt({"iss": "supabase"})
GARBLED_JWT = "eyJhbGciOiJIUzI1NiJ9.!!!not-base64!!!.sig"


def run_checker(key, *args):
    env = {k: v for k, v in os.environ.items() if k != "SUPABASE_ANON_KEY"}
    if key is not None:
        env["SUPABASE_ANON_KEY"] = key
    return subprocess.run([sys.executable, "-I", str(HERE / "check_supabase_key.py"), *args],
                          env=env, capture_output=True, text=True)


class KeyProblemTest(unittest.TestCase):
    def test_publishableAndLegacyAnon_areAccepted(self):
        self.assertIsNone(key_problem(PUBLISHABLE))
        self.assertIsNone(key_problem(ANON_JWT))
        # Padded base64 in the payload is still read.
        header, payload, sig = ANON_JWT.split(".")
        self.assertIsNone(key_problem(f"{header}.{payload}{'=' * (-len(payload) % 4)}.{sig}"))

    def test_secretKey_isRefused(self):
        self.assertIn("sb_secret_", key_problem(SECRET))

    def test_legacyServiceRole_isRefused(self):
        self.assertIn("service_role", key_problem(SERVICE_JWT))

    def test_otherLegacyRolesAndUnreadableTokens_areRefused(self):
        for key in (AUTHENTICATED_JWT, ROLELESS_JWT, GARBLED_JWT):
            with self.subTest(key=key[:12]):
                self.assertIsNotNone(key_problem(key))

    def test_unrecognisedKeys_areRefused(self):
        for key in ("not-a-key", "sb_unknown_abc", "SB_SECRET_abc", "a.b"):
            with self.subTest(key=key):
                self.assertIsNotNone(key_problem(key))


class CheckerCliTest(unittest.TestCase):
    def assertNeverPrints(self, result, key):
        output = result.stdout + result.stderr
        self.assertNotIn(key, output)
        for part in key.split("."):
            if len(part) >= 8:
                self.assertNotIn(part, output)

    def test_acceptedKeys_exitZero(self):
        for key in (PUBLISHABLE, ANON_JWT):
            with self.subTest(key=key[:12]):
                result = run_checker(key)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertNeverPrints(result, key)

    def test_refusedKeys_failWithoutPrintingThem(self):
        for key in (SECRET, SERVICE_JWT, AUTHENTICATED_JWT, "not-a-key"):
            with self.subTest(key=key[:12]):
                result = run_checker(key)
                self.assertEqual(1, result.returncode)
                self.assertIn("::error::", result.stdout)
                self.assertNeverPrints(result, key)

    def test_emptyKey_failsUnlessAllowed(self):
        self.assertEqual(2, run_checker(None).returncode)
        self.assertEqual(2, run_checker("").returncode)
        self.assertEqual(0, run_checker("", "--allow-empty").returncode)


@unittest.skipUnless(os.environ.get("GOATTV_GRADLE_KEY_TESTS") == "1", "set GOATTV_GRADLE_KEY_TESTS=1")
class GradleKeyCheckTest(unittest.TestCase):
    """The same rules hold in app/build.gradle.kts, which every build (debug and release) applies."""

    def configure(self, key):
        gradlew = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
        env = dict(os.environ, SUPABASE_ANON_KEY=key)
        return subprocess.run([str(gradlew), ":app:help", "-q"], cwd=REPO, env=env,
                              capture_output=True, text=True, shell=os.name == "nt")

    def test_acceptedKeys_configure(self):
        for key in (PUBLISHABLE, ANON_JWT):
            with self.subTest(key=key[:12]):
                result = self.configure(key)
                self.assertEqual(0, result.returncode, result.stderr[-2000:])

    def test_refusedKeys_failTheBuildWithoutPrintingThem(self):
        for key in (SECRET, SERVICE_JWT):
            with self.subTest(key=key[:12]):
                result = self.configure(key)
                output = result.stdout + result.stderr
                self.assertNotEqual(0, result.returncode)
                self.assertIn("bypasses Row Level Security", output)
                self.assertNotIn(key, output)
                for part in key.split("."):
                    if len(part) >= 8:
                        self.assertNotIn(part, output)


if __name__ == "__main__":
    unittest.main()
