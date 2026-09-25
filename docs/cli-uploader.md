# M74.9 firmware uploader

New builds use M749ACT2: one software HEX/SREC payload supports the known I812
and I865 loader profiles. The CLI detects the target before erase; it preserves
the loader, identity, pairing data and the complete 0x08060000-0x0807FFFF gap.
I812 calibration starts at 0x08069000, I865 at 0x08060000. The application selects
the retained calibration CRC domain from the validated loader CRC. Unknown
I8xx profiles are rejected. Legacy M749ACT1 software remains I865-only.

Check a payload against the connected ECU without writing flash:

```sh
bash bin/m749-cli.sh --check-target ext/rusefi/firmware/build/rusefi.hex --slcan auto
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --slcan auto
```

The first command enters session 02 and checks authentication, loader sentinels,
payload compatibility, the retained CRC trailer and journal space. It sends no
erase/download, metadata write or reset; the loader can later time out back to
the OEM application. The second command performs the update. Both also accept
`--socketcan can0` on Linux or `--channel PCAN_USBBUS1` on native Windows. I865 paired authorization still uses
`--pair-file` or `--immo-backup` when needed; those credentials do not apply to
I812. I812 calibration-only uploads are deliberately rejected.

On 2026-09-24 the I812TA01_w2243v21 bench passed the complete SLCAN preflight
without a pairing credential. Its retained CRC was A5EC33E0 and journal slot 0
was empty. Subsequent identification confirmed OEM session 01 and the same ECU
identity. The new M749ACT2 image has passed software checks for both profiles;
physical upload and power-cycle validation of this build remain outstanding.

For a complete main-flash backup over SLCAN, Linux SocketCAN or Windows PCAN, see
[the flash-reader guide](cli-flash-reader.md).

