#!/usr/bin/env python3
"""Reproduce inspected finding-token fields and rotation with public fixtures.

The host probe calls only NIDiscoveryToken's inspected in-memory factory and
getters. No NISession, daemon, messaging, account, Keychain or radio API is used.
The bucket model is inferred from static code, not an executed daemon function.
"""

import argparse
import json
import math
import platform
import subprocess
import tempfile
from pathlib import Path

from verify_uwb_crypto import SHARED_SECRET, TOKEN_SALT, AppleCrypto, check, hkdf_sha256

REFERENCE_UNIX_OFFSET = 978_307_200.0  # Foundation's 2001-01-01 UTC epoch.
DEFAULT_MINUTES = 10_080
FIXED_IDENTIFIER = bytes.fromhex("123456")


def bucket(base: float, date: float, minutes: int = DEFAULT_MINUTES) -> dict:
    """Static model of 26B123 sub_100404214; inputs are seconds since 2001 UTC."""
    check(math.isfinite(base) and math.isfinite(date), "Finite fixture dates required")
    check(
        isinstance(minutes, int) and minutes > 0, "Positive fixture interval required"
    )
    duration = float(minutes) * 60
    anchor = math.floor(base / duration) * duration
    index = int((max(base, date) - anchor) / duration) + 1
    check(0 < index < 1 << 64, "Bucket index exceeds UInt64")
    start = anchor + duration * (index - 1)
    boundary_unix = REFERENCE_UNIX_OFFSET + start + duration
    return {
        "index": index,
        "anchor_reference_seconds": anchor,
        "start_reference_seconds": start,
        # The inspected code steps one representable Unix-time Double below the
        # next boundary. Subtracting a whole millisecond would change behavior.
        "end_unix_seconds": math.nextafter(boundary_unix, -math.inf),
        "next_boundary_unix_seconds": boundary_unix,
    }


def finding_token_raw(irk: bytes) -> bytes:
    """Observed OPACK for {1: Data(12 34 56), 7: 1, 2: Data(16-byte IRK)}.

    This narrow encoder is not NSSecureCoding, a BLE advertisement, an OOB
    configuration message, or proof that the recipient accepts a connection.
    """
    check(len(irk) == 16, "Finding-token IRK must contain 16 bytes")
    return bytes.fromhex("e30973") + FIXED_IDENTIFIER + bytes.fromhex("0f090a80") + irk


def run(*arguments: str) -> str:
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout


def verify() -> dict:
    check(
        platform.system() == "Darwin" and platform.machine() == "arm64",
        "Host finding-token verification requires macOS arm64",
    )
    source = Path(__file__).resolve().parent / "uwb-finding-token/token_factory_probe.m"
    with tempfile.TemporaryDirectory(prefix="findbridge-finding-token-") as directory:
        executable = Path(directory) / "probe"
        run(
            "xcrun",
            "clang",
            "-arch",
            "arm64",
            "-fobjc-arc",
            "-framework",
            "Foundation",
            str(source),
            "-o",
            str(executable),
        )
        rows = json.loads(run(str(executable)))

    expected_irks = [bytes(range(16)), bytes(16), bytes([255]) * 16]
    check(len(rows) == len(expected_irks), "Unexpected fixed token fixtures")
    for row, irk in zip(rows, expected_irks, strict=True):
        check(row["irk"] == irk.hex(), "Host factory changed the IRK")
        check(
            row["identifier"] == FIXED_IDENTIFIER.hex(),
            "Host session identifier differs",
        )
        check(row["variant"] == 1, "Host token variant differs")
        check(
            row["raw_opack"] == finding_token_raw(irk).hex(), "OPACK encoding differs"
        )

    duration = DEFAULT_MINUTES * 60
    cases = [
        (123.5, 123.5, DEFAULT_MINUTES, 1, 0),
        (123.5, -500.0, DEFAULT_MINUTES, 1, 0),
        (123.5, duration - 1.0, DEFAULT_MINUTES, 1, 0),
        (123.5, float(duration), DEFAULT_MINUTES, 2, duration),
        (duration + 123.5, 2.0 * duration, DEFAULT_MINUTES, 2, 2 * duration),
        (-1.0, -1.0, DEFAULT_MINUTES, 1, -duration),
        (-1.0, 0.0, DEFAULT_MINUTES, 2, 0),
        (123.5, 180.0, 1, 2, 180),
    ]
    apple = AppleCrypto()
    vectors = []
    for base, date, minutes, index, start in cases:
        result = bucket(base, date, minutes)
        check(result["index"] == index, "Unexpected bucket index")
        check(result["start_reference_seconds"] == start, "Unexpected bucket start")
        check(
            result["end_unix_seconds"] < result["next_boundary_unix_seconds"],
            "End must precede the next boundary",
        )
        info = index.to_bytes(8, "little")
        irk = hkdf_sha256(SHARED_SECRET, info, TOKEN_SALT, 16)
        check(
            irk == apple.derive(SHARED_SECRET, info, TOKEN_SALT, 16),
            "Token KDF mismatch",
        )
        vectors.append(
            {
                "base_reference_seconds": base,
                "date_reference_seconds": date,
                "minutes": minutes,
                **result,
                "info": info.hex(),
                "synthetic_irk": irk.hex(),
            }
        )
    invalid = 0
    for size in (0, 3, 15, 17, 32):
        try:
            finding_token_raw(bytes(size))
        except RuntimeError:
            invalid += 1
        else:
            raise RuntimeError("Accepted an invalid fixture IRK size")
    return {
        "scope": "Fixed public inputs only; no account material, messaging or ranging",
        "host_build": run("sw_vers", "-buildVersion").strip(),
        "checks": {
            "host_token_factories_matched": len(rows),
            "static_bucket_model_fixtures": len(cases),
            "bucket_hkdf_commoncrypto_matches": len(vectors),
            "invalid_irk_lengths_rejected": invalid,
        },
        "host_token_fixtures": rows,
        "static_bucket_model_fixtures": vectors,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = verify()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report["checks"], indent=2))


if __name__ == "__main__":
    main()
