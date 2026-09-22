import importlib.util
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("m749_image", ROOT / "bin/m749_image.py")
image = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image)


def elf_fixture(extra=()):
    segments = [(image.APP_START, struct.pack("<II", 0x20020000, 0x08080001)),
                (image.APP_SECOND, b"\x72\xB6\x00\xBF")] + list(extra)
    offset = 52 + 32 * len(segments)
    elf = bytearray(offset)
    elf[:7] = b"\x7fELF\x01\x01\x01"
    struct.pack_into("<HHII", elf, 16, 2, 40, 1, 0x08080001)
    struct.pack_into("<I", elf, 28, 52)
    struct.pack_into("<HHH", elf, 40, 52, 32, len(segments))
    for i, (address, data) in enumerate(segments):
        struct.pack_into("<8I", elf, 52 + 32 * i,
                         1, offset, address, address, len(data), len(data), 5, 4)
        elf.extend(data)
        offset += len(data)
    return elf


def decode_records(text, fmt):
    result = {}
    upper = 0
    for line in text.splitlines():
        raw = bytes.fromhex(line[1:] if fmt == "hex" else line[2:])
        assert sum(raw) & 255 == (0 if fmt == "hex" else 255)
        if fmt == "hex":
            assert len(raw) == raw[0] + 5
            if raw[3] == 4:
                upper = int.from_bytes(raw[4:6], "big") << 16
            if raw[3] != 0:
                continue
            address = upper + int.from_bytes(raw[1:3], "big")
            data = raw[4:-1]
        else:
            assert len(raw) == raw[0] + 1
            if line[:2] != "S3":
                continue
            address = int.from_bytes(raw[1:5], "big")
            data = raw[5:-1]
        for index, byte in enumerate(data):
            assert address + index not in result
            result[address + index] = byte
    return result


class MemoryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.software = image.software_image(elf_fixture())

    def test_crc_known_vector_and_incremental_segments(self):
        self.assertEqual(image.crc32_mpeg2(b"123456789"), 0x0376E6E7)
        self.assertEqual(image.crc32_mpeg2(b"456789", image.crc32_mpeg2(b"123")), 0x0376E6E7)

    def test_addressed_outputs_have_complete_pages_and_no_calibration(self):
        for fmt in ("hex", "srec"):
            with self.subTest(fmt=fmt):
                decoded = decode_records(image.encode(self.software, fmt), fmt)
                expected = dict((address + i, byte) for address, data in self.software
                                for i, byte in enumerate(data))
                self.assertEqual(decoded, expected)
                self.assertNotIn(image.CAL_START, decoded)
                self.assertNotIn(image.CAL_CRC, decoded)
                self.assertEqual(decoded[image.APP_START + 100], 255)
                self.assertEqual(decoded[image.APP_CRC - 1], 255)

    def test_protected_regions_and_crc_trailers_are_rejected(self):
        for address in (0x08000000, image.APP_START - 1, image.CAL_START,
                        image.CAL_CRC, image.APP_CRC, image.APP_END, 0x08200000,
                        0x08201000, 0x0822DFFC, 0x0824E000, 0x0824F000,
                        0x08250000, 0x08270000, 0x08274000, 0x08300000,
                        0x20000000, 0xFFFFFFFF):
            with self.subTest(address=hex(address)), self.assertRaises(ValueError):
                image.software_image(elf_fixture([(address, b"\x00")]))
        with self.assertRaises(ValueError):
            image.software_image(elf_fixture([(image.CAL_START - 1, b"xx")]))

    def test_bad_vectors_truncation_overlap_and_missing_startup(self):
        for offset in (24, 52 + 32 * 2, 52 + 32 * 2 + 4):
            elf = elf_fixture()
            elf[offset] ^= 4
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                image.software_image(elf)
        with self.assertRaises(ValueError):
            image.software_image(elf_fixture()[:-1])
        with self.assertRaises(ValueError):
            image.software_image(elf_fixture([(image.APP_START, b"x")]))
        elf = elf_fixture()
        struct.pack_into("<I", elf, 52 + 32 + 16, 0)
        with self.assertRaises(ValueError):
            image.software_image(elf)

    def test_calibration_is_explicit_complete_and_independent(self):
        data = b"\xA5" * (image.CAL_CRC - image.CAL_START)
        cal = image.calibration_image(data)
        image.verify_domains(cal, calibration=True)
        decoded = decode_records(image.encode(cal, "hex", calibration=True), "hex")
        self.assertEqual(min(decoded), image.CAL_START)
        self.assertEqual(max(decoded), image.APP_SECOND - 1)
        self.assertEqual(cal[0][1][:-4], data)
        for bad in (data[:-1], data + b"\xFF", b""):
            with self.assertRaises(ValueError):
                image.calibration_image(bad)
        with self.assertRaises(ValueError):
            image.encode(self.software + cal, "hex")

    def test_corruption_incomplete_range_and_raw_binary_rejected(self):
        first, second = self.software
        corrupted = bytearray(first[1])
        corrupted[100] ^= 1  # erased padding is included in the CRC
        with self.assertRaises(ValueError):
            image.verify_domains([(first[0], corrupted), second])
        with self.assertRaises(ValueError):
            image.verify_domains([(first[0], first[1][:-1]), second])
        with self.assertRaises(ValueError):
            image.encode(self.software, "bin")

    def test_handoff_protocol(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "test_handoff"
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                            str(ROOT / "tests/test_bootloader_handoff.cpp"),
                            "-o", str(executable)], check=True)
            subprocess.run([str(executable)], check=True)

    def test_activation_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "test_activation"
            subprocess.run(["c++", "-std=c++17", "-Wall", "-Wextra", "-Werror",
                            str(ROOT / "tests/test_boot_activation.cpp"), "-o", str(executable)], check=True)
            subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    unittest.main()
