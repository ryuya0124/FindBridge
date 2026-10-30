#!/usr/bin/env python3
"""Confirm bucket-index encoding and synthetic HKDF values on macOS arm64.

Compiles a pure integer-data getter probe into a temporary directory. The probe
uses four fixed public integers only, never account material or radio APIs.
Private ABI findings apply to the exact host build recorded in the research doc.
"""

import argparse
import json
import platform
from pathlib import Path
import subprocess
import tempfile

from verify_uwb_crypto import AppleCrypto, SHARED_SECRET, TOKEN_SALT, check, hkdf_sha256


def run(*arguments: str) -> str:
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout.strip()


def verify() -> dict:
    check(platform.system() == "Darwin" and platform.machine() == "arm64",
          "This verification requires macOS arm64")
    source = Path(__file__).resolve().parent / "uwb-token-index"
    with tempfile.TemporaryDirectory(prefix="findbridge-integer-probe-") as directory:
        work = Path(directory)
        reference = work / "reference.o"
        probe = work / "probe.o"
        executable = work / "probe"
        run("xcrun", "swiftc", "-parse-as-library", "-emit-object",
            str(source / "reference.swift"), "-o", str(reference))
        run("xcrun", "clang", "-arch", "arm64", "-c", str(source / "probe.c"), "-o", str(probe))
        run("xcrun", "swiftc", str(probe), str(reference), "-o", str(executable))
        runtime = run(str(executable))

    apple = AppleCrypto()
    vectors = []
    for index in (0, 1, 0x0102030405060708, (1 << 64) - 1):
        info = index.to_bytes(8, "little")
        derived = hkdf_sha256(SHARED_SECRET, info, TOKEN_SALT, 16)
        check(
            derived == apple.derive(SHARED_SECRET, info, TOKEN_SALT, 16),
            "Bucket HKDF mismatch",
        )
        vectors.append({"index": str(index), "info": info.hex(), "derived": derived.hex()})
    check(
        vectors[1]["derived"] != vectors[2]["derived"],
        "Different indices must be distinguishable",
    )
    return {
        "scope": "Fixed synthetic inputs only; no account access, discovery or ranging",
        "host_build": run("sw_vers", "-buildVersion"),
        "runtime_probe": runtime.splitlines(),
        "checks": {"native_getter_fixtures": 4, "hkdf_commoncrypto_matches": len(vectors)},
        "synthetic": {
            "shared_secret": SHARED_SECRET.hex(),
            "salt": TOKEN_SALT.decode(),
            "vectors": vectors,
        },
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
