#!/usr/bin/env python3
"""Refuses a Supabase key that must never be built into the APK.

The key in SUPABASE_ANON_KEY ships inside every APK, and anyone can unzip one and read it. Only a key
that Row Level Security limits is safe there:
  - accepted: a publishable key (sb_publishable_...) or a legacy anon key (a JWT whose role is "anon");
  - refused:  a secret key (sb_secret_...), a legacy service_role JWT, and anything unrecognised.
A secret or service_role key bypasses RLS, so shipping one would give every user write access to the
whole database.

The key is read from the environment, never from the command line (where process listings show it),
and no message ever includes it or any part of it. app/build.gradle.kts applies the same rules
(supabaseKeyProblem) to every build; keep the two in step.

Usage: SUPABASE_ANON_KEY=... python3 tools/supabase/check_supabase_key.py [--allow-empty]
Exit status: 0 = safe to ship, 1 = refused, 2 = empty (unless --allow-empty).
"""
import base64
import binascii
import json
import os
import sys

ENV_NAME = "SUPABASE_ANON_KEY"


def key_problem(key):
    """None when the key is safe to ship in an APK, else a description that never contains the key."""
    if key.startswith("sb_publishable_"):
        return None
    if key.startswith("sb_secret_"):
        return "is a secret key (sb_secret_), which bypasses Row Level Security"
    parts = key.split(".")
    if len(parts) == 3:
        role = _jwt_role(parts[1])
        if role == "anon":
            return None
        if role == "service_role":
            return "is a legacy service_role key, which bypasses Row Level Security"
        if role is None:
            return "looks like a legacy key, but its role cannot be read"
        return "is a legacy key whose role is not anon"
    return "is neither a publishable key (sb_publishable_) nor a legacy anon key"


def _jwt_role(payload):
    try:
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    except (binascii.Error, ValueError, UnicodeDecodeError):
        return None
    role = claims.get("role") if isinstance(claims, dict) else None
    return role if isinstance(role, str) else None


def main(argv):
    key = os.environ.get(ENV_NAME, "")
    if not key:
        if "--allow-empty" in argv:
            return 0
        print(f"::error::{ENV_NAME} is not set.")
        return 2
    problem = key_problem(key)
    if problem:
        print(f"::error::{ENV_NAME} {problem}. Only a publishable key or a legacy anon key may be "
              "built into the APK. The key itself is not printed.")
        return 1
    print(f"{ENV_NAME}: accepted (publishable or legacy anon key).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
