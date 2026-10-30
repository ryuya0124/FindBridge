#!/usr/bin/env python3
"""Verify static UWB crypto findings with synthetic data, without radio/account access.

macOS only: compares Python HKDF/SipHash with CommonCrypto/CoreUtils and checks
AES-CCM against RFC 3610 vector 1. Does not load or execute the iOS daemon.
All keys and challenges below are public test literals. This is research code,
not an implementation of a working Find My connection or an Android STS key.
"""

import argparse
import ctypes as c
import hmac
import json
from pathlib import Path
import struct
import sys


MASK64 = (1 << 64) - 1
IRK = bytes(range(16))
SESSION_ID = bytes.fromhex("1011121314151617")
FINDER_CHALLENGE = bytes.fromhex("202122")
FINDEE_CHALLENGE = bytes.fromhex("303132")
ADDRESS_INPUT = bytes.fromhex("0123456789abcdef")
SHARED_SECRET = bytes(range(0xA0, 0xC0))
TOKEN_SALT = b"NITokenService.salt"


def check(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def hkdf_sha256(ikm: bytes, info: bytes, salt: bytes = b"", length: int = 32) -> bytes:
    """Observed HKDF invocations need only the first SHA-256 expansion block."""
    check(0 < length <= 32, "Unsupported synthetic HKDF output length")
    prk = hmac.digest(salt or bytes(32), ikm, "sha256")
    return hmac.digest(prk, info + b"\x01", "sha256")[:length]


def siphash24(key: bytes, message: bytes) -> bytes:
    """64-bit SipHash-2-4, serialized in the daemon's little-endian order."""
    check(len(key) == 16, "SipHash requires a 16-byte test key")
    k0, k1 = struct.unpack("<QQ", key)
    state = [
        0x736F6D6570736575 ^ k0,
        0x646F72616E646F6D ^ k1,
        0x6C7967656E657261 ^ k0,
        0x7465646279746573 ^ k1,
    ]

    def rotate(value: int, count: int) -> int:
        return ((value << count) | (value >> (64 - count))) & MASK64

    def rounds(count: int) -> None:
        for _ in range(count):
            state[0] = (state[0] + state[1]) & MASK64
            state[1] = rotate(state[1], 13) ^ state[0]
            state[0] = rotate(state[0], 32)
            state[2] = (state[2] + state[3]) & MASK64
            state[3] = rotate(state[3], 16) ^ state[2]
            state[0] = (state[0] + state[3]) & MASK64
            state[3] = rotate(state[3], 21) ^ state[0]
            state[2] = (state[2] + state[1]) & MASK64
            state[1] = rotate(state[1], 17) ^ state[2]
            state[2] = rotate(state[2], 32)

    end = len(message) - len(message) % 8
    words = [int.from_bytes(message[i:i + 8], "little") for i in range(0, end, 8)]
    words.append(((len(message) & 255) << 56) | int.from_bytes(message[end:], "little"))
    for word in words:
        state[3] ^= word
        rounds(2)
        state[0] ^= word
    state[2] ^= 255
    rounds(4)
    return struct.pack("<Q", state[0] ^ state[1] ^ state[2] ^ state[3])


class AppleCrypto:
    """Calls only the host's pure crypto primitives with synthetic inputs."""

    def __init__(self) -> None:
        check(sys.platform == "darwin", "This verification requires macOS")
        self.cc = c.CDLL("/usr/lib/libSystem.B.dylib")
        self.cu = c.CDLL("/System/Library/PrivateFrameworks/CoreUtils.framework/CoreUtils")
        pointer = c.c_void_p
        size = c.c_size_t
        uint = c.c_uint32
        signatures = {
            "CCKDFParametersCreateHkdf": [c.POINTER(pointer), pointer, size, pointer, size],
            "CCDeriveKey": [pointer, uint, pointer, size, pointer, size],
            "CCKDFParametersDestroy": [pointer],
            "CCCryptorCreateWithMode": [
                uint, uint, uint, uint, pointer, pointer, size, pointer,
                size, c.c_int, uint, c.POINTER(pointer),
            ],
            "CCCryptorAddParameter": [pointer, uint, pointer, size],
            "CCCryptorUpdate": [pointer, pointer, size, pointer, size, c.POINTER(size)],
            "CCCryptorFinal": [pointer, pointer, size, c.POINTER(size)],
            "CCCryptorGetParameter": [pointer, uint, pointer, c.POINTER(size)],
            "CCCryptorRelease": [pointer],
        }
        for name, arguments in signatures.items():
            function = getattr(self.cc, name)
            function.argtypes = arguments
            function.restype = None if name.endswith("Destroy") else c.c_int
        self.cu.SipHash.argtypes = [pointer, pointer, size]
        self.cu.SipHash.restype = c.c_uint64

    @staticmethod
    def status(value: int) -> None:
        check(value == 0, f"CommonCrypto failed with status {value}")

    def derive(self, key: bytes, info: bytes, salt: bytes = b"", length: int = 32) -> bytes:
        parameters = c.c_void_p()
        self.status(self.cc.CCKDFParametersCreateHkdf(
            c.byref(parameters), salt, len(salt), info, len(info),
        ))
        try:
            output = c.create_string_buffer(length)
            self.status(self.cc.CCDeriveKey(parameters, 10, key, len(key), output, length))
            return output.raw
        finally:
            self.cc.CCKDFParametersDestroy(parameters)

    def siphash(self, key: bytes, message: bytes) -> bytes:
        return struct.pack("<Q", self.cu.SipHash(key, message, len(message)))

    def ccm(self, operation: int, key: bytes, nonce: bytes, aad: bytes,
            payload: bytes, tag_length: int) -> tuple[bytes, bytes]:
        cryptor = c.c_void_p()
        # Captured invocation: AES(0), CCM(12), padding argument 1000.
        self.status(self.cc.CCCryptorCreateWithMode(
            operation, 12, 0, 1000, None, key, len(key), None, 0, 0, 0, c.byref(cryptor),
        ))
        try:
            for parameter, data, length in [
                (3, None, len(payload)), (2, None, tag_length),
                (0, nonce, len(nonce)), (1, aad, len(aad)),
            ]:
                self.status(self.cc.CCCryptorAddParameter(cryptor, parameter, data, length))
            output = c.create_string_buffer(len(payload) + 32)
            written = c.c_size_t()
            self.status(self.cc.CCCryptorUpdate(
                cryptor, payload, len(payload), output, len(output), c.byref(written),
            ))
            final_written = c.c_size_t()
            self.status(self.cc.CCCryptorFinal(
                cryptor, c.byref(output, written.value), len(output) - written.value,
                c.byref(final_written),
            ))
            tag = c.create_string_buffer(tag_length)
            tag_size = c.c_size_t(tag_length)
            self.status(self.cc.CCCryptorGetParameter(cryptor, 4, tag, c.byref(tag_size)))
            check(tag_size.value == tag_length, "Unexpected CCM tag length")
            return output.raw[:written.value + final_written.value], tag.raw
        finally:
            self.cc.CCCryptorRelease(cryptor)


def verify() -> dict:
    apple = AppleCrypto()
    # Original authors' published SipHash-2-4 vector for key 00..0f, empty input.
    check(siphash24(IRK, b"").hex() == "310e0edd47db6f72", "SipHash reference mismatch")
    for length in range(64):
        message = bytes(range(length))
        check(siphash24(IRK, message) == apple.siphash(IRK, message), f"SipHash length {length}")

    # RFC 3610 packet vector 1 (AES-128), independent of the observed Apple format.
    rfc_key = bytes(range(0xC0, 0xD0))
    rfc_nonce = bytes.fromhex("00000003020100a0a1a2a3a4a5")
    rfc_plaintext = bytes(range(8, 31))
    ciphertext, tag = apple.ccm(0, rfc_key, rfc_nonce, bytes(range(8)), rfc_plaintext, 8)
    check((ciphertext + tag).hex() == (
        "588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0"
    ), "RFC 3610 CCM mismatch")

    key = hkdf_sha256(IRK, SESSION_ID)
    check(key == apple.derive(IRK, SESSION_ID), "HKDF-SHA256 mismatch")
    token_secret = hkdf_sha256(SHARED_SECRET, b"", TOKEN_SALT, 16)
    check(token_secret == apple.derive(SHARED_SECRET, b"", TOKEN_SALT, 16), "Token KDF mismatch")
    check(siphash24(IRK, ADDRESS_INPUT) == apple.siphash(IRK, ADDRESS_INPUT), "Address SipHash mismatch")
    finder_response = siphash24(IRK, FINDER_CHALLENGE + FINDEE_CHALLENGE)[:6]
    findee_response = siphash24(IRK, FINDEE_CHALLENGE + FINDER_CHALLENGE)[:6]
    # All token IRKs are equal in this fixture for convenience. The real code
    # selects a session token for CCM and role-associated tokens for mutual auth;
    # their relationship and acquisition are not reproduced here.
    # Nibble states below are test values; this is not a complete state machine.
    plaintext = (b"\x01\x23" + FINDER_CHALLENGE + finder_response
                 + b"\x45" + FINDEE_CHALLENGE + findee_response)
    check(len(plaintext) == 21, "Unexpected mutual-auth message size")
    counter = 0x01020304
    aad = b"\x01" + struct.pack("<I", counter)
    nonce = struct.pack("<Q", counter)
    ciphertext, tag = apple.ccm(0, key, nonce, aad, plaintext, 6)
    envelope = aad + tag + ciphertext
    decrypted, computed_tag = apple.ccm(1, key, nonce, aad, ciphertext, 6)
    check(hmac.compare_digest(computed_tag, tag) and decrypted == plaintext, "CCM roundtrip")

    # Authenticate all serialized bytes. Changing version, counter, tag, ciphertext,
    # or shared key must not pass the original authentication check.
    rejected = 0
    for index in range(len(envelope)):
        altered = bytearray(envelope)
        altered[index] ^= 1
        changed = bytes(altered)
        if changed[0] != 1:
            rejected += 1
            continue
        changed_counter = struct.unpack("<I", changed[1:5])[0]
        _, expected = apple.ccm(1, key, struct.pack("<Q", changed_counter), changed[:5], changed[11:], 6)
        check(not hmac.compare_digest(expected, changed[5:11]), f"Accepted tamper at {index}")
        rejected += 1
    wrong_key = hkdf_sha256(bytes(reversed(IRK)), SESSION_ID)
    _, wrong_tag = apple.ccm(1, wrong_key, nonce, aad, ciphertext, 6)
    check(not hmac.compare_digest(wrong_tag, tag), "Accepted wrong shared key")

    return {
        "scope": "Synthetic static-analysis reproduction only; no device, account or radio access",
        "target": "iPhone18,3 iOS 27.0.1 24A446 nearbyd",
        "target_sha256": "4f2702b9e7ab26ac9aab305f15a054965690d40cd6a0e29a6dacdd818d6f0dfa",
        "mac_token_target": "macOS 27.2 26B123 findmylocateagent arm64e",
        "mac_token_target_fat_binary_sha256": (
            "46a6157e0591725563e41a15ec9024d2ab0948d76be0f7bfc530ed010a55f4c1"
        ),
        "checks": {
            "siphash24_lengths_compared_with_host_CoreUtils": 64,
            "ccm_RFC3610_packet_vector_1": "passed",
            "hkdf_sha256_compared_with_host_CommonCrypto": "passed",
            "mac_token_kdf_16_bytes_compared_with_host_CommonCrypto": "passed",
            "ccm_roundtrip": "passed",
            "single_byte_tampers_rejected": rejected,
            "wrong_shared_key_rejected": True,
        },
        "synthetic_vector": {
            "mac_token_shared_secret": SHARED_SECRET.hex(),
            "mac_token_salt": TOKEN_SALT.decode(),
            "mac_token_derived_secret_without_bucket_info": token_secret.hex(),
            "irk": IRK.hex(), "session_identifier": SESSION_ID.hex(),
            "session_key": key.hex(), "counter": counter,
            "nonce": nonce.hex(), "aad": aad.hex(),
            "finder_challenge": FINDER_CHALLENGE.hex(), "findee_challenge": FINDEE_CHALLENGE.hex(),
            "finder_response": finder_response.hex(), "findee_response": findee_response.hex(),
            "address_siphash_input": ADDRESS_INPUT.hex(),
            "address_siphash_result_le64": siphash24(IRK, ADDRESS_INPUT).hex(),
            "mutual_auth_plaintext": plaintext.hex(),
            "ccm_tag": tag.hex(), "ciphertext": ciphertext.hex(), "envelope": envelope.hex(),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Write only public synthetic test results")
    args = parser.parse_args()
    report = verify()
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report["checks"], indent=2))


if __name__ == "__main__":
    main()