The Java CLI programs the supported OEM resident loaders over standard CAN IDs
0x7E0/0x7E8 at 500 kbit/s. The Swing tab's **Flash rusEFI / Update rusEFI** button
uses the same uploader; see [the UI guide](../readme.md#m749-java-ui). The CLI
and the application activation routine pass their software checks. Normal
paired IMMO authorization and programming-session
entry have been validated on the restored I865 bench using native Windows PCAN.
The first live upload transferred every software byte and restored the normal
boot marker, but a scheduler-priority startup error prevented CAN readiness
confirmation. The corrected image (CRC D30E5B07) now reports all activation values correctly
after a bench power cycle, with the OEM loader regions preserved. Recovery from
power interruption during flashing remains unvalidated.

## Commands

Identify either OEM firmware or rusEFI without a power cycle:

```bat
bin\m749-cli.bat PCAN_USBBUS1
```

The CLI first makes read-only UDS queries on 7E0/7E8. An exact reply to private
DID F1A4 containing four ASCII bytes `rEFI` identifies rusEFI. Older M74.9
images are recognized by F1A0 = 4D740101 (M749ACT1 ready) or 4D740100
(M749ACT1 not ready). Silence and unrelated replies do not identify firmware.
Each optional query has a two-second timeout. If neither identity matches,
the CLI checks OEM session/software/part DIDs without changing sessions or authenticating.

When `--pair-file` or `--immo-backup` is supplied for programming, a recognized
M749ACT1 application skips OEM startup authentication: no power cycle is needed.
A generic rusEFI identity alone does not establish OEM-loader support, so that
case stops before authorization. Activation readiness remains a separate check
after programming; identity alone does not prove successful activation.

The shared rusEFI identity service is compiled with `EFI_UDS=TRUE` (default
false; enabled for the upstream M74.9 board). The board checkout's pinned
rusEFI submodule must include that service before building a new F1A4-capable
image. Existing M749ACT1 images work with this CLI through F1A0.

Every CLI output line starts with elapsed whole seconds since CLI startup,
for example `[  12] Transfer ...`. This also applies to errors and library logs.

Build with Java 11 and the checked-in Gradle wrapper:

```sh
bash compile_firmware.sh
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --dry-run
bash bin/m749-cli.sh --list
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --channel PCAN_USBBUS1
```

On native Windows, use `bin\m749-cli.bat` with the same arguments and Windows
file paths. Install the PEAK driver and matching PCAN-Basic/JNI libraries.
Windows DLLs cannot be used from a WSL JVM. Use SLCAN or SocketCAN for Linux live access.
The launchers retain the caller's working
directory and preserve quoted paths containing spaces.

### Linux SocketCAN

Configure the selected Linux CAN interface at 500 kbit/s before opening the CLI:

```sh
sudo ip link set can0 down
sudo ip link set can0 type can bitrate 500000
sudo ip link set can0 up
bash bin/m749-cli.sh --identify --socketcan can0
bash bin/m749-cli.sh --check-target rusefi.hex --socketcan can0
bash bin/m749-cli.sh --upload rusefi.hex --socketcan can0
```

`--socketcan IFACE` selects one explicit interface without scanning serial or
PCAN devices. It also works with `--read-flash`, `--read-byte` and `--read-pair`,
and with existing credential options. Use only one transport selector per command.
`--socketcan can0` alone performs read-only identification. `--list` continues
to list PCAN channels; use `ip -details link show` to inspect Linux interfaces.

The CLI uses rusEFI's JavaCAN backend, with its Linux x86_64 native library
included in the runtime. It does not configure bitrate or change interface
state. Other CPU architectures need the matching JavaCAN native library.
Under WSL, the adapter must be exposed as a CAN interface in the Linux kernel;
Windows PEAK DLLs do not provide that interface. SocketCAN receives diagnostic
and paired-authorization frames on one socket and disables own-message echo.
Physical SocketCAN ECU transfers still need bench validation.

The launcher builds `:custom-java-ui:installM749Cli`, then runs
`com.rusefi.m749.M749Cli` directly with the installed runtime JARs. A missing
adapter/native library does not prevent `--help` or `--dry-run`.

Software HEX and SREC are equivalent inputs. Unsupported BIN, ELF, sparse images,
overlaps, mixed software/calibration domains, bad record checksums, missing
terminators, invalid vectors and bad CRC trailers are rejected before opening
an adapter. Software upload also requires the `M749ACT1` or `M749ACT2` descriptor emitted by
the current firmware build. Supported OEM full-flash BIN files use the restore
path below. PCAN uploads accept a named channel or `--channel auto` when exactly one
available channel exists; upload never tries another ECU after a failure.

## Shared transport options

Every hardware action uses the same connection parser and defaults, including
`--identify`, implicit identification, `--read-flash`, `--read-byte`, `--read-pair`,
`--check-target`, `--upload` and `--write-flash`. Offline `--export-pair` needs no
connection options; `--list` lists PCAN channels without opening them.

| Option | Default and behavior |
| --- | --- |
| Connector | `--slcan auto` if no selector is supplied |
| `--slcan PORT` | Explicit serial port, or `auto` requiring one detected SLCAN adapter |
| `--channel CHANNEL` | Explicit available PCAN channel, or `auto` requiring one available channel |
| `--socketcan IFACE` | Explicit Linux interface already up at 500 kbit/s; no `auto` |
| `--serial-baud BAUD` | 115200, SLCAN only; 9600..4000000 for explicit ports |
| `--slcan-bus BUS` | 1, SLCAN only; tagged adapters can use 1..3 |
| `--block-size BS` | 16 received ISO-TP frames per block; 0..255, zero means unlimited |
| `--stmin MS` | PCAN/SocketCAN: 1 ms; SLCAN: at least 3 ms, increased for slower serial baud. Explicit range 0..127 |

SLCAN auto discovery uses 115200; choose an explicit port for a different baud.
Duplicate options, conflicting selectors, invalid values and serial settings on
nonserial connectors are rejected before adapter access. PCAN auto never tries
multiple ECUs to find one willing to accept a write. A positional PCAN channel
remains an alias for `--channel CHANNEL`; no arguments now identify via SLCAN auto.

```sh
bash bin/m749-cli.sh --identify --slcan /dev/ttyACM0 --serial-baud 57600 --slcan-bus 2
bash bin/m749-cli.sh --check-target rusefi.hex --slcan /dev/ttyACM0 --serial-baud 57600 --slcan-bus 2
bash bin/write-flash.sh rusefi.hex --slcan /dev/ttyACM0 --serial-baud 57600 --slcan-bus 2
bash bin/write-flash.sh rusefi.hex --channel auto --block-size 8 --stmin 2
```

The UI's Connector row and transport settings feed this same connection model
for identification, bundled updates, backup reads and selected-file writes.
`--check-target` also accepts `--calibration`, matching calibration upload mode.

## Selected-file writes

`write-flash.sh` / `write-flash.bat` invoke `m749-cli --write-flash FILE`.
The action shares the uploader with `--upload` and the UI's **Write firmware...**
button. With no transport selector, `--write-flash` selects exactly one SLCAN
adapter automatically. `--upload` uses the same options and defaults.
Quoted filenames retain spaces. `--dry-run` validates without opening an adapter.

```sh
bash bin/write-flash.sh rusefi.hex --dry-run
bash bin/write-flash.sh rusefi.srec --slcan /dev/ttyACM0
bash bin/write-flash.sh "OEM full backup.bin" --dry-run
bash bin/m749-cli.sh --check-target "OEM full backup.bin" --socketcan can0
bash bin/write-flash.sh "OEM full backup.bin" --channel PCAN_USBBUS1
```

HEX/SREC software updates use the existing activation contract and preserve
calibration. OEM BIN input must contain exactly 0x3F0000 bytes mapped from
0x08000000 and have a supported I812/I865 loader CRC, valid boot/software/
calibration CRCs and the corresponding application vectors. I812 calibration
starts at 0x08069000; I865 starts at 0x08060000. Partial 2 MiB dumps, unsupported
profiles, rusEFI BIN backups and corrupt images are rejected before adapter
access. Use addressed HEX/SREC for rusEFI updates. `--calibration` is incompatible
with OEM BIN because restoring OEM software requires its matching calibration.

OEM restore writes only 0x08001000..0x080FFFFF, including calibration. The rest of
the source file is not copied to the ECU: the boot page, resident loader, identity,
paired credentials, EEPROM and rusEFI storage remain those of the connected ECU.
The existing programming-history transaction still appends one record and the
OEM application manages its boot-validity state. This is an application restore,
not a whole-chip clone. Use a backup appropriate for this ECU and calibration.
The connected loader profile must match the backup; an I812/I865 mismatch stops
before any erase. Credential options remain optional and retain their existing
I865-only scope; the input BIN is not implicitly used as a credential.

Transfer verification uses additive block checksums, or exact individual-byte
comparisons with `--verify-bytes`. The OEM completion path then verifies the six
programming records, requests reset and requires F186=01, confirming return to
the application. OEM applications do not expose rusEFI's activation CRC/marker
DIDs, so this path does not claim those checks or a cold power-cycle test.
OEM BIN restore has automated protocol coverage and offline validation against
both supported full backups; live CAN restoration and cold boot remain untested.

For the paired I865 bench that rejects programming entry with NRC 22, add the
original full backup as the credential source:

```bat
bin\m749-cli.bat --upload rusefi.hex --channel PCAN_USBBUS1 --immo-backup "bin\Read_FULLFLASH_I865LB52_w2404b1____(240626_103727).bin"
```

`--immo-backup` is optional and currently accepts only the validated backup
with SHA-256 `ac052cd5cacf0385b4c2de794f6b1ad476e3f9f1badab5b8c54619854e428b39`.
It reads the paired key locally; it does not program the backup, change pairing
or disable the immobilizer. Its file length and hash are checked before opening
the adapter, including during `--dry-run`. Key bytes are never printed.

If the ECU already reports programming session 02, the CLI proceeds directly
to the existing loader checks. Otherwise wait for `IMMO listener ready`, then
cycle the ECU bench power while leaving the CAN adapter connected. The listener waits up
to 60 seconds, proves the normal 713/714 exchange, sends the encrypted permission
message and verifies its acknowledgement before allowing the uploader to run.
An existing peer, wrong proof, wrong acknowledgement or timeout stops the attempt
before any flash request. It sends no ignition/cranking simulation or key-learning
messages. This is a paired bench entry route, not a universal OEM credential.

## Single-byte reads and indexed pair files

Read one byte through the loader's FF01 checksum comparison:

```bat
bin\m749-cli.bat --read-byte 0x08000004 --channel PCAN_USBBUS1
```

Addresses are hexadecimal, with optional `0x`, within `0x08000000..0x083EFFFF`.
The command checks the current session, requests session 02 if needed, completes
loader SecurityAccess and checks the I865 loader profile. Accepted programming
entry can reset the ECU into its loader. Rejected entry stops the operation.
No erase, download, metadata write or recovery reset is requested.

The reader tries one-byte sums 0..255. After a match it requires a deliberately
wrong candidate to fail before returning the value. This takes at most 257
checksum requests per unknown byte, plus setup. A timeout, malformed reply or
failed control stops the read; it is never recorded as a zero or FF byte.

For a locked application, add `--pair-file KNOWN.pair` or
`--immo-backup PAIRED_FULLFLASH.bin` to a read command. The CLI then runs its
60-second startup authorization listener and immediately enters the loader on
the same CAN connection. Follow the prompt before cycling ECU power. See the
[Windows pairing-file walkthrough](../readme-grab-key.md) for the full sequence.
A valid existing credential is required for this authorization step.

Read the 24 authentication bytes, saving progress after each verified byte:

```bat
bin\m749-cli.bat --read-pair ecu.pair --channel PCAN_USBBUS1
```

Repeat the same command to resume. Existing entries are checked against the
connected ECU before new bytes are added; a mismatch leaves the file unchanged.
New bytes are written through a flushed temporary file and atomic replacement.
An interrupted read retains the last completed checkpoint. `.pair` files are
local credentials and excluded from git by default. Progress prints indices
and counts, without printing the stored key values.

The UTF-8 file has a version/profile header followed by decimal-index/hex-byte
pairs. For example, this file knows only two bytes; all others are unknown:

```text
M749PAIR1 I865
0=00
23=FF
```

| Decimal indices | Contents | ECU addresses |
| --- | --- | --- |
| 0..15 | Paired key | 0x08274000..0x0827400F |
| 16..23 | I865 reference | 0x0804C2B4..0x0804C2BB |

Omitted indices are unknown. Zero and FF are ordinary known byte values.
Blank lines and `#` comments are allowed; duplicates, invalid bytes, unsupported
headers and out-of-range indices are rejected. A complete file is required for
authentication, including during an upload dry run:

```bat
bin\m749-cli.bat --upload rusefi.hex --channel PCAN_USBBUS1 --pair-file ecu.pair
```

`--pair-file` and `--immo-backup` are mutually exclusive. To convert the existing
validated full backup without CAN access:

```bat
bin\m749-cli.bat --export-pair ecu.pair --immo-backup "bin\Read_FULLFLASH_I865LB52_w2404b1____(240626_103727).bin"
```

Export fills missing entries in an existing compatible file and rejects
conflicting known bytes before saving. The full backup is unnecessary after
export. CAN reads still need loader access: they cannot obtain the missing key
to satisfy a locked application's initial authorization condition.

## Calibration

I865 calibration is a separate operation:

```sh
python3 bin/m749_image.py --calibration --format hex calibration.bin calibration.hex
bash bin/m749-cli.sh --upload calibration.hex --calibration --dry-run
bash bin/m749-cli.sh --upload calibration.hex --calibration --channel PCAN_USBBUS1
```

Calibration upload requires activation-capable software already installed.
Its retained software CRC is checked after boot. Software upload preserves
calibration and checks its retained CRC after boot. An invalid retained domain
prevents firmware activation; the CLI cannot calculate a remote CRC through the
loader's additive-checksum-only service before programming.

## Transaction and completion

This sequence describes rusEFI HEX/SREC updates. OEM BIN completion uses the
application-session check described under selected-file writes above.

1. Validate the entire input and optional pairing credential. If requested, complete
   normal IMMO authorization. Enter programming session 02, wait for the
   application handoff, and authenticate with the I865 loader polynomial.
2. Compare individual loader sentinel bytes and its stored CRC word. Check the
   activation ABI for a calibration update, read the retained domain's CRC
   trailer, and check space for the required loader metadata transaction.
3. Erase only the chosen domain, in 4 KiB requests. Software uses two disjoint
   ranges, preserving the calibration gap. Download each range with 34/36/37,
   honoring negotiated block length and starting the counter at zero.
4. Verify every range using FF01 block sums. `--verify-bytes` adds exact
   individual-byte comparisons instead; it is substantially slower. Block sums
   alone are not CRCs or proof of byte equality.
5. Complete the minimal six-DID loader metadata handshake and verify those
   records, then request reset. The OEM loader writes the SRAM return token.
6. Before actuator initialization, the new application checks the software,
   calibration and supported loader CRCs. It preserves the validity page's
   remaining bytes and writes the normal marker last. Failure resets into the
   resident loader without starting engine control.
7. Read application activation status, both CRCs and the persistent marker.
   Reset again with a cleared SRAM token, then recheck. Only this complete
   sequence prints `Upload complete` and returns exit code 0.

Errors return 1, usage errors 2. A failed write/erase/verification is not retried
and does not trigger a cleanup reset. An interrupted update can leave incomplete
software in flash; retry requires another complete, validated image. A successful
dry run returns 0 but does not claim device compatibility or flashing success.

## Programming-session rejection

`Stopped while entering programming session: UDS 10 rejected: NRC 22`
means the ECU rejected `10 02` because entry conditions were not met. This
happens before loader authentication, erase, download or activation. That
attempt sent no flash erase or programming request; it does not establish the
state left by any earlier attempt. The CLI stops without trying to bypass the
condition or sending a recovery reset.

Confirm the installed firmware using identification only:

```bat
bin\m749-cli.bat PCAN_USBBUS1
```

Both OEM and rusEFI identification use read-only DID queries. Bare/positional
identification and `--identify` use the same transport defaults and do not enter
an OEM session or authenticate.
It sends no flash erase/download requests. Capture its output, particularly
session/authentication results and DID F189. NRC 22 alone does not identify a
specific unmet condition. The replacement firmware checks that the engine is
stopped; the OEM application has separate admission checks, so do not infer
an engine-running diagnosis from that code alone on an OEM bench ECU.

On the restored I865 bench, session 03 and its seed/key authentication succeeded
while an immobilizer-related programming-entry condition remained unsatisfied.
Diagnostic authentication does not satisfy that separate condition.
The normal OEM authorization exchange is now recovered and validated live;
use `--pair-file` or `--immo-backup` above for this paired bench. The CLI
does not automatically retry a rejected session or change admission state via
a debug probe.

## Minimal metadata handshake; history management TODO

History is not required for transferring bytes. In this loader, however, the
six-DID write transaction (F188, F189, F194, F195, F198, F199) arms the
return-to-application path used by reset 11 01. Reset alone after an erase stays
in the loader. These programming records are distinct from ECU identity/VIN.

The CLI retains existing programming-record bytes and appends them through the
OEM service. If the history is empty, as in the supplied full I865 image, it
creates a record identifying `fw-m74.9 CLI`, the software CRC and today's UTC
date. This initializes programming metadata; it does not infer an OEM build date
or alter the ECU identity records.

The loader appends 256-byte records in 0x0824E000-0x0824EFFF, without erasing
that page. Before any application erase, the CLI requires the next loader-selected
slot to be fully FF and refuses a full/partially written journal. Full-page
compaction, history browsing/export and a metadata-free activation route remain
TODOs. The uploader does not erase protected NVM to make space.

The payload whitelist still excludes every loader/metadata/NVM region. The only
additional writes are the resident loader's own programming-state/history
transactions and the application's narrow, CRC-gated validity-page transaction.

## Power interruption and recovery

This updater is not yet validated for power-loss recovery. It updates the sole
application in place; there is no second image or automatic rollback.

Before the first application erase, the OEM loader changes the persistent
marker to programming state. During erase/download/verification, the intended
recovery path is to remain in the preserved OEM loader and retry the complete
image over CAN. Firmware transfers do not resume at the last completed block.
This is the expected protocol behavior, not a successful hardware power-cut test.

Interruption during programming-record writes can leave metadata that the CLI
refuses to reuse. During final activation, the application erases and restores
a 4 KiB marker page using a RAM copy, then writes the normal marker last.
Loss of power during this operation can lose the saved page or leave a partial
marker. Automatic CAN recovery is not established for those states; J-Link
recovery may be necessary. The upload payload excludes the OEM loader, but that
alone does not prove that every power-cut state is recoverable over CAN.

The bench has demonstrated J-Link OEM restoration following a firmware startup
failure. That is separate from power-cut validation. Recovery testing must
cover application erase, transfer, metadata and marker publication before this
can be described as safe for unattended field updates.

## Validation and limits

The Java M749 suite covers image preflight, ISO-TP, checksum byte
reads, protected ranges, metadata capacity, activation status, IMMO authentication, rejection,
timeouts and pair-file persistence/resume. A live Windows PCAN byte read
returned 01 at 0x08000004 after loader authorization. Offline export recovered
all 24 expected bytes, and Windows upload preflight accepted the compact file
with software CRC 59F9BC66. Live CAN capture saved all 24 bytes to ecu-can.pair;
a second pass verified every saved byte. All captured bytes match the original
backup. Interrupted-read resume has unit coverage; the live second pass used
a complete file. Memory-contract and activation checks also pass. The first hardware transfer matches the complete input image
byte-for-byte, and the normal boot marker is set. The original readiness check failed
after a scheduler-priority startup error. The corrected D30E5B07 image now
reports readiness, both expected CRCs and the normal marker after a bench
power cycle; its loader regions match the original backup. The full corrected
upload transaction was not observed here. Interrupted-power recovery remains
unvalidated.

OpenBLT replacement and generic AT32 MFS layouts are incompatible with this
resident-loader contract. The local narrow bank-2 driver follows the register
sequence in [Artery's SDK](https://github.com/ArteryTek/AT32F435_437_Firmware_Library/blob/master/libraries/drivers/src/at32f435_437_flash.c)
and [AT32F435/437 reference manual](https://www.arterychip.com/download/RM/RM_AT32F435_437_V2.07_EN.pdf).

Bench follow-up: full-transfer PCAN flow control and flash timing, physical cold
boot, interrupted-power recovery, and preservation of protected ECU data. Offline tests cannot establish those hardware properties.
