#!/usr/bin/env python3
"""Build addressed M74.9 update payloads; never issue erase/write/activation commands.

Software and calibration are separate invocations and independent CRC domains.
Every output range contains complete 4 KiB pages, including erased padding.
"""

import argparse
from pathlib import Path
import struct

APP_START = 0x08001000
CAL_START = 0x08060000
CAL_CRC = 0x0807FFFC
APP_SECOND = 0x08080000
APP_CRC = 0x080FFFFC
APP_END = 0x08100000  # exclusive
PAGE_SIZE = 4096
SOFTWARE = ((APP_START, CAL_START), (APP_SECOND, APP_CRC))


def crc32_mpeg2(data, crc=0xFFFFFFFF):
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ (0x04C11DB7 if crc & 0x80000000 else 0)) & 0xFFFFFFFF
    return crc


def validate_range(address, length):
    if length <= 0 or address < APP_START or address + length > APP_END:
        raise ValueError("payload outside the application-only whitelist")


def software_image(elf):
    """Read ELF32 load addresses, including initialized RAM's flash contents."""
    if len(elf) < 52 or elf[:7] != b"\x7fELF\x01\x01\x01":
        raise ValueError("expected a little-endian ELF32 image")
    if struct.unpack_from("<HH", elf, 16) != (2, 40):
        raise ValueError("expected an ARM executable")
    entry, phoff = struct.unpack_from("<II", elf, 24)
    phsize, phnum = struct.unpack_from("<HH", elf, 42)
    if entry != APP_SECOND + 1 or phsize != 32 or phoff + phsize * phnum > len(elf):
        raise ValueError("invalid ELF entry point or program headers")
    image = bytearray(b"\xFF" * (APP_END - APP_START))
    populated = bytearray(len(image))
    for index in range(phnum):
        kind, offset, _, address, size, memsize, _, _ = struct.unpack_from(
            "<8I", elf, phoff + index * phsize)
        if kind != 1 or size == 0:
            continue
        if size > memsize or offset + size > len(elf):
            raise ValueError("truncated ELF load segment")
        validate_range(address, size)
        if not any(start <= address and address + size <= end for start, end in SOFTWARE):
            raise ValueError("ELF overlaps calibration or a CRC trailer")
        start = address - APP_START
        if any(populated[start:start + size]):
            raise ValueError("overlapping ELF load segments")
        image[start:start + size] = elf[offset:offset + size]
        populated[start:start + size] = b"\x01" * size
    if not all(populated[:8]) or not all(populated[APP_SECOND - APP_START:APP_SECOND - APP_START + 2]):
        raise ValueError("missing vectors or startup code")
    if struct.unpack_from("<II", image) != (0x20020000, APP_SECOND + 1):
        raise ValueError("incorrect application stack/reset vectors")
    crc = 0xFFFFFFFF
    for start, end in SOFTWARE:
        crc = crc32_mpeg2(image[start - APP_START:end - APP_START], crc)
    struct.pack_into("<I", image, APP_CRC - APP_START, crc)
    return [(APP_START, bytes(image[:CAL_START - APP_START])),
            (APP_SECOND, bytes(image[APP_SECOND - APP_START:]))]


def calibration_image(data):
    # Require all retained bytes. Never guess calibration from a sparse file or
    # silently fill it with FF while replacing software.
    if len(data) != CAL_CRC - CAL_START:
        raise ValueError("calibration input must contain exactly 131068 bytes (without CRC)")
    return [(CAL_START, data + struct.pack("<I", crc32_mpeg2(data)))]


def verify_domains(ranges, calibration=False):
    expected = [(CAL_START, APP_SECOND)] if calibration else [
        (APP_START, CAL_START), (APP_SECOND, APP_END)]
    if [(address, address + len(data)) for address, data in ranges] != expected:
        raise ValueError("incomplete download ranges or mixed CRC domains")
    crc = 0xFFFFFFFF
    for index, (address, data) in enumerate(ranges):
        validate_range(address, len(data))
        if address % PAGE_SIZE or len(data) % PAGE_SIZE:
            raise ValueError("erase/download ranges must contain complete 4 KiB pages")
        last = index == len(ranges) - 1
        crc = crc32_mpeg2(data[:-4] if last else data, crc)
    if struct.unpack("<I", ranges[-1][1][-4:])[0] != crc:
        raise ValueError("CRC validation failed")


def ihex_record(kind, address, data):
    raw = bytes([len(data)]) + struct.pack(">HB", address, kind) + data
    return ":" + (raw + bytes([-sum(raw) & 255])).hex().upper()


def encode(ranges, output_format, calibration=False):
    verify_domains(ranges, calibration)
    lines = []
    upper = None
    for address, data in ranges:
        for offset in range(0, len(data), 32):
            dest = address + offset
            block = data[offset:offset + 32]
            if output_format == "hex":
                if dest >> 16 != upper:
                    upper = dest >> 16
                    lines.append(ihex_record(4, 0, struct.pack(">H", upper)))
                lines.append(ihex_record(0, dest & 65535, block))
            elif output_format == "srec":
                raw = bytes([len(block) + 5]) + struct.pack(">I", dest) + block
                lines.append("S3" + (raw + bytes([~sum(raw) & 255])).hex().upper())
            else:
                raise ValueError("raw binaries/DFU are disabled; use addressed HEX or SREC")
    if output_format == "hex":
        lines.append(ihex_record(1, 0, b""))
    else:
        # An S7 terminator is file metadata, not permission to reset/activate.
        raw = bytes([5]) + struct.pack(">I", 0 if calibration else APP_SECOND + 1)
        lines.append("S7" + (raw + bytes([~sum(raw) & 255])).hex().upper())
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("hex", "srec", "bin"), required=True)
    parser.add_argument("--calibration", action="store_true",
                        help="input is a complete raw calibration domain without its CRC")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        if args.format == "bin":
            raise ValueError("raw binaries are disabled; use addressed HEX or SREC")
        data = args.input.read_bytes()
        ranges = calibration_image(data) if args.calibration else software_image(data)
        result = encode(ranges, args.format, args.calibration)
        args.output.write_text(result, encoding="ascii")
    except (ValueError, OSError, struct.error) as error:
        parser.exit(1, f"M74.9 image rejected: {error}\n")


if __name__ == "__main__":
    main()
