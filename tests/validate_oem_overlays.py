#!/usr/bin/env python3
"""Check a built HEX/SREC over each supplied full OEM backup, without hardware.

Usage: python3 tests/validate_oem_overlays.py build/rusefi.hex backup1.bin ...
Compiles the production C++ image checker and checks retained bytes and CRCs.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("contract", ROOT / "tests/test_memory_contract.py")
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
payload = Path(sys.argv[1])
decoded = contract.decode_records(payload.read_text(), "hex" if payload.suffix == ".hex" else "srec")
expected = set(range(0x08001000, 0x08060000)) | set(range(0x08080000, 0x08100000))
assert set(decoded) == expected
assert bytes(decoded[a] for a in range(0x0805FFE0, 0x08060000)) == struct.pack(
    "<8I", 0x3934374D, 0x33544341, 0xD7B6B894, 0x08060000, 0x4F256CD9, 0x08069000, 0xE3186D26, 0x08060000)
checker = r'''
#include "boot_activation.h"
#include <cassert>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), {});
    assert(data.size() == 0x3F0000);
    auto read = [&](uint32_t a) { return data.at(a - 0x08000000); };
    auto check = [&] { return m749::checkImages(read, [] {}); };
    auto result = check();
    assert(result.valid);
    for (unsigned offset : {0x1000U, 0x80000U, 0x69000U, 0x7FFFCU, 0xFFFFCU, 0U, 0x201000U, 0x22DFFCU}) {
        data[offset] ^= 1;
        assert(!check().valid);
        data[offset] ^= 1;
    }
    data[0x60000] ^= 1;
    assert(check().valid == (result.boot == m749::I812BootCrc));
    std::cout << std::hex << result.software << " " << result.calibration << " " << result.boot;
}
'''
results = []
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    source = directory / "checker.cpp"
    source.write_text(checker)
    executable = directory / "checker"
    subprocess.run(["c++", "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-I", str(ROOT / "firmware"),
                    str(source), "-o", str(executable)], check=True)
    for name in sys.argv[2:]:
        backup = Path(name)
        original = backup.read_bytes()
        assert len(original) == 0x3F0000
        merged = bytearray(original)
        for address, value in decoded.items():
            merged[address - 0x08000000] = value
        for start, end in [(0, 0x1000), (0x60000, 0x80000), (0x100000, 0x3F0000)]:
            assert merged[start:end] == original[start:end]
        overlay = directory / "overlay.bin"
        overlay.write_bytes(merged)
        crcs = subprocess.check_output([str(executable), str(overlay)], text=True).split()
        results.append({"backup": backup.name, "backup_sha256": hashlib.sha256(original).hexdigest(),
                        "software_crc": crcs[0], "calibration_crc": crcs[1], "loader_crc": crcs[2],
                        "retained_bytes_unchanged": True, "corruption_rejected": True})
assert results, "Supply at least one full OEM backup"
print(json.dumps({"payload": payload.name, "sha256": hashlib.sha256(payload.read_bytes()).hexdigest(),
                  "bytes": len(decoded), "profiles": results}, indent=2))
