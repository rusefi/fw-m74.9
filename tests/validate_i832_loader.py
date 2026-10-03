#!/usr/bin/env python3
"""Execute the saved I832GA01 loader's programming and activation paths offline.

Usage: python3 tests/validate_i832_loader.py ../m749-ghidra
Requires Unicorn and that checkout's SHA-pinned I865 harness and I832 backup.
Only transport, physical flash I/O, reset and application entry are modeled.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import sys

from unicorn import UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0

sys.dont_write_bytecode = True
root = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("loader", root / "tools/uds/validate_i865_loader_protocol.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
image = root / "bin/Read_FULLFLASH_I832GA01_w2304v2_20261002T155353576Z_kent_8450110707.bin"
m.IMAGE = image.read_bytes()
sha = hashlib.sha256(m.IMAGE).hexdigest()
assert len(m.IMAGE) == 0x3F0000
assert sha == "f574718ecaa776876cab89d0520054dff23c4ac69535f64e0fbe19d45eeade10"

# I832 shares the UDS handler addresses with I865; its RAM state moves by 32
# bytes. Physical flash entry points and data initializers move independently.
m.REQ_LEN = 0x20000422
m.REQ_BUF = 0x20000434
m.CURRENT_SESSION = 0x2000041C
m.UNLOCKED_SESSION = 0x2000041E
m.REQUEST_COUNTER = 0x20000424
m.FUN_FLASH_ERASE = 0x082062F8
m.FUN_FLASH_WRITE = 0x082060F0
loader = m.Loader()
# Native reset literals at 08201050..58 describe RAM .data and its flash source.
data_start, data_end, data_source = struct.unpack_from("<III", m.IMAGE, 0x201050)
assert (data_start, data_end, data_source) == (0x20000100, 0x20000240, 0x08208400)
loader.uc.mem_write(data_start, m.IMAGE[data_source - 0x08000000:data_source - 0x08000000 + data_end - data_start])
loader.call(m.FUN_INSTALL_LOADER)
assert [loader.r32(a) for a in (0x200010C0, 0x200010C8, 0x200010CC, 0x200010D8)] == [
    0x08201E2D, 0x08201DF1, 0x08201D85, 0x082017D9]
loader.w8(m.CURRENT_SESSION, 1)
loader.w32(m.REQUEST_COUNTER, 0x12345678)
seed_response = loader.request(m.FUN_SECURITY_ACCESS, [0x27, 1, 0])
seed = int.from_bytes(bytes.fromhex(seed_response[0])[2:], "big")
assert seed == ((0x12345678 ^ 0x55AA55AA) ^ 0x08207505)
key = m.security_key(seed)
assert loader.request(m.FUN_SECURITY_ACCESS, [0x27, 2, *key.to_bytes(4, "big")]) == ["67 02"]
assert loader.r8(m.UNLOCKED_SESSION) == 1

checksum = [0x31, 1, 0xFF, 1, 0x44, 8, 0, 0, 4, 0, 0, 0, 1, 0, 1]
assert loader.request(m.FUN_ROUTINE_CONTROL, checksum) == ["71 01 ff 01 00"]
checksum[-1] = 2
assert loader.request(m.FUN_ROUTINE_CONTROL, checksum) == ["71 01 ff 01 01"]
loader.w32(0x08200000, m.PROGRAMMING_MARKER)
erase = [0x31, 1, 0xFF, 0, 0x44, 8, 0, 0x10, 0, 0, 0, 0x10, 0]
assert loader.request(m.FUN_ROUTINE_CONTROL, erase) == []
assert loader.operations[-1] == {"operation": "erase", "address": "0x08001000", "length": 4096}
loader.call(m.FUN_FLASH_COMPLETE, 1, 0)
assert [r.hex(" ") for r in loader.responses] == ["71 01 ff 00 00"]
assert loader.request(m.FUN_REQUEST_DOWNLOAD, [0x34, 0, 0x44, 8, 0, 0x10, 0, 0, 0, 0x10, 0]) == ["74 20 08 02"]
for counter, data in enumerate((b"\xDE\xAD", b"\xBE\xEF")):
    assert loader.request(m.FUN_TRANSFER_DATA, [0x36, counter, *data]) == []
    assert loader.operations[-1] == {"operation": "write", "address": f"0x{0x08001000 + counter * 2:08X}",
                                     "length": 2, "data": data.hex(" ")}
    loader.call(m.FUN_FLASH_COMPLETE, 2, 0)
    assert [r.hex(" ") for r in loader.responses] == [f"76 {counter:02x}"]
assert loader.request(m.FUN_TRANSFER_EXIT, [0x37]) == ["77"]
assert [struct.unpack_from("<I", m.IMAGE, a)[0] for a in (0x209A0C, 0x209870, 0x209938)] == [500000, 0x7E0, 0x7E8]

# Execute native metadata packing, completion, readback and boot selection.
events = []
def hook(uc, address, size, data):
    if address == 0x08205110:
        events.append(["reset_token", hex(loader.r32(0x20000000))])
        loader._return(0)
    elif address == 0x082051FE:
        events.append(["application_entry", hex(uc.reg_read(UC_ARM_REG_R0))])
        loader._return(0)
loader.uc.hook_add(UC_HOOK_CODE, hook)
loader.w8(0x20001286, 1)
loader.w32(0x20000000, 0x4DF9123B)
assert loader.request(0x08202BE0, [0x11, 1]) == ["51 01"]
loader.call(0x08202198)
assert events[-1] == ["reset_token", "0x4df9123b"]
records = {0xF188: b"rusefi", 0xF189: b"M749ACT3", 0xF194: b"rusefi",
           0xF195: b"20261002", 0xF198: b"TESTER", 0xF199: b"20261002"}
for index, (did, value) in enumerate(records.items()):
    reply = loader.request(0x08202E9C, [0x2E, did >> 8, did & 255, *value])
    assert reply == ([bytes([0x6E, did >> 8, did & 255]).hex(" ")] if index < 5 else [])
    assert loader.r8(0x20000410) == (index == 5)
packed = bytes(loader.uc.mem_read(0x20001394, 256))
for did, offset in [(0xF199, 0), (0xF198, 9), (0xF195, 0x6D), (0xF194, 0x76), (0xF188, 0x9F), (0xF189, 0xD0)]:
    assert packed[offset:offset + len(records[did])] == records[did]
operation = loader.operations[-1]
assert operation["operation"] == "write" and operation["address"] == "0x0824E000" and operation["length"] == 256
loader.uc.mem_write(0x0824E000, bytes.fromhex(operation["data"]))
loader.responses = []
loader.call(0x08207600, 2, 0)
assert [r.hex(" ") for r in loader.responses] == ["6e f1 99"]
for did, value in records.items():
    assert loader.request(0x082033E4, [0x22, did >> 8, did & 255]) == [bytes([0x62, did >> 8, did & 255, *value]).hex(" ")]
assert loader.request(0x08202BE0, [0x11, 1]) == ["51 01"]
loader.call(0x08202198)
assert events[-1] == ["reset_token", "0xf9c74a52"]
loader.call(0x08204B7C)
assert events[-1] == ["application_entry", "0x8080000"]
loader.w32(0x20000000, 0)
before = len(events)
loader.call(0x08204B7C)
assert len(events) == before
loader.w32(0x08200000, 0x43A0C212)
loader.call(0x08204B7C)
assert events[-1] == ["application_entry", "0x8080000"]
assert loader.unmapped == []
print(json.dumps({"source_sha256": sha, "evidence": "native Thumb execution; physical flash I/O modeled",
                  "programming": "security, FF00/FF01, 34/36/37 passed", "metadata_dids": [hex(d) for d in records],
                  "events": events, "cold_boot_requires_normal_marker": True}, indent=2))
