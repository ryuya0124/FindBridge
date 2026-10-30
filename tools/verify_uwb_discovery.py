#!/usr/bin/env python3
"""Reproduce observed discovery calculations with public synthetic values only.

iOS 27.0.1 / 24A446 nearbyd and bluetoothd static analysis. This is neither a
BLE scanner/advertiser nor an implementation of a working Find My connection.
The framing parser covers CoreBluetooth's SpatialInteraction body, not the
complete BLE advertisement. No account input or radio API is used.
"""

import argparse
import json
from pathlib import Path

from verify_uwb_crypto import AppleCrypto, check, siphash24


IRK = bytes(range(16))
ADDRESS = bytes.fromhex("a0a1a2a3a4a5")
IDENTIFIER = bytes.fromhex("b0b1b2")


def spatial_identifier(irk: bytes, address: bytes, identifier: bytes) -> bytes:
    check(len(irk) == 16 and len(address) == 6 and len(identifier) == 3, "Invalid lengths")
    # bluetoothd 0x10005c894..0x10005c8fc: 9 input bytes; REV before copying 3.
    return siphash24(irk, address + identifier)[::-1][:3]


def no_wake_auth_tag(irk: bytes, address: bytes) -> bytes:
    check(len(irk) == 16 and len(address) == 6, "Invalid lengths")
    # Address-based branch, not a claim about which branch a real phone uses.
    # nearbyd 0x1003df2ac..0x1003df304: original address bytes; low 24 bits.
    return siphash24(irk, address)[:3]


def parse_spatial_body(body: bytes) -> dict:
    """Strict bounds around observed fields; not Apple's partial-error policy.

    Framing corroborated with macOS 27.2 / 26B123 CBDevice's receiver. Unknown
    trailing bytes are reported without being interpreted or used for ranging.
    """
    cursor = 0

    def take(size: int) -> bytes:
        nonlocal cursor
        if cursor + size > len(body):
            raise ValueError("Truncated SpatialInteraction field")
        value = body[cursor:cursor + size]
        cursor += size
        return value

    flags = take(1)[0]
    identifiers = [take(3).hex() for _ in range(flags & 3)]
    extended = bool(flags & 0x80)
    uwb = None
    if flags & 0x08 and (not extended or flags & 0x40):
        uwb = take(5)
    presence_flags = take(1)[0] if extended else None
    presence = take(1) if presence_flags is not None and presence_flags & 1 else None
    return {
        "flags": flags,
        "identifiers": identifiers,
        "uwb": uwb.hex() if uwb is not None else None,
        "presence_flags": presence_flags,
        "presence": presence.hex() if presence is not None else None,
        "uninterpreted_trailing": body[cursor:].hex(),
    }


def parse_finding_config(uwb: bytes, sender_is_finder: bool) -> dict:
    check(len(uwb) >= 5, "CoreBluetooth UWB config needs five bytes")
    if not uwb[0] & 1:
        return {"finding_enabled": False}
    # nearbyd 0x1002cbdd8..0x1002cbdf4, 0x1003e628c..0x1003e634c.
    status, config, config2 = uwb[1:4]
    packed = config if sender_is_finder else config2
    return {
        "finding_enabled": True,
        "can_range": bool(status & 2),
        "technology_1_advertised": bool(status & 4),
        "use_case_code": (status >> 4) & 1,
        "protocol_version": packed & 7 if status & 1 else 0,
        "nb_mask_code": (packed >> 3) & 3 if status & 1 else None,
        "mms_fragment_code": (packed >> 5) & 3 if status & 1 else None,
        "nb_acquisition_index": config if status & 1 and not sender_is_finder else None,
        "uninterpreted_fifth_byte": uwb[4],
    }


