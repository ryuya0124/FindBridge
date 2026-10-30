#!/usr/bin/env python3
"""Inspect owned, local OS binaries without running them or reading account data.

Only hashes and selected symbol names are written. No framework binaries, keys,
device caches or private runtime data are copied into the report.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


INTERESTING = re.compile(
    r"NIFindingConfiguration|NIFindingNotifier|NISession\(Finding\)|"
    r"NIDiscoveryToken\(Finding_Project\)|"
    r"SPPeripheralConnectionMaterial|SPLocalFindableConnectionMaterialMonitoringSession|"
    r"SPBTFindingSession|SPOwnerSession peripheralConnectionMaterial|"
    r"SPBeaconTaskName(?:BeginRanging|EndRanging|FindingExperienceV2)|"
    r"FMIPManager.*rangingParameters|FMIPRangingParameters"
)


def inspect(path: Path) -> dict:
    symbols = subprocess.run(
        ["xcrun", "nm", "-pa", str(path)],
        check=True,
        capture_output=True,
        timeout=30,
    ).stdout
    demangled = subprocess.run(
        ["xcrun", "swift-demangle"],
        input=symbols,
        check=True,
        capture_output=True,
        timeout=30,
    ).stdout.decode("utf-8", errors="replace")
    digest = hashlib.sha256()
    with path.open("rb") as binary:
        for chunk in iter(lambda: binary.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "module": path.name,
        "bytes": path.stat().st_size,
        "sha256": digest.hexdigest(),
        "symbols": [line.strip() for line in demangled.splitlines() if INTERESTING.search(line)],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--ios-symbols", type=Path, required=True,
        help="Xcode DeviceSupport arm64e/Symbols directory",
    )
    parser.add_argument("--version-label", required=True, help="Exact acquired OS version/build")
    parser.add_argument("--mac-findmy", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    library = args.ios_symbols / "System/Library"
    modules = [
        ("Frameworks", "NearbyInteraction"),
        ("PrivateFrameworks", "SPOwner"),
        ("PrivateFrameworks", "SPFinder"),
        ("PrivateFrameworks", "FMIPCore"),
    ]
    report = {
        "version": args.version_label,
        "method": "Static symbols only; no live discovery, radio commands or account data",
        "ios": [inspect(library / kind / f"{name}.framework" / name) for kind, name in modules],
    }
    if args.mac_findmy is not None:
        report["mac"] = inspect(args.mac_findmy)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Inspected {len(report['ios'])} iOS modules; report: {args.output}")


if __name__ == "__main__":
    main()
