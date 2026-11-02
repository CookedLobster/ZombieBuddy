#!/usr/bin/env python3
"""Verify the Ed25519 signature on authors.json.

The signed payload is "ZBAuthors:" + sha256hex(canonical), where canonical is
the raw file with the signature value replaced by an empty string. The public
key is read from KnownAuthors.java so the CI check follows the runtime trust
anchor instead of keeping a second copy.
"""

from __future__ import annotations

import hashlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
KNOWN_AUTHORS = REPO_ROOT / "java/src/main/java/me/zed_0xff/zombie_buddy/KnownAuthors.java"
SIGNATURE_RE = re.compile(rb'("signature"\s*:\s*")([0-9a-fA-F]*)(")')
PUBKEY_RE = re.compile(r'AUTHORS_PUBKEY_HEX\s*=\s*"([0-9a-fA-F]{64})"')
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")


def main(argv: list[str]) -> int:
    authors_path = Path(argv[1] if len(argv) > 1 else "authors.json")
    if not authors_path.is_absolute():
        authors_path = REPO_ROOT / authors_path
    body = authors_path.read_bytes()

    match = SIGNATURE_RE.search(body)
    if match is None:
        print("authors.json: no signature field", file=sys.stderr)
        return 1
    signature_hex = match.group(2).decode("ascii")
    if len(signature_hex) != 128:
        print("authors.json: signature must be 128 hex characters", file=sys.stderr)
        return 1

    source = KNOWN_AUTHORS.read_text(encoding="utf-8")
    pubkey_match = PUBKEY_RE.search(source)
    if pubkey_match is None:
        print(f"{KNOWN_AUTHORS}: AUTHORS_PUBKEY_HEX not found", file=sys.stderr)
        return 1
    pubkey = bytes.fromhex(pubkey_match.group(1))

    canonical = body[: match.start()] + match.group(1) + match.group(3) + body[match.end() :]
    message = b"ZBAuthors:" + hashlib.sha256(canonical).hexdigest().encode("ascii")
    signature = bytes.fromhex(signature_hex)

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        pubkey_path = tmp_path / "authors-pub.der"
        message_path = tmp_path / "authors-message.bin"
        signature_path = tmp_path / "authors-signature.bin"
        pubkey_path.write_bytes(ED25519_SPKI_PREFIX + pubkey)
        message_path.write_bytes(message)
        signature_path.write_bytes(signature)
        result = subprocess.run(
            [
                "openssl",
                "pkeyutl",
                "-verify",
                "-pubin",
                "-inkey",
                str(pubkey_path),
                "-keyform",
                "DER",
                "-rawin",
                "-in",
                str(message_path),
                "-sigfile",
                str(signature_path),
            ],
            check=False,
            capture_output=True,
            text=True,
        )

    if result.returncode != 0:
        print("authors.json: signature verification failed", file=sys.stderr)
        if result.stdout.strip():
            print(result.stdout.strip(), file=sys.stderr)
        if result.stderr.strip():
            print(result.stderr.strip(), file=sys.stderr)
        return 1

    print(f"{authors_path}: Ed25519 signature verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