def verify() -> dict:
    apple = AppleCrypto()
    spatial = spatial_identifier(IRK, ADDRESS, IDENTIFIER)
    no_wake = no_wake_auth_tag(IRK, ADDRESS)
    check(spatial == apple.siphash(IRK, ADDRESS + IDENTIFIER)[::-1][:3], "Spatial hash")
    check(no_wake == apple.siphash(IRK, ADDRESS)[:3], "NoWake hash")
    static_input = b"StaticSipHashInput"
    check(len(static_input) == 18, "Observed alternative input length")
    static_tag = siphash24(IRK, static_input)[:3]
    check(static_tag == apple.siphash(IRK, static_input)[:3], "NoWake static-input branch")
    check(spatial != no_wake, "Different schemes must remain separate")
    wrong_endian = siphash24(IRK, ADDRESS + IDENTIFIER)[:3]
    check(spatial != wrong_endian, "Synthetic vector must expose the endian difference")

    # Known synthetic bit fields: version=2, mask=3, fragment code=2 -> 0x5a.
    uwb = bytes.fromhex("0117025a00")
    body = b"\x49" + spatial + uwb
    parsed = parse_spatial_body(body)
    expected = {
        "finding_enabled": True, "can_range": True,
        "technology_1_advertised": True, "use_case_code": 1,
        "protocol_version": 2, "nb_mask_code": 3, "mms_fragment_code": 2,
        "nb_acquisition_index": 2, "uninterpreted_fifth_byte": 0,
    }
    check(parse_finding_config(bytes.fromhex(parsed["uwb"]), False) == expected,
          "Findee advertisement field mapping")
    finder = parse_finding_config(bytes.fromhex("01175a0000"), True)
    check(finder["protocol_version"] == 2 and finder["nb_acquisition_index"] is None,
          "Finder payload is not the Findee layout")
    check(parse_finding_config(bytes(5), False) == {"finding_enabled": False},
          "Disabled finding must not become a ranging candidate")
    extended = parse_spatial_body(b"\xc9" + spatial + uwb + b"\x01\x7f")
    check(extended["presence"] == "7f", "Extended presence framing")
    presence_only = parse_spatial_body(b"\x81" + spatial + b"\x01\x7f")
    check(presence_only["uwb"] is None and presence_only["presence"] == "7f",
          "Presence-only packet must not supply UWB config")
    rejected = 0
    for length in range(len(body)):
        try:
            parse_spatial_body(body[:length])
        except ValueError:
            rejected += 1
        else:
            raise RuntimeError(f"Accepted truncated fixture of length {length}")
    rotated = bytes.fromhex("a0a1a2a3a4a6")
    check(spatial_identifier(IRK, rotated, IDENTIFIER) != spatial, "Address rotation")
    return {
        "scope": "Synthetic only; no real keys, discovery, advertising or ranging",
        "source_nearbyd_sha256":
            "4f2702b9e7ab26ac9aab305f15a054965690d40cd6a0e29a6dacdd818d6f0dfa",
        "source_bluetoothd_sha256":
            "cfd94854d0a2efd3ca4a2de6e55349f43fd0d274a4b8bfa9937f29d9f7208cb1",
        "checks": {
            "spatial_hash_compared_with_host_CoreUtils": True,
            "no_wake_hash_compared_with_host_CoreUtils": True,
            "no_wake_static_input_branch_compared_with_host_CoreUtils": True,
            "finder_and_findee_layouts": True,
            "extended_presence_and_presence_only": True,
            "truncated_bodies_rejected": rejected,
            "rotating_address_changes_identifier": True,
        },
        "synthetic": {
            "irk": IRK.hex(), "cb_address_data": ADDRESS.hex(),
            "token_identifier": IDENTIFIER.hex(),
            "spatial_identifier": spatial.hex(), "no_wake_auth_tag": no_wake.hex(),
            "no_wake_static_input_tag": static_tag.hex(),
            "spatial_body_without_manufacturer_header": body.hex(),
            "findee_fields": expected,
        },
    }


def main():
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
