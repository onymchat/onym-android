#!/usr/bin/env python3
"""
Static lint: prevent secret material from crossing into the transport
layer unsealed.

Default-deny + `// onym:allow-secret-transport` opt-out. Catches a
realistic regression class — a refactor accidentally plumbs
`groupSecret` through to `NostrInboxTransport.send(...)` instead of
the sealed bytes. The cryptographic gate (secrets must enter
`IdentityRepository.sealInvitation` before the transport sees them)
is the real protection; this script is the static reminder.

Two rules:

1. **Transport barrier**: identity / per-dialog / group secret names
   may not appear (as identifiers) inside
   `app/src/main/kotlin/app/onym/android/transport/`. Every match
   in production code under that path is a violation unless suppressed.

2. **Send-arg shape**: `<anything>.send(<arg>, ...)` calls must not
   pass an argument whose name is one of the known secret names. The
   transport's `send` takes opaque bytes (`payload: ByteArray`) — if
   the variable holding those bytes is named `secret` / `groupSecret`
   / `recoveryPhrase` / etc., that's almost certainly the unsealed
   secret going on the wire.

Suppression: a line containing `// onym:allow-secret-transport`,
either on the violating line itself or in the contiguous block of
`//`-prefixed comment lines directly above. Annotate the suppression
with a justification — every one is a small trust delegation.

Ported from `onym-ios/scripts/lint-no-secret-transport.py`.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# Names of identity / per-dialog / group secrets — variables holding
# any of these MUST be sealed before crossing the transport barrier.
IDENTITY_SECRET_NAMES: set[str] = {
    "blsSecretKey",
    "nostrSecretKey",
    "inboxKeyAgreementPrivateKey",
    "stellarSigningPrivateKey",
    "peerBlsSecret",
    "peerBlsSecretKey",
    "groupSecret",
    "introPrivateKey",
    "recoveryPhrase",
    "mnemonic",
    "entropy",
    "blsSecret",
    "nostrSecret",
}

# Send-arg shape: extra generic names that strongly suggest secret
# bytes when passed positionally to `*.send(arg, …)`.
SEND_ARG_FORBIDDEN: set[str] = IDENTITY_SECRET_NAMES | {
    "secretKey",
    "privateKey",
    "phrase",
    "seed",
    "secret",
}

# Production-only barrier — tests are free to construct secret
# fixtures and call into the transport layer with raw bytes.
TRANSPORT_BARRIER_DIR = "app/src/main/kotlin/app/onym/android/transport/"
SUPPRESSION = "onym:allow-secret-transport"

COMMENT_LINE = re.compile(r"^\s*//")
SEND_CALL_RE = re.compile(
    r"\b\w+\.send\(\s*"
    r"(?P<arg>\w+)"
    r"\s*,"
)


def is_suppressed(lines: list[str], index: int) -> bool:
    """True when the violation on `lines[index]` (1-based) is
    suppressed by an `// onym:allow-secret-transport` annotation on
    the violating line itself or anywhere in the contiguous block of
    `//`-prefixed lines directly above."""
    line = lines[index - 1]
    if SUPPRESSION in line:
        return True
    j = index - 2
    while j >= 0 and COMMENT_LINE.match(lines[j]):
        if SUPPRESSION in lines[j]:
            return True
        j -= 1
    return False


def is_comment_line(line: str) -> bool:
    s = line.lstrip()
    return s.startswith("//") or s.startswith("/*") or s.startswith("*")


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    sources = sorted(
        root.glob("app/src/main/kotlin/app/onym/android/**/*.kt"),
    )

    violations: list[tuple[str, int, str, str]] = []

    for f in sources:
        rel = f.relative_to(root).as_posix()
        lines = f.read_text(encoding="utf-8").splitlines()

        # Rule 1 — transport-barrier.
        if rel.startswith(TRANSPORT_BARRIER_DIR):
            for i, line in enumerate(lines, start=1):
                if is_comment_line(line):
                    continue
                for name in IDENTITY_SECRET_NAMES:
                    if not re.search(rf"\b{re.escape(name)}\b", line):
                        continue
                    if is_suppressed(lines, i):
                        continue
                    violations.append((
                        rel, i,
                        f"transport-barrier: secret name '{name}' inside {TRANSPORT_BARRIER_DIR}",
                        line.rstrip(),
                    ))

        # Rule 2 — send-arg shape (applies to every file, not just
        # transport/).
        for i, line in enumerate(lines, start=1):
            if is_comment_line(line):
                continue
            for m in SEND_CALL_RE.finditer(line):
                arg = m.group("arg")
                if arg not in SEND_ARG_FORBIDDEN:
                    continue
                if is_suppressed(lines, i):
                    continue
                violations.append((
                    rel, i,
                    f"transport-arg: send() called with secret-named arg '{arg}' "
                    f"(must be sealed bytes)",
                    line.rstrip(),
                ))

    if violations:
        for rel, i, desc, line in violations:
            print(f"{rel}:{i}: {desc}")
            print(f"    {line}")
        print()
        print(f"Found {len(violations)} secret-leak violation(s).")
        print(f"Allow with `// {SUPPRESSION}` on the line or directly above.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
