#!/usr/bin/env python3
"""Find narrow static references in a local thin arm64 Mach-O.

Recognizes adjacent ADRP/ADD string references and direct B/BL branches. It does
not infer indirect Swift calls, execute images, contact services or read account
data. Function labels come from LC_FUNCTION_STARTS, including async fragments.
"""

import argparse
import bisect
import hashlib
import json
import struct
from pathlib import Path

from inspect_swift_fields import MachO


def signed(value: int, bits: int) -> int:
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def page_reference(pc: int, first: int, second: int) -> int | None:
    if first & 0x9F000000 != 0x90000000 or second & 0xFF000000 != 0x91000000:
        return None
    if first & 31 != (second >> 5) & 31:
        return None
    # Register 31 is SP for ADD, whereas ADRP's register 31 is XZR.
    if first & 31 == 31:
        return None
    immediate = ((first >> 5) & 0x7FFFF) << 2 | (first >> 29) & 3
    page = (pc & ~4095) + (signed(immediate, 21) << 12)
    return page + (((second >> 10) & 4095) << (12 if second & (1 << 22) else 0))


def direct_branch(pc: int, instruction: int) -> tuple[int, str] | None:
    if instruction & 0x7C000000 != 0x14000000:
        return None
    return pc + (signed(instruction & 0x3FFFFFF, 26) << 2), (
        "BL" if instruction & (1 << 31) else "B"
    )


def function_starts(raw: bytes, base: int) -> list[int]:
    starts = []
    value = shift = 0
    for byte in raw:
        if shift >= 64:
            raise ValueError("Function-start ULEB exceeds 64 bits")
        value |= (byte & 127) << shift
        if byte & 128:
            shift += 7
            continue
        if value == 0:
            return starts
        base += value
        if base >= 1 << 64:
            raise ValueError("Function address overflow")
        starts.append(base)
        value = shift = 0
    if shift:
        raise ValueError("Truncated function-start ULEB")
    return starts


class References:
    def __init__(self, path: Path):
        self.image = MachO(path)
        self.starts = []
        self.stubs = {}
        sections = []
        symbols = []
        indirect = []
        cursor = 32
        image = self.image
        for _ in range(image.unpack("I", 16)[0]):
            command, size = image.unpack("II", cursor)
            if command == 0x19:  # LC_SEGMENT_64
                for index in range(image.unpack("I", cursor + 64)[0]):
                    entry = cursor + 72 + 80 * index
                    address, length = image.unpack("QQ", entry + 32)
                    flags, first, stride = image.unpack("III", entry + 64)
                    sections.append((address, length, flags, first, stride))
            elif command == 0x26:  # LC_FUNCTION_STARTS
                offset, length = image.unpack("II", cursor + 8)
                base = next(
                    address
                    for address, file_size, file_offset in image.segments
                    if file_offset == 0 and file_size > 0
                )
                self.starts = function_starts(image.read(offset, length), base)
            elif command == 2:  # LC_SYMTAB
                offset, count, strings_offset, length = image.unpack("IIII", cursor + 8)
                strings = image.read(strings_offset, length)
                image.read(offset, count * 16)
                for index in range(count):
                    name = image.unpack("I", offset + index * 16)[0]
                    if name >= len(strings):
                        raise ValueError("Invalid symbol name offset")
                    symbols.append(
                        strings[name:].split(b"\0", 1)[0].decode("utf-8", "replace")
                    )
            elif command == 0xB:  # LC_DYSYMTAB
                offset, count = image.unpack("II", cursor + 56)
                indirect = image.unpack("I" * count, offset) if count else ()
            cursor += size
        for address, length, flags, first, stride in sections:
            if flags & 255 != 8:  # S_SYMBOL_STUBS
                continue
            if (
                stride == 0
                or length % stride
                or first + length // stride > len(indirect)
            ):
                raise ValueError("Malformed symbol-stub table")
            for index in range(length // stride):
                symbol = indirect[first + index]
                if symbol & 0xC0000000:  # INDIRECT_SYMBOL_LOCAL / ABS
                    continue
                if symbol >= len(symbols):
                    raise ValueError("Invalid indirect symbol")
                self.stubs[address + index * stride] = symbols[symbol]

    def containing_function(self, address: int) -> str | None:
        index = bisect.bisect_right(self.starts, address) - 1
        return hex(self.starts[index]) if index >= 0 else None

    def inspect(
        self, contains: list[str], imports: list[str], calls: list[int]
    ) -> list[dict]:
        image = self.image
        strings = {}
        for section in ("__cstring", "__oslogstring"):
            address, length = image.sections.get(section, (0, 0))
            if not address:
                continue
            raw = image.read(image.file_offset(address, length), length)
            offset = 0
            for value in raw.split(b"\0"):
                matching = [needle for needle in contains if needle.encode() in value]
                if matching:
                    # Report only the supplied search text, never a whole unrequested string.
                    strings[address + offset] = matching
                offset += len(value) + 1
        start, length = image.sections["__text"]
        if length % 4:
            raise ValueError("Unaligned arm64 text section")
        code = image.read(image.file_offset(start, length), length)
        words = struct.unpack("<" + "I" * (length // 4), code)
        results = []
        for index, instruction in enumerate(words):
            pc = start + index * 4
            common = {"pc": hex(pc), "function": self.containing_function(pc)}
            if index + 1 < len(words):
                target = page_reference(pc, instruction, words[index + 1])
                if target in strings:
                    results.append(
                        {
                            **common,
                            "kind": "ADRP_ADD",
                            "target": hex(target),
                            "matching": strings[target],
                        }
                    )
            branch = direct_branch(pc, instruction)
            if branch is not None:
                target, kind = branch
                name = self.stubs.get(target)
                if (
                    target in calls
                    or name
                    and any(needle in name for needle in imports)
                ):
                    results.append(
                        {**common, "kind": kind, "target": hex(target), "import": name}
                    )
        return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--version-label", required=True)
    parser.add_argument("--contains", action="append", default=[])
    parser.add_argument("--calls-import", action="append", default=[])
    parser.add_argument(
        "--calls-address", type=lambda value: int(value, 0), action="append", default=[]
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (args.contains or args.calls_import or args.calls_address):
        parser.error("Supply at least one explicit reference filter")
    references = References(args.image)
    report = {
        "image": args.image.name,
        "version": args.version_label,
        "sha256": hashlib.sha256(references.image.data).hexdigest(),
        "method": "Static adjacent ADRP/ADD and direct B/BL only; no execution or account access",
        "functions": len(references.starts),
        "references": references.inspect(
            args.contains, args.calls_import, args.calls_address
        ),
    }
    encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
