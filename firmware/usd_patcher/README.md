# I862 USD patcher

Standalone RAM maintenance payload for protected I862BA02 options. This is
separate from normal firmware startup and upload. It changes EOPB0 from 00FF
(128 KiB SRAM) to 05FA (384 KiB), preserving raw FAP=FFFF and every other USD
halfword. Already-configured 05FA returns without erasing.

Live erase/restore and retained-result retrieval passed on one protected I862
unit on 2026-11-09. After a user-reported physical power cycle, stock
application diagnostics and duplicate full-page reads confirmed option
retention on this unit. High-RAM runtime operation, watchdog timing margins
and recovery from interrupted programming are not qualified. Use the [maintenance guide](../../docs/usd-patcher.md) for the CLI
and console workflow, requirements and failure handling.

## Build and packaging

Requires Arm GNU Toolchain 14.2.Rel1 (GCC 14.2.1):

```sh
make -C firmware/usd_patcher CROSS_COMPILE=/path/to/bin/arm-none-eabi-
make -C firmware/usd_patcher check-bundle CROSS_COMPILE=/path/to/bin/arm-none-eabi-
```

The build produces a 1804-byte raw image. Java bundles
`java-custom-ui/src/main/resources/usd_patcher_2001ba00.bin` and checks its
length and SHA-256 before adapter access. Expected SHA-256:
`b112975af46925c3ca1f651a18a97fd68d733f38b3fa7d050eea3852428b213e`.
The source rebuild must match that resource. Deliberate payload updates require
reviewing the resource and Java integrity constant together. No ARM toolchain
is needed for the ordinary Java/console build. Do not package a shortened
poll-limit test build.

## Runtime contract

| Region | Address / size |
| --- | --- |
| Code / inspect entry | 2001BA00, exact BIN length |
| Apply entry | 2001BA04 |
| Original snapshot | 20019400, 4096 bytes |
| Inverse snapshot | 2001A400, 4096 bytes |
| Apply command | 2001B400, 32 bytes |
| Current result | 2001B440, 64 bytes |
| Temporary vectors | 2001B600, 256 bytes |
| Private stack | 2001D400..2001E3FF |
| Reset-retained result | 20000040, 64 bytes |

Requires privileged thread mode on MSP, aligned caller SP in
2001E440..20020000, MCU 70084540, loader CRC D7B6B894, application CRC B167C266,
and application static-RAM boundary 20019320. The payload computes both whole
CRC domains and compares their stored words. FAP must be active, raw FAP FFFF,
USDERR clear, debugger inactive, SLIB disabled and both flash banks idle.
A matching software name alone is insufficient.

Inspection snapshots the entire raw 4096-byte USD page and its inverse, checks
defined-field encodings and returns to the caller. Table 5-5 reserved byte
ranges 012..013, 01C..033 and 044..04B bypass format and all-FFFF erase checks.
Their full raw halfwords are saved, then written unchanged if erase changed
them; already-matching reserved halfwords need no write. Defined fields still
use normal data/complement programming. Exact final readback includes every
reserved byte; an ignored or transformed reserved write fails verification.
Hardware acceptance of arbitrary reserved raw writes is not established.
No option unlock/erase/program occurs in inspection. Apply requires a separate
32-byte command matching the freshly captured page CRC and original EOPB0.
Only verified success resets into the resident loader. Post-erase failures park
in RAM while feeding the independent watchdog; there is no CAN recovery server.
External/window watchdog behavior and power-loss recovery are not provided.

`payload.h` defines the command/result ABI. Words are little endian; checksums
use CRC-32/MPEG-2 (polynomial 04C11DB7, initial FFFFFFFF, no reflection or final
XOR). The retained record replaces the normal boot diagnostic and does not
contain a sequence number. It cannot establish its own age or survive power
loss reliably. The complete original page must be saved off-device before
apply; the loader clears the high-RAM snapshot during reset.
