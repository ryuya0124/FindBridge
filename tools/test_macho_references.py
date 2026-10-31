#!/usr/bin/env python3
"""Check signed address decoding and a compiler-generated, never-executed fixture."""

import platform
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from inspect_macho_references import (
    References,
    direct_branch,
    function_starts,
    page_reference,
)


class AddressDecodingTest(unittest.TestCase):
    def test_positive_and_negative_adrp_pages(self):
        # adrp x0, +2 pages; add x0, x0, #0x456.
        self.assertEqual(0x3456, page_reference(0x1000, 0xD0000000, 0x91115800))
        # adrp x2, -1 page; add x2, x2, #8.
        self.assertEqual(0x1008, page_reference(0x2123, 0xF0FFFFE2, 0x91002042))

    def test_register_mismatch_sp_and_unrelated_instructions_are_ignored(self):
        self.assertIsNone(page_reference(0x1000, 0xD0000000, 0x91115820))
        self.assertIsNone(page_reference(0x1000, 0x9000001F, 0x910003FF))
        self.assertIsNone(page_reference(0x1000, 0xD503201F, 0x91115800))

    def test_backward_and_forward_branches(self):
        self.assertEqual((0xFFC, "B"), direct_branch(0x1000, 0x17FFFFFF))
        self.assertEqual((0xFF8, "BL"), direct_branch(0x1000, 0x97FFFFFE))
        self.assertEqual((0x1010, "BL"), direct_branch(0x1000, 0x94000004))
        self.assertIsNone(direct_branch(0x1000, 0xD61F0200))  # br x16

    def test_function_start_deltas_and_truncation(self):
        self.assertEqual(
            [0x1010, 0x1091], function_starts(bytes.fromhex("10810100"), 0x1000)
        )
        with self.assertRaises(ValueError):
            function_starts(b"\x80", 0x1000)
        with self.assertRaises(ValueError):
            function_starts(b"\x80" * 11, 0x1000)

    @unittest.skipUnless(
        platform.system() == "Darwin" and shutil.which("xcrun"),
        "macOS toolchain required",
    )
    def test_real_macho_pagezero_function_base_strings_and_symbol_stubs(self):
        with tempfile.TemporaryDirectory(
            prefix="findbridge-static-fixture-"
        ) as directory:
            work = Path(directory)
            source = work / "fixture.c"
            executable = work / "fixture"
            source.write_text(
                '#include <stdio.h>\nint main(void) { puts("FIND_BRIDGE_PUBLIC_FIXTURE"); return 0; }\n'
            )
            subprocess.run(
                [
                    "xcrun",
                    "clang",
                    "-arch",
                    "arm64",
                    "-O1",
                    "-fno-builtin",
                    str(source),
                    "-o",
                    str(executable),
                ],
                check=True,
                capture_output=True,
            )
            # Parsing only. The compiled fixture executable is never launched.
            references = References(executable)
            start, size = references.image.sections["__text"]
            self.assertTrue(references.starts)
            self.assertTrue(
                all(start <= value < start + size for value in references.starts)
            )
            rows = references.inspect(["FIND_BRIDGE_PUBLIC_FIXTURE"], ["_puts"], [])
            self.assertTrue(any(row["kind"] == "ADRP_ADD" for row in rows))
            self.assertTrue(any(row.get("import") == "_puts" for row in rows))
            self.assertTrue(
                all(int(row["function"], 16) >= 0x100000000 for row in rows)
            )


if __name__ == "__main__":
    unittest.main()
