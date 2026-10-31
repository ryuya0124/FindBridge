#!/usr/bin/env python3
"""Read one nominal Swift type's stored fields from a thin arm64 Mach-O.

This deliberately reads only local relative metadata. It does not execute the
image, load private frameworks, resolve account records or access a radio.
Symbolic field types requiring other cache images are reported as unresolved.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct


class MachO:
    def __init__(self, path: Path):
        self.data = path.read_bytes()
        self.segments = []
        self.sections = {}
        if self.read(0, 4) != b"\xcf\xfa\xed\xfe":
            raise ValueError("Expected a thin, little-endian 64-bit Mach-O")
        if self.unpack("I", 4)[0] != 0x0100000C:
            raise ValueError("Expected an arm64 Mach-O")
        count, commands_size = self.unpack("II", 16)
        self.read(32, commands_size)
        cursor = 32
        commands_end = cursor + commands_size
        if count > commands_size // 8:
            raise ValueError("Invalid load command count")
        for _ in range(count):
            command, size = self.unpack("II", cursor)
            if size < 8 or cursor + size > commands_end:
                raise ValueError("Invalid load command size")
            if command == 0x19:  # LC_SEGMENT_64
                if size < 72:
                    raise ValueError("Truncated segment command")
                address, virtual_size, offset, file_size = self.unpack("QQQQ", cursor + 24)
                self.read(offset, file_size)
                if file_size > virtual_size:
                    raise ValueError("File-backed segment exceeds its virtual size")
                self.segments.append((address, file_size, offset))
                sections = self.unpack("I", cursor + 64)[0]
                if 72 + 80 * sections > size:
                    raise ValueError("Truncated section table")
                for index in range(sections):
                    entry = cursor + 72 + index * 80
                    name = self.read(entry, 16).split(b"\0", 1)[0].decode("ascii")
                    section_address, section_size = self.unpack("QQ", entry + 32)
                    self.sections[name] = (section_address, section_size)
            cursor += size

    def read(self, offset: int, size: int) -> bytes:
        if offset < 0 or size < 0 or offset + size > len(self.data):
            raise ValueError("Read outside Mach-O file")
        return self.data[offset:offset + size]

    def unpack(self, form: str, offset: int):
        return struct.unpack("<" + form, self.read(offset, struct.calcsize("<" + form)))

    def file_offset(self, address: int, size: int = 1) -> int:
        for start, length, offset in self.segments:
            if start <= address and address + size <= start + length:
                return offset + address - start
        raise ValueError(f"Address {address:#x} is not backed by this image")

    def value(self, form: str, address: int):
        return self.unpack(form, self.file_offset(address, struct.calcsize("<" + form)))

    def relative(self, address: int) -> int | None:
        delta = self.value("i", address)[0]
        return address + delta if delta else None

    def string(self, address: int) -> str:
        raw = bytearray()
        for index in range(2048):
            byte = self.read(self.file_offset(address + index), 1)[0]
            if not byte:
                return raw.decode("utf-8")
            raw.append(byte)
        raise ValueError("Unterminated metadata string")

    def fields(self, type_name: str) -> list[dict]:
        address, size = self.sections.get("__swift5_types", (0, 0))
        if not address or size % 4:
            raise ValueError("Missing or malformed Swift type references")
        matches = []
        for index in range(size // 4):
            descriptor = self.relative(address + index * 4)
            if descriptor is None:
                continue
            kind = self.value("I", descriptor)[0] & 0x1F
            if kind not in (16, 17, 18):  # class, struct, enum
                continue
            name_address = self.relative(descriptor + 8)
            if name_address is None or self.string(name_address) != type_name:
                continue
            fields_address = self.relative(descriptor + 16)
            fields = []
            if fields_address is not None:
                _, _, _, record_size, count = self.value("iiHHI", fields_address)
                if record_size < 12 or count > 65536:
                    raise ValueError("Invalid Swift field record table")
                for field_index in range(count):
                    record = fields_address + 16 + field_index * record_size
                    field_name = self.relative(record + 8)
                    if field_name is None:
                        raise ValueError("Missing field name")
                    field_type = self.relative(record + 4)
                    mangled = None
                    if field_type is not None:
                        # Symbolic references contain binary offsets and cannot be
                        # decoded as a C string without the rest of the dyld cache.
                        try:
                            candidate = self.string(field_type)
                        except UnicodeError:
                            candidate = ""
                        if candidate.isascii() and candidate.isprintable():
                            mangled = candidate
                    fields.append({"name": self.string(field_name), "mangled_type": mangled})
            matches.append({"name": type_name, "kind": kind,
                            "descriptor": hex(descriptor), "fields": fields})
        if not matches:
            raise ValueError(f"Swift type {type_name!r} was not found")
        return matches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--type", required=True, dest="type_name")
    parser.add_argument("--version-label", required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    image = MachO(args.image)
    report = {
        "image": args.image.name,
        "version": args.version_label,
        "sha256": hashlib.sha256(image.data).hexdigest(),
        "method": "Local relative Swift metadata only; no execution or account/radio access",
        "types": image.fields(args.type_name),
    }
    encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")


if __name__ == "__main__":
    main()
