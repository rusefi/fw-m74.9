#!/usr/bin/env python3
"""Combine the supplied OEM 707 backup with a built M74.9 rusEFI ELF, offline."""

import argparse
import hashlib
from pathlib import Path
import struct

import m749_image as image

ROOT = Path(__file__).resolve().parents[1]
OEM = ROOT / "docs/oem/I832GA01_w2304v2_8450110707.bin"
OEM_SHA256 = "f574718ecaa776876cab89d0520054dff23c4ac69535f64e0fbe19d45eeade10"
FLASH_BASE = 0x08000000
FLASH_SIZE = 0x3F0000
DESCRIPTOR = 0x0805FFE0


def validate_oem(data):
    if len(data) != FLASH_SIZE:
        raise ValueError("OEM 707 backup must contain exactly 0x3F0000 bytes")
    if hashlib.sha256(data).hexdigest() != OEM_SHA256:
        raise ValueError("OEM 707 backup SHA-256 does not match the supplied reference")
    for name, domains, trailer, expected in (
        ("boot", ((0, 0x1000), (0x201000, 0x22DFFC)), 0x22DFFC, 0xE3186D26),
        ("software", ((0x1000, 0x60000), (0x80000, 0xFFFFC)), 0xFFFFC, 0xDF2857AA),
        ("calibration", ((0x60000, 0x7FFFC),), 0x7FFFC, 0xD7BA65B9),
    ):
        crc = 0xFFFFFFFF
        for start, end in domains:
            crc = image.crc32_mpeg2(data[start:end], crc)
        if crc != expected or struct.unpack_from("<I", data, trailer)[0] != crc:
            raise ValueError(f"OEM 707 {name} CRC mismatch")


def merge(oem, elf):
    validate_oem(oem)
    ranges = image.software_image(elf)
    image.verify_domains(ranges)
    descriptor = struct.unpack_from("<8I", ranges[0][1], DESCRIPTOR - image.APP_START)
    if descriptor[:2] != (0x3934374D, 0x33544341):
        raise ValueError("I832 requires M749ACT3 replacement firmware")
    if (0xE3186D26, image.CAL_START) not in tuple(zip(descriptor[2::2], descriptor[3::2])):
        raise ValueError("replacement firmware does not declare the I832 loader/calibration profile")
    result = bytearray(oem)
    for address, data in ranges:
        offset = address - FLASH_BASE
        result[offset:offset + len(data)] = data
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, default=ROOT / "ext/rusefi/firmware/build/rusefi.elf",
                        help="built M74.9 ELF (default: board firmware build)")
    parser.add_argument("--oem", type=Path, default=OEM,
                        help="exact OEM 707 reference backup (SHA-256 checked)")
    parser.add_argument("--output", type=Path, default=ROOT / "build/707/rusefi-707-full.bin",
                        help="full flash BIN mapped at 0x08000000")
    args = parser.parse_args()
    try:
        output = args.output.resolve()
        inputs = (args.oem.resolve(), args.elf.resolve(), OEM.resolve())
        if output in inputs or (output.exists() and any(output.samefile(p) for p in inputs if p.exists())):
            raise ValueError("output must not overwrite an input or the bundled OEM backup")
        result = merge(args.oem.read_bytes(), args.elf.read_bytes())
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(result)
        print(f"Created {output}: {len(result)} bytes at 0x{FLASH_BASE:08X}")
        print(f"SHA-256: {hashlib.sha256(result).hexdigest()}")
        print(f"Software CRC: {struct.unpack_from('<I', result, 0xFFFFC)[0]:08X}")
        print("OEM loader, calibration, marker and stored data preserved. MCU options are separate.")
    except (ValueError, OSError, struct.error) as error:
        parser.exit(1, f"M74.9 707 image rejected: {error}\n")


if __name__ == "__main__":
    main()
