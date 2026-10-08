"""Full 707 image preservation, input rejection and build-wrapper checks."""

from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from test_memory_contract import elf_fixture

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "bin"))
import m749_707_image as builder


class Image707Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.oem = builder.OEM.read_bytes()
        cls.descriptor = struct.pack("<8I", 0x3934374D, 0x33544341,
                                     0xD7B6B894, 0x08060000,
                                     0x4F256CD9, 0x08069000,
                                     0xE3186D26, 0x08060000)
        cls.elf = elf_fixture([(builder.DESCRIPTOR, cls.descriptor)])

    def test_full_image_preserves_every_byte_outside_software(self):
        result = builder.merge(self.oem, self.elf)
        self.assertEqual(len(result), 0x3F0000)
        for start, end in ((0, 0x1000), (0x60000, 0x80000), (0x100000, 0x3F0000)):
            self.assertEqual(result[start:end], self.oem[start:end])
        self.assertEqual(struct.unpack_from("<II", result, 0x1000), (0x20020000, 0x08080001))
        self.assertEqual(result[0x1008:0x5FFE0], b"\xFF" * (0x5FFE0 - 0x1008))
        builder.image.verify_domains([(0x08001000, result[0x1000:0x60000]),
                                      (0x08080000, result[0x80000:0x100000])])

    def test_truncated_blank_and_changed_oem_are_rejected(self):
        changed = bytearray(self.oem)
        changed[0x24F000] ^= 1  # Identity is outside the three CRC domains.
        for data in (self.oem[:-1], b"\xFF" * len(self.oem), bytes(changed)):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                builder.merge(data, self.elf)

    def test_missing_old_and_incompatible_descriptors_are_rejected(self):
        for descriptor in (b"\xFF" * 32, self.descriptor[:7] + b"2" + self.descriptor[8:],
                           self.descriptor[:-8] + struct.pack("<II", 0xE3186D26, 0x08069000)):
            with self.subTest(descriptor=descriptor.hex()), patch.object(builder, "validate_oem"):
                with self.assertRaises(ValueError):
                    builder.merge(self.oem, elf_fixture([(builder.DESCRIPTOR, descriptor)]))

    def test_elf_cannot_write_retained_regions(self):
        with patch.object(builder, "validate_oem"), self.assertRaises(ValueError):
            builder.merge(self.oem, elf_fixture([(builder.DESCRIPTOR, self.descriptor),
                                               (0x08060000, b"bad")]))

    def test_cli_paths_with_spaces_and_failed_validation_preserves_output(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            elf = directory / "firmware input.elf"
            output = directory / "new output/full image.bin"
            elf.write_bytes(self.elf)
            command = [sys.executable, str(ROOT / "bin/m749_707_image.py"), "--elf", str(elf),
                       "--output", str(output)]
            subprocess.run(command, cwd=directory, check=True, capture_output=True)
            original = output.read_bytes()
            self.assertEqual(len(original), 0x3F0000)
            elf.write_bytes(b"invalid ELF")
            result = subprocess.run(command, cwd=directory, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_bytes(), original)
            for target in (elf, builder.OEM):
                result = subprocess.run(command[:-1] + [str(target)], capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b"must not overwrite", result.stderr)

    def test_build_failure_does_not_package_stale_firmware(self):
        with tempfile.TemporaryDirectory() as directory:
            board = Path(directory) / "board with spaces"
            (board / "bin").mkdir(parents=True)
            wrapper = board / "bin/build-707.sh"
            wrapper.write_bytes((ROOT / "bin/build-707.sh").read_bytes())
            (board / "compile_firmware.sh").write_text('exit 23\n')
            (board / "bin/m749_707_image.py").write_text('raise RuntimeError("stale build packaged")\n')
            result = subprocess.run(["bash", str(wrapper)], cwd=directory, capture_output=True)
            self.assertEqual(result.returncode, 23)
            self.assertNotIn(b"stale build packaged", result.stderr)


if __name__ == "__main__":
    unittest.main()
