# M74.9 memory layout and bootloader contract

## Scope

The replaceable main firmware occupies the lower-flash application window. The
resident bootloader uses a split layout with vectors below the application and
executable code in high flash. Persistent ECU identity and learned data also
reside in high flash. An application update must use a strict address whitelist;
it must not treat a full flash image as a single replaceable region.

All ranges below use inclusive CPU addresses.

## Memory layout

### SRAM prerequisite

The current linker script reserves low SRAM for the loader and places the
application process stack, data, BSS and heap at `0x20020000-0x2005FFFF`.
The MCU must therefore have at least 384 KiB of SRAM configured. A matching
loader profile and valid flash CRCs do not establish this prerequisite.

For this 4 MiB AT32, EOPB0 bits 2:0 select SRAM capacity: erased `111` selects
128 KiB and `010` selects 384 KiB. See Artery's
[AT32F435/437 memory configuration note](https://www.arterytek.com/file/download/1302),
section 2.2. The I832 bench required EOPB0 `FA` with complement `05` at
`0x1FFFC010` before the installed rusEFI image could start.

New software handles the erased OEM EOPB0 automatically in `m749PrepareRam`,
before `_crt0_entry` sets up the process stack or initializes data/BSS. It uses
MSP below `0x20020000`; the option-writing routine is copied from the addressed
software payload into `0x20001000-0x20001FFF`. That low RAM scratch area is used
only after leaving the resident loader, and the next reset reinitializes loader
RAM. The boot intent word at `0x20000000` remains separate.

Only the erased halfword `FFFF` may be programmed to `05FA`. The bootstrap
validates all flash CRCs first, preserves other USD bytes and never erases USD.
Valid settings with enough RAM are untouched. Failures request the programming
loader before C startup; success requests application return after option reload.
See the [CAN installation flow](cli-uploader.md) and
[original J-Link evidence](evidence/i832-jlink/README.md).

### Flash

AT32F435 distinguishes fast, zero-wait-state (ZW) flash access from slower,
non-zero-wait-state (NZW) access. Both regions can execute code, but NZW
instruction fetches can make the CPU wait. EOPB0 trades SRAM capacity against
the ZW region: the OEM setting provides 128 KiB SRAM and 512 KiB ZW flash;
our 384 KiB SRAM setting leaves 256 KiB ZW flash. See Artery's
[performance note, section 2](https://www.arterytek.com/file/download/1302).

The linker places interrupt, scheduler, trigger and frequently used engine code
in `.m749_fast_text`, starting at `0x08002000`. A link-time assertion keeps its
end below `0x08020000`, so it stays in ZW even with the supported 512 KiB SRAM
option (128 KiB ZW). Vectors and constructors occupy the application page at
`0x08001000`; read-only data follows the hot code, and remaining code stays in
the second software segment. The OEM reset entry remains `0x08080000`.


| Address range | Size | Contents | Update rule |
| --- | --- | --- | --- |
| `0x08000000-0x08000FFF` | 4 KiB | Bootloader vector page | Never erase or write during a main-firmware update. |
| `0x08001000-0x0805FFFF` | 380 KiB | Main application, first software CRC segment | Replaceable application software. |
| `0x08060000-0x0807FFFB` | 128 KiB minus 4 bytes | Calibration/data domain | Update separately from application software. |
| `0x0807FFFC-0x0807FFFF` | 4 bytes | Calibration CRC word | Recompute after changing calibration/data. |
| `0x08080000-0x080FFFFB` | 512 KiB minus 4 bytes | Main application, second software CRC segment | Replaceable application software; startup is at `0x08080000`. |
| `0x080FFFFC-0x080FFFFF` | 4 bytes | Application CRC word | Recompute after changing application software. |
| `0x08100000-0x081FFFFF` | 1 MiB | Reserved/erased flash | Preserve. |
| `0x08200000-0x08200FFF` | 4 KiB | Boot validity and activation state | Bootloader-managed; exclude from application payloads. |
| `0x08201000-0x0822DFFB` | 180 KiB minus 4 bytes | Bootloader code, configuration, and covered tail | Never erase or write during a main-firmware update. |
| `0x0822DFFC-0x0822DFFF` | 4 bytes | Bootloader CRC word | Preserve with the bootloader. |
| `0x0822E000-0x0824DFFF` | 128 KiB | Reserved/erased flash | Preserve. |
| `0x0824E000-0x0824EFFF` | 4 KiB | Protected high-flash data | Preserve. |
| `0x0824F000-0x0824FFFF` | 4 KiB | ECU identity data | Preserve. |
| `0x08250000-0x0825FFFF` | 64 KiB | Emulated EEPROM backing store 0 | Preserve. |
| `0x08260000-0x0826FFFF` | 64 KiB | Reserved/erased flash | Preserve. |
| `0x08270000-0x08273FFF` | 16 KiB | Emulated EEPROM backing store 1 | Preserve. |
| `0x08274000-0x08274FFF` | 4 KiB | High NVM | Preserve. |
| `0x08275000-0x082FFFFF` | 556 KiB | Unclassified flash | Preserve. |
| `0x08300000-0x0833FFFF` | 256 KiB | rusEFI MFS bank 0 | Runtime settings storage; preserve during firmware updates. |
| `0x08340000-0x0837FFFF` | 256 KiB | rusEFI MFS bank 1 | Runtime settings storage; preserve during firmware updates. |
| `0x08380000-0x083EFFFF` | 448 KiB | Unclassified flash | Preserve. |

For an application-only writer, the sole permitted destination window is
`0x08001000-0x080FFFFF`. The calibration subrange and both CRC trailers still
require separate handling inside that window. Broad address acceptance by the
bootloader is not permission to overwrite its vectors, code, state, or NVM.

### rusEFI settings storage

The board uses ChibiOS MFS on EFLD2 with two 256 KiB banks. Physical erase
sectors are 4 KiB: bank 0 uses sectors 256-319 and bank 1 uses sectors 320-383,
relative to the MCU bank-2 base at `0x08200000`. Startup checks the flash
descriptor's base, sector geometry and capacity before allowing MFS to mount.
The generic AT32 MFS configuration at sectors 0 and 32 must never be used here;
it overlaps OEM boot state and loader code.

Primary and backup settings are MFS records 1 and 2. They are not assigned one
per bank: MFS appends records and moves live records between banks during
garbage collection. Other enabled persistent pages use the same MFS backend.
Normal storage writes are deferred while the engine is running. The existing
self-stimulation exception and explicitly forced-save commands remain available.

These allocations are separate from the OEM calibration/data domain at
`0x08060000-0x0807FFFF`, whose contents and CRC remain unchanged by a tune burn.
The application linker and update payload whitelist exclude both MFS banks.

The MFS allocation repurposes previously unclassified flash; it does not prove
that the OEM software never uses these pages. Preserve their original contents
before deployment. MFS may erase/initialize the banks on first boot, even
before a tune burn. Hardware validation of retention, power interruption and
garbage collection is still required.

## Deployment artifacts and tools

**Current status:** the [Java CLI](cli-uploader.md) implements I865 OEM CAN
programming, verification and persistent application activation. Software checks pass;
real PCAN flashing and physical cold-boot validation
remain bench follow-ups. Direct MCU programming remains a separate bench route.

### Why there is no application `.bin`

A raw binary contains bytes without destination addresses. This application's
software occupies two separated ranges with calibration between them. Flattening
the software into one binary either fills that gap, risking calibration overwrite,
or removes the gap and places the second segment at the wrong address when written
as one contiguous block. A separate address manifest and a range-aware writer could
make split binaries usable, but this build does not implement that format.

Intel HEX and Motorola S-record files carry absolute addresses. Our `.hex` and
`.srec` software outputs omit the calibration range entirely while including all
bytes, including `0xFF` padding, inside the two software ranges. The writer must
also preserve the gap when erasing; an addressed file alone cannot make a tool's
mass-erase or whole-span erase operation safe.

Consequently, raw application BIN, generic DFU, and replacement bootloader targets
are disabled. Never use an old `rusefi.bin` from an earlier build, and never apply
the generic rusEFI `rusefi.bin` upload address `0x08000000` to this ECU: that address
belongs to the resident bootloader. The bundle excludes generic flash scripts and
updater launchers that do not implement this layout.

### Which artifact goes where

Build from the repository root:

```sh
bash compile_firmware.sh
```

| Artifact | Device destination, inclusive | Purpose |
| --- | --- | --- |
| `ext/rusefi/firmware/build/rusefi.hex` | `0x08001000-0x0805FFFF` **and** `0x08080000-0x080FFFFF` | Intel HEX application software, with the application CRC already included at `0x080FFFFC`. |
| `ext/rusefi/firmware/build/rusefi.srec` | The same two software ranges | Equivalent Motorola S-record payload. In bundles it is named `rusefi_<release>_<date>_re74.9_<signature>_<commit>_update.srec`. Choose one format; do not program both. |
| Separately generated `calibration.hex` or `calibration.srec` | `0x08060000-0x0807FFFF` only | An intentional calibration update, including its CRC at `0x0807FFFC`. Preserve existing calibration during a software-only update. |
| `rusefi.elf`, `.map`, `.list` | No deployment destination | Link/debug artifacts. The ELF lacks the final CRC trailers; do not use debugger ELF auto-download as a substitute for the generated HEX/SREC payload. |
| Full or autoupdate `.zip` | No deployment destination | Distribution containers. Extract the addressed payload; use the documented I865 CLI; archive naming does not establish hardware validation. |

The absolute addresses are already in HEX/SREC records: apply **no relocation or
base-address offset**. `0x08080000` is the executable startup address, not the
base at which to upload the entire file. Decode address records into bytes before
UDS TransferData; do not send the HEX/SREC text itself as firmware data.

For an uploader with explicit address/length fields, the software operation consists
of these two separate erase/download ranges. Lengths include CRC/padding bytes:

| Domain | Start address | Byte count | 4 KiB pages |
| --- | --- | --- | --- |
| Software, first range | `0x08001000` | `0x0005F000` (389,120) | 95 |
| Software, second range, including CRC | `0x08080000` | `0x00080000` (524,288) | 128 |
| Calibration, only when explicitly updating it | `0x08060000` | `0x00020000` (131,072) | 32 |

Do not replace the first two rows with one erase/download spanning
`0x08001000-0x080FFFFF`: that would include the retained calibration pages.

To prepare calibration, supply every byte from `0x08060000-0x0807FFFB`, obtained
from a complete retained or deliberately modified calibration image. This input
is exactly 131,068 bytes, without the existing CRC word:

```sh
python3 bin/m749_image.py --calibration --format hex calibration.bin calibration.hex
# Alternatively, generate S-records from the same input:
python3 bin/m749_image.py --calibration --format srec calibration.bin calibration.srec
```

Here `calibration.bin` is a raw **input data domain**, not a whole application
binary. The tool appends the new little-endian CRC and assigns the output addresses.
It never substitutes blank calibration during a software build.

### Toolsets and their current limits

| Route/toolset | Input or interface | Current use |
| --- | --- | --- |
| `compile_firmware.sh` and Python 3 `bin/m749_image.py` | ELF for software; complete raw data for calibration | Offline image preparation and CRC/range checks. These tools do not communicate with an ECU. |
| M74.9 Java tab or `bin/m749-cli.sh` / `.bat`, PEAK PCAN driver and native libraries | Physical CAN `0x7E0/0x7E8`, 500 kbit/s | Tab: identification. CLI: validated HEX/SREC upload, verification and activation; see [CLI guide](cli-uploader.md). |
| OEM resident loader plus an M74.9-specific ISO-TP/UDS writer | Decoded software or calibration HEX/SREC ranges over CAN | Implemented I865-specific CLI and application activation, pending live bench validation. Generic rusEFI OpenBLT/BootCommander is not this OEM protocol. |
| Artery AT-Link probe with Artery ICP Programmer over SWD | `rusefi.hex`; `calibration.hex` only for a separate calibration operation | Vendor toolset for evaluating direct MCU programming on the bench. No validated M74.9 programming profile, ECU connector pinout, or automatic activation procedure is supplied by this repository. |

Artery provides the [AT-Link and ICP tools](https://www.arterychip.com/en/support/tools.jsp?index=4).
Its [ICP Programmer manual](https://www.arterychip.com/download/TOOL/UM_ICP_Programmer_EN.pdf)
documents device connection, file information, sector/block erase, download, and
verification. The [AT-Link Console manual](https://arterychip.com/download/TOOL/UM_AT-Link_Console_Programmer_EN.pdf)
also documents HEX input, verification, and downloading without automatic sector
erase. These vendor capabilities do not establish that a default programming
profile preserves this ECU's OEM memory layout.

For a bench SWD evaluation, the required procedure is:

1. Establish the ECU's actual SWD connections and confirm the MCU is
   AT32F435ZMT7. Retain readable originals of software, calibration, and protected
   regions before any erase. Do not unlock protection as part of this procedure.
2. Load `rusefi.hex` and inspect the addresses against the two software rows above.
   Configure explicit erasure of only those pages and disable further automatic
   erasure during programming. If the tool cannot express or confirm those two
   disjoint erase ranges, do not use that profile.
3. Disable automatic reset/run and any serial-number, user-system-data,
   protection, or boot-configuration writes. Do not request mass erase.
4. Program the HEX records at their embedded addresses. Leave calibration and
   all regions outside the software ranges untouched. A calibration change is
   its own operation using only the calibration row above.
5. Read back every programmed range and compare it byte-for-byte with the
   decoded payload, including padding and CRC trailers. Validate the software
   CRC over both segments and the retained or updated calibration CRC separately;
   a generic programmer CRC display is not a substitute for these exact domains.
6. Treat successful programming/verification as bench evidence only. It does not
   finalize OEM validity state or prove that the resident loader will boot the
   application. Do not manually write the validity marker to bypass the missing
   activation sequence. Use the documented CRC-gated application path and verify its status.

There is therefore no supported one-command production upload to document yet.
The CAN loader sequence below defines the work still required of that uploader.

## Main firmware

The application software CRC covers two segments in this order:

```text
0x08001000-0x0805FFFF
0x08080000-0x080FFFFB
```

The little-endian CRC word is stored at `0x080FFFFC`. The application vector
table begins at `0x08001000`; its initial stack pointer is `0x20020000`, and its
reset vector points to Thumb code at `0x08080000`.

The calibration/data CRC covers `0x08060000-0x0807FFFB`, with its little-endian
CRC word at `0x0807FFFC`.

Both domains use CRC-32/MPEG-2:

| Parameter | Value |
| --- | --- |
| Polynomial | `0x04C11DB7` |
| Initial value | `0xFFFFFFFF` |
| Input reflection | None |
| Output reflection | None |
| Final XOR | `0x00000000` |

Erased bytes inside a CRC domain remain part of the CRC input and must retain
their intended value, normally `0xFF`.

## Bootloader

The bootloader is split across two flash areas:

```text
vector page:  0x08000000-0x08000FFF
loader body:  0x08201000-0x0822DFFB
loader CRC:   0x0822DFFC-0x0822DFFF
```

Its CRC input concatenates the vector page and loader body. The loader body
contains startup, CAN/ISO-TP transport, UDS programming services, security,
erase/write routines, checksum support, and flash/NVM geometry. Erased-looking
space through `0x0822DFFB` remains owned by the bootloader CRC domain.

The page at `0x08200000` holds boot validity and programming state. Its normal
marker is `0x43A0C212`; its programming marker is `0x2548A4D2`. Application
firmware must not manage this page as ordinary application data.

## Required application-to-bootloader contract

A replacement application must preserve these interfaces:

1. Place executable Thumb startup code at `0x08080000`.
2. Place a valid vector table at `0x08001000`, beginning with a suitable stack
   pointer and application reset vector.
3. Set Cortex-M VTOR to `0x08001000` early in startup, before relying on
   interrupts.
4. Implement UDS programming-session entry on physical diagnostic CAN IDs
   `0x7E0` request and `0x7E8` response at 500 kbit/s.
5. On an accepted `10 02` request, transmit the positive response before reset.
6. After that response completes, write programming token `0x4DF9123B` to
   `0x20000000` and issue a system reset.
7. Preserve token `0xF9C74A52` as the alternate/return boot intent if compatible
   timeout and recovery behavior is required.
8. Supply correct application and calibration CRC words at their fixed trailer
   addresses.

The bootloader does not require an application callback table. The fixed entry
address, vector relocation, SRAM token, and reset sequence form the interface.
An application can boot without the programming-session hook, but it then loses
the normal CAN route back into the resident loader.

## Distribution of the CAN flash-write chain

```mermaid
flowchart TD
    A[Application UDS and ISO-TP] -->|10 02 positive response| B[Application TX-completion callback]
    B -->|write 0x4DF9123B| C[SRAM 0x20000000]
    B -->|system reset| D[Boot vectors 0x08000000]
    D --> E[Loader body 0x08201000]
    E --> F[Loader SecurityAccess]
    F -->|31 FF00| G[Aligned flash erase]
    F -->|34, 36, 37| H[Download and flash write]
    F -->|31 FF01| I[Additive checksum comparison]
    G --> J[Whitelisted application pages]
    H --> J
```

| Stage | Location | Responsibility |
| --- | --- | --- |
| Application transport and handoff | Lower application flash, with diagnostic code near `0x080Bxxxx` in the OEM layout | Accept programming session, send response, set SRAM token, reset |
| Reset handoff | SRAM `0x20000000` | Carry boot intent across reset |
| Hardware reset entry | `0x08000000-0x08000FFF` | Enter the resident loader |
| Loader UDS server | Approximately `0x08201000-0x08204FFF` | Session, security, erase/download/write/checksum request handling |
| Loader flash layer | Approximately `0x08205F30-0x08207FFF` | Physical erase and programming, completion callbacks |
| Loader configuration | Approximately `0x082093D8-0x08209AF7` | CAN setup, permitted flash geometry, memory and EEPROM layout |
| Activation state | `0x08200000-0x08200FFF` | Valid/programming state used during update and boot |

The running application does not program its own flash through UDS services
`0x34`, `0x36`, and `0x37`. It only performs the reset handoff. The resident
loader performs destructive operations while executing from high flash.

The loader-side programming sequence is:

1. Enter the loader through the application handoff.
2. Complete loader SecurityAccess.
3. Erase 4 KiB-aligned application ranges with RoutineControl `31 01 FF 00`.
4. Open a download with `34 00 44`, a four-byte destination address, and a
   four-byte length.
5. Send TransferData blocks with service `0x36`. The first block counter is
   zero, and each block carries at most 2,048 data bytes.
6. Close the transfer with `0x37` after the final physical write completes.
7. Verify content. Routine `31 01 FF 01` compares a caller-supplied 16-bit
   additive checksum over the selected address range.
8. Complete the boot validity transition before requesting application boot.

Transfer exit confirms that no write is busy. It does not prove that the
declared byte count arrived, validate the application/calibration CRCs, or
complete activation. The [CLI activation sequence](cli-uploader.md) uses the
loader metadata handshake to arm the SRAM return token. The application checks
all CRC domains, restores the normal marker last and exposes status/CRC DIDs.
The CLI confirms those values after a second reset with the SRAM token cleared.
Software checks pass; electrical and power-cycle testing is pending.

## Writer requirements

- Enforce `0x08001000-0x080FFFFF` as the application-only write whitelist.
- Refuse any application update that overlaps the boot vector page, loader
  validity page, loader body, loader CRC, identity, EEPROM, high NVM, reserved,
  or unclassified regions.
- Treat application software and calibration as independent CRC domains.
- Use 4 KiB-aligned erase ranges and reconstruct every retained byte in an
  erased page.
- Require a complete payload for every declared download range.
- Read back or checksum every programmed range before activation.
- Do not report a successful update until CRC validation, validity-state
  finalization, reset, and application startup all succeed.

## Activation ABI

The application reserves 32 bytes at 0x0805FFE0 for `M749ACT3`. Its eight
little-endian words are 3934374D, 33544341, D7B6B894, 08060000, 4F256CD9,
08069000, E3186D26, 08060000: magic followed by three (loader CRC, retained
calibration start) pairs. Version 3 implies exactly three pairs and activation
protocol 1, removing ACT2's explicit count/protocol words to keep the same
reservation and software ranges. These bytes remain inside the software CRC.
Legacy `M749ACT1` supports I865; `M749ACT2` supports I812/I865. Old uploaders
reject ACT3, and the current uploader rejects old payloads on I832.

I812's original software/calibration split is 0x08069000. The replacement
software still ends its first range at 0x08060000 and preserves the entire gap
through 0x0807FFFF. I812 bytes 0x08060000-0x08068FFF are retained but are not
part of either replacement software CRC or I812 calibration CRC. Boot CRC
4F256CD9 selects calibration 0x08069000-0x0807FFFB; D7B6B894 and E3186D26 select
0x08060000-0x0807FFFB. The stored trailer remains at 0x0807FFFC. Unknown loader
CRCs cannot activate. Calibration generation/upload described above is I865-only.

DIDs F1A0-F1A3 return, respectively, ready status 0x4D740101,
software CRC, calibration CRC and the current persistent marker (big-endian).
The validity-page operation is confined to 0x08200000-0x08200FFF and publishes
0x43A0C212 only after CRC checks and restoration/verification of the page body.
Generic AT32 MFS remains disabled. Loader-managed metadata writes are described
in the [CLI guide](cli-uploader.md); they are never arbitrary payload ranges.

### Volatile boot diagnostic record

Firmware reserves `0x20000040..0x2000007F` as a 64-byte NOLOAD record, separate
from the boot token at `0x20000000`, option code at `0x20001000` and both stacks.
The I812/I832/I865 loader startup clears RAM starting at `0x20000100`; the record
must remain below that boundary. Only these supported loader profiles are
eligible for automatic diagnostic retrieval. Main flash and option write
whitelists are unchanged. Physical reset-retention qualification remains open.

The record is 16 little-endian words:

| Word | Meaning |
| --- | --- |
| 0 | Magic 3144424D (MBD1), published last |
| 1 | Version/size 00010040 |
| 2 | BootReason from firmware/boot_diagnostic.h |
| 3 | Expected software CRC |
| 4 | Boot sequence, incremented from a valid previous record |
| 5 | MCU DEBUG_ID |
| 6 | EOPB0 low halfword; access option high halfword |
| 7 | SLIB register snapshot |
| 8 | Flash status, refreshed when a reason is recorded |
| 9 | Marker on entry |
| 10 | SRAM token on entry |
| 11-13 | Computed software, calibration and loader CRCs |
| 14 | Bit 0: CRC checks computed; bit 1: checks valid |
| 15 | Integrity checksum |

Checksum starts with the magic; for words 1 through 14, rotate the accumulator
left by five bits then XOR the word. Updates first invalidate magic and publish
it only after the checksum and a memory barrier. This is corruption detection,
not authentication. Power loss can discard the record. Each application entry
starts a fresh sequence and snapshot; successful stages also update the reason.
All helpers used while flash is busy are inlined into the copied low-SRAM code.
