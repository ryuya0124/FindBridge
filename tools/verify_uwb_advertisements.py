#!/usr/bin/env python3
"""Compare fixed BLE manufacturer fixtures with the host's in-memory CBDevice.

No manager, scan, advertisement, account data, connection or ranging is used.
The Android parser deliberately rejects truncation instead of retaining Apple's
partial-error state; this comparison covers complete synthetic packets only.
"""

import argparse
import json
import platform
import subprocess
import tempfile
from pathlib import Path

from verify_uwb_crypto import check
from verify_uwb_discovery import parse_spatial_body


def parse_manufacturer(data: bytes) -> dict:
    result = {
        "spatial_identifiers": [],
        "uwb_config": None,
        "presence": None,
        "no_wake_auth_tag": None,
        "no_wake_config": None,
    }
    cursor = 0
    while cursor < len(data):
        if len(data) - cursor < 2:
            raise ValueError("Truncated manufacturer header")
        kind, length = data[cursor], data[cursor + 1] & 31
        cursor += 2
        if length > len(data) - cursor:
            raise ValueError("Truncated manufacturer body")
        body = data[cursor : cursor + length]
        cursor += length
        if kind == 0x13:
            spatial = parse_spatial_body(body)
            result["spatial_identifiers"] = spatial["identifiers"]
            result["uwb_config"] = spatial["uwb"]
            result["presence"] = spatial["presence"]
        elif kind == 0x1A:
            if not body:
                raise ValueError("Missing NoWake version")
            if body[0] != 1:
                continue
            if len(body) < 5 or body[1] & 1 and len(body) < 6:
                raise ValueError("Truncated NoWake body")
            result["no_wake_auth_tag"] = body[2:5].hex()
            result["no_wake_config"] = body[5:6].hex() if body[1] & 1 else None
    return result


def run(*arguments: str) -> str:
    return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout


def verify() -> dict:
    check(
        platform.system() == "Darwin" and platform.machine() == "arm64",
        "Host parser verification requires macOS arm64",
    )
    source = Path(__file__).resolve().parent / "uwb-finding-token/advertisement_probe.m"
    with tempfile.TemporaryDirectory(prefix="findbridge-advertisement-") as directory:
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
    check(len(rows) == 8, "Unexpected fixed manufacturer fixture count")
    for row in rows:
        expected = parse_manufacturer(bytes.fromhex(row["manufacturer_data"]))
        check(
            {key: row[key] for key in expected} == expected,
            "CoreBluetooth manufacturer parsing differs",
        )
    # At least one positive fixture must expose each route, and type 0x1c must
    # not accidentally stand in for SpatialInteraction (its on-air type is 0x13).
    check(rows[0]["spatial_identifiers"] == ["06aba9"], "Spatial route missing")
    check(rows[4]["no_wake_auth_tag"] == "eabec2", "NoWake route missing")
    check(not rows[6]["spatial_identifiers"], "Unrelated type became Spatial")
    return {
        "scope": "Fixed synthetic manufacturer data; no radio or account access",
        "host_build": run("sw_vers", "-buildVersion").strip(),
        "complete_packets_matched": len(rows),
        "host_fixtures": rows,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify()
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
