#!/usr/bin/env python3
"""
Static check: forbid reads of identity secrets outside IdentityRepository.

The mnemonic (BIP39 recovery phrase) and the persisted private key
bytes (nostr secp256k1, BLS12-381) are owned by `IdentityRepository`.
Any code outside the repository that reads them — even in passing —
invites the secret to leak through logs, crash reports, screenshots,
third-party SDK call sites, or accidental serialization.

Default-deny. To allow a specific read, annotate the line itself or
any `//` comment line in the contiguous block directly above with
`// onym:allow-secret-read`. Each suppression should justify itself
in code review and ideally name a reason inline:

    // Rendered behind biometric on backup screen — production reveal
    // UI owns the gating, this view just renders.
    // onym:allow-secret-read
    val phrase = identity.recoveryPhrase

Usage:
    python3 scripts/lint-secrets.py
Exits 0 on success, 1 on any unsuppressed violation.

Ported 1:1 from `onym-ios/scripts/lint-secrets.py`. Differences:

- File globs are `**/*.kt` and `**/*.kts` (skipping `build/`,
  `.gradle/`, and `**/generated/`).
- Allowlist paths follow the Android module layout
  (`app/src/{main,androidTest}/kotlin/...`).
- Adds a destructuring check: `val (entropy, …) = thing` binds the
  destructured value to a variable named `entropy` (or any other
  secret-field name), which is the only Kotlin-specific way to read
  these fields without going through `.fieldName` syntax. iOS
  doesn't have this hole because Swift doesn't destructure structs
  the same way.

Known holes (deliberate gaps, flag in code review):

- Reflection: `identity::class.memberProperties.forEach { println(it.get(identity)) }`
  bypasses both regexes. Not worth catching with a static linter.
- Renamed destructuring: `val (a, b, c) = storedSnapshot` with no
  secret-named binding bypasses the destructuring regex but still
  reads the fields. Caller intent is the same as `.entropy` etc.,
  but the linter only flags the obvious case.
- IDE-generated code (e.g. data class `copy()` overrides) that the
  developer hand-edits to read fields. Out of scope.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# Files allowed to construct / inspect secrets. Adding to this list
# requires a justification in code review — these are the only files
# that legitimately need to touch raw secret material.
ALLOWED: set[str] = {
    "app/src/main/kotlin/app/onym/android/identity/IdentityRepository.kt",
    "app/src/main/kotlin/app/onym/android/identity/StoredSnapshot.kt",
    "app/src/main/kotlin/app/onym/android/identity/Identity.kt",
    "app/src/androidTest/kotlin/app/onym/android/identity/IdentityRepositoryTest.kt",
}

# Field-access patterns that read identity secrets. The `.` prefix
# means we only catch member access (not declarations or unrelated
# local variables that happen to share a name).
PATTERNS: list[tuple[str, str]] = [
    (r"\.nostrSecretKey\b", "nostr secp256k1 secret key"),
    (r"\.blsSecretKey\b",   "BLS12-381 secret key"),
    (r"\.recoveryPhrase\b", "BIP39 recovery phrase (mnemonic)"),
    (r"\.entropy\b",        "BIP39 entropy bytes"),
]

# Destructuring with one of our secret field names as a binding —
# `val (entropy, nostrSk, blsSk) = snapshot` reads all three fields
# without touching `.fieldName` syntax. We catch the case where the
# binding is named like the field (intent is clear); a developer
# renaming `(entropy, …)` to `(a, …)` to dodge the linter is in
# bad faith and out of scope.
DESTRUCTURING = re.compile(
    r"\bval\s*\([^)]*\b(?:nostrSecretKey|blsSecretKey|recoveryPhrase|entropy)\b[^)]*\)\s*="
)

SUPPRESSION = "onym:allow-secret-read"
COMMENT_LINE = re.compile(r"^\s*//")


def is_suppressed(lines: list[str], index: int) -> bool:
    """True if the violation on `lines[index]` (1-based) is suppressed.

    A suppression is honoured if `SUPPRESSION` appears on the violating
    line itself, or anywhere in the contiguous block of `//`-prefixed
    comment lines directly above it.
    """
    line = lines[index - 1]
    if SUPPRESSION in line:
        return True
    j = index - 2
    while j >= 0 and COMMENT_LINE.match(lines[j]):
        if SUPPRESSION in lines[j]:
            return True
        j -= 1
    return False


# Path components anywhere in the relative path that should be skipped:
# Gradle build outputs, Gradle daemon scratch, kotlinx.serialization /
# AGP-generated sources.
SKIP_DIR_COMPONENTS = {"build", ".gradle", "generated"}


def is_skipped(path: Path, root: Path) -> bool:
    rel = path.relative_to(root).parts
    return any(part in SKIP_DIR_COMPONENTS for part in rel)


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    sources = sorted(
        list(root.glob("**/*.kt")) + list(root.glob("**/*.kts"))
    )

    violations: list[tuple[str, int, str, str]] = []
    for file in sources:
        if is_skipped(file, root):
            continue

        rel = file.relative_to(root).as_posix()
        if rel in ALLOWED:
            continue

        lines = file.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines, start=1):
            for pattern, desc in PATTERNS:
                if not re.search(pattern, line):
                    continue
                if is_suppressed(lines, i):
                    continue
                violations.append((rel, i, desc, line.rstrip()))
            if DESTRUCTURING.search(line):
                if not is_suppressed(lines, i):
                    violations.append(
                        (rel, i, "destructuring binds a secret field name", line.rstrip())
                    )

    if violations:
        for rel, i, desc, line in violations:
            print(f"{rel}:{i}: forbidden secret read ({desc})")
            print(f"    {line}")
        print()
        print(f"Found {len(violations)} secret-read violation(s).")
        print(f"Allow with `// {SUPPRESSION}` on the line or directly above.")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
