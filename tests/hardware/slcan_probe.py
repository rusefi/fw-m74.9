#!/usr/bin/env python3
"""Headless TS-over-SLCAN probe; requires pyserial.

Example (the command changes live ECU state, but does not burn settings):
  python slcan_probe.py --port COM116 --command "enable self_stimulation" \
      --gap-ms 20 --text --trace stimulation.jsonl

Without --command, only the firmware signature is requested. Commands are sent
once, never retried. A timeout does not establish whether a command executed.
Use this with the console closed so it cannot reopen the adapter.
"""

import argparse
import json
import struct
import time
import zlib

import serial


class Probe:
    def __init__(self, port, trace, timeout, gap_ms):
        self.serial = serial.Serial(port, 115200, timeout=0.01)
        self.trace = trace
        self.timeout = timeout
        self.gap = gap_ms / 1000
        self.partial = bytearray()

    def record(self, kind, **values):
        if self.trace:
            self.trace.write(json.dumps(dict(time=time.monotonic(), kind=kind, **values)) + "\n")
            self.trace.flush()

    def send_line(self, value):
        self.record("tx", line=value)
        self.serial.write((value + "\r").encode("ascii"))
        self.serial.flush()

    def read_line(self, deadline):
        while time.monotonic() < deadline:
            char = self.serial.read(1)
            if char == b"\x07":
                self.record("bell")
                raise RuntimeError("SLCAN adapter rejected a request (BELL)")
            if char == b"\r":
                value = self.partial.decode("ascii")
                self.partial.clear()
                self.record("rx", line=value)
                return value
            if char and char != b"\n":
                self.partial.extend(char)
        raise TimeoutError("SLCAN response timeout")

    def drain(self, seconds):
        deadline = time.monotonic() + seconds
        try:
            while True:
                self.read_line(deadline)
        except TimeoutError:
            pass

    def open(self):
        self.send_line("C")
        self.drain(0.2)
        self.partial.clear()
        self.send_line("V")
        deadline = time.monotonic() + self.timeout
        while True:
            version = self.read_line(deadline)
            if version.startswith(("V", "WeAct")) or "github.com" in version:
                break
        print("Adapter:", version, flush=True)
        # This CANable revision omits setup acknowledgements. A fresh V reply
        # checks serial responsiveness after each setup command.
        for command in ["S6"] + (["A1"] if "WeAct" in version else []) + ["O"]:
            self.send_line(command)
            self.drain(0.1)
            self.send_line("V")
            deadline = time.monotonic() + self.timeout
            while self.read_line(deadline) != version:
                pass

    def send_frame(self, data):
        self.send_line("t710" + str(len(data)) + data.hex().upper())

    def read_frame(self, deadline):
        while True:
            line = self.read_line(deadline)
            if line.startswith("t720"):
                dlc = int(line[4], 16)
                if not 1 <= dlc <= 8 or len(line) != 5 + 2 * dlc:
                    raise ValueError("Invalid CAN reply: " + line)
                return bytes.fromhex(line[5:])

    def request(self, payload):
        packet = struct.pack(">H", len(payload)) + payload + struct.pack(">I", zlib.crc32(payload))
        if len(packet) > 4095:
            raise ValueError("Request exceeds the ISO-TP length limit")
        self.record("request", payload=payload.hex())
        deadline = time.monotonic() + self.timeout
        if len(packet) <= 7:
            self.send_frame(bytes([len(packet)]) + packet)
        else:
            self.send_frame(bytes([0x10 | (len(packet) >> 8), len(packet) & 255]) + packet[:6])
            flow = self.read_frame(deadline)
            if flow[:3] != b"\x30\0\0":
                raise ValueError("Expected unrestricted ISO-TP flow control: " + flow.hex())
            for index, offset in enumerate(range(6, len(packet), 7), 1):
                if index > 1 and self.gap:
                    time.sleep(self.gap)
                self.send_frame(bytes([0x20 | (index & 15)]) + packet[offset:offset + 7])

        result = bytearray()
        remaining = 0
        sequence = 1
        # The firmware can split one TS response into multiple ISO-TP packets.
        while True:
            frame = self.read_frame(deadline)
            kind = frame[0] >> 4
            if kind == 0:
                size = frame[0] & 15
                if remaining or size > len(frame) - 1:
                    raise ValueError("Unexpected ISO-TP single frame")
                result.extend(frame[1:1 + size])
            elif kind == 1:
                if remaining or len(frame) < 2:
                    raise ValueError("Unexpected ISO-TP first frame")
                remaining = ((frame[0] & 15) << 8) | frame[1]
                chunk = frame[2:2 + remaining]
                result.extend(chunk)
                remaining -= len(chunk)
                sequence = 1
                self.send_frame(bytes.fromhex("3000000000000000"))
            elif kind == 2:
                if not remaining or frame[0] & 15 != sequence:
                    raise ValueError("Unexpected ISO-TP consecutive frame: " + frame.hex())
                sequence = (sequence + 1) & 15
                chunk = frame[1:1 + remaining]
                result.extend(chunk)
                remaining -= len(chunk)
            else:
                raise ValueError("Unexpected ISO-TP frame: " + frame.hex())
            if len(result) >= 2:
                size = int.from_bytes(result[:2], "big")
                if len(result) >= size + 6:
                    if remaining or len(result) != size + 6:
                        raise ValueError("TS response length mismatch")
                    body = bytes(result[2:-4])
                    if zlib.crc32(body) != int.from_bytes(result[-4:], "big"):
                        raise ValueError("TS response CRC mismatch")
                    self.record("response", payload=body.hex())
                    return body

    def close(self):
        try:
            self.send_line("C")
        finally:
            self.serial.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--command")
    parser.add_argument("--gap-ms", type=float, default=0)
    parser.add_argument("--timeout", type=float, default=10)
    parser.add_argument("--poll-count", type=int, default=0,
                        help="Read the signature this many times after the command")
    parser.add_argument("--trace")
    parser.add_argument("--text", action="store_true")
    args = parser.parse_args()
    if args.gap_ms < 0 or args.timeout <= 0 or args.poll_count < 0:
        parser.error("gap-ms and poll-count must be nonnegative; timeout must be positive")
    trace = open(args.trace, "w", encoding="ascii") if args.trace else None
    probe = None
    try:
        probe = Probe(args.port, trace, args.timeout, args.gap_ms)
        probe.open()
        print("Signature:", probe.request(b"S"), flush=True)
        failed = False
        if args.command:
            try:
                start = time.monotonic()
                response = probe.request(b"E" + args.command.encode("ascii"))
                print("Command response:", response.hex(), "elapsed:", round(time.monotonic() - start, 3), flush=True)
                failed = response != b"\0"
            except (TimeoutError, ValueError, RuntimeError) as error:
                probe.record("failure", message=str(error))
                print("Command failed:", error, flush=True)
                failed = True
        if args.text:
            print("Text:", probe.request(b"G"), flush=True)
        for index in range(args.poll_count):
            time.sleep(0.1)
            print("Poll", index + 1, probe.request(b"S"), flush=True)
        return int(failed)
    except (TimeoutError, ValueError, RuntimeError, serial.SerialException) as error:
        if probe:
            probe.record("failure", message=str(error))
        print("Probe failed:", error, flush=True)
        return 1
    finally:
        if probe:
            probe.close()
        if trace:
            trace.close()


if __name__ == "__main__":
    raise SystemExit(main())
