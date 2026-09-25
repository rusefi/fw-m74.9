# Complete main-flash backup

The M74.9 tab's **Read flash...** button runs this same reader. Select the shared
Connector and transport settings, choose a destination in the save dialog and watch Messages
for verified byte counts, percentage, speed and SHA-256. The dialog supports
`--resume` and `--helper-running`; the UI adds `--reset-after` on successful reads.
Completed backups are never overwritten, even if an existing path is selected.
This helper requires OEM application support; it is not a rusEFI backup service.

`m749-cli --read-flash` reads M74.9 main flash using the bundled RAM helper.
It supports Linux SocketCAN, SLCAN serial adapters on Linux, macOS and Windows, or PCAN with
native Windows Java. The default range is `0x08000000..0x083EFFFF`: 4,128,768
bytes (4,032 KiB), including both banks, boot code, application, calibration,
identity and main-flash NVM. This profile requires the corresponding flash
capacity; it does not include separate user-system-data/option-memory areas.

The command uploads code to RAM and takes over diagnostic communication. It
does not issue flash erase/program commands or disable flash protection.
Application session `60` must be available on the target. A rejected session
or security response stops the operation. A resident loader in session `02`
does not provide this bootstrap. Normal application operation stops while the
RAM helper runs.

Transport selection, serial settings and flow-control defaults are shared with
all other hardware commands; see [shared options](cli-uploader.md#shared-transport-options).

## Run

Confirm the connected ECU before reading:

```sh
bash bin/m749-cli.sh --identify --slcan /dev/ttyACM0
```

This queries OEM session/software/part DIDs and replacement-firmware identity
DIDs without changing sessions, unlocking security or uploading RAM. At least
one positive DID response is required for success. Adapter discovery alone is
not ECU identification. `--identify` also accepts `--socketcan can0`, `--slcan auto` or a PCAN
`--channel`, and creates no backup files.

No arguments are needed with these wrappers:

```sh
bin/read-flash.sh
```

```bat
bin\read-flash.bat
bin\read-flash-pcan.bat
```

`read-flash.sh` and `read-flash.bat` scan for SLCAN using the shared
`SlcanPortScanner`. The scanner probes serial ports, classifies TunerStudio
consoles separately, and completes one scan before opening the selected CAN
adapter. Exactly one detected SLCAN port is required. No adapter or multiple
adapters produces an error; use `--slcan PORT` to select explicitly. Detection
uses the shared scanner's 115200 serial setting; other UART speeds require an
explicit port. Discovery identifies the adapter, not the ECU connected to it.

`read-flash-pcan.bat` selects the sole available Windows PCAN channel. With
multiple available channels, use `read-flash.bat --channel PCAN_USBBUS1`.

The default filename is `m749-full-YYYYMMDDTHHMMSSsssZ.bin` (UTC) in the current
working directory. The chosen path and adapter are printed. The wrappers forward
an optional output filename and read options, preserving quoted paths:

```sh
bin/read-flash.sh "my backup.bin" --slcan /dev/ttyACM0
bin/read-flash.sh "my backup.bin" --resume --helper-running
bin/read-flash.sh --help
```

Resume always requires the original filename. A no-argument launch starts a new
backup; it never guesses which previous partial backup to resume. The equivalent
direct invocation is `m749-cli --read-flash`, with optional `--slcan auto` or
`--channel auto`. The existing `m749-cli` command without arguments retains its
identification behavior.

Use the existing launchers from this checkout. They build the Java CLI and
preserve quoted paths. Java 11 or newer and the checked-in Gradle wrapper are
required; first-time dependency setup may need network access.

Linux SLCAN:

```sh
bash bin/m749-cli.sh --read-flash m749-full.bin --slcan /dev/ttyACM0
```

Linux SocketCAN (bring `can0` up at 500 kbit/s first; see
[SocketCAN setup](cli-uploader.md#linux-socketcan)):

```sh
bash bin/m749-cli.sh --identify --socketcan can0
bash bin/m749-cli.sh --read-flash "my backup.bin" --socketcan can0
```

`bin/read-flash.sh "my backup.bin" --socketcan can0` is equivalent. Select an
explicit interface; `auto`, serial baud/bus options, and combining SocketCAN
with SLCAN or PCAN selectors are rejected. Existing resume, helper and credential
options apply unchanged. The default without a transport selector remains SLCAN.

macOS SLCAN:

```sh
bash bin/m749-cli.sh --read-flash m749-full.bin --slcan /dev/cu.usbmodem1
```

Windows SLCAN or PCAN:

```bat
bin\m749-cli.bat --read-flash "C:\backups\m749-full.bin" --slcan COM12
bin\m749-cli.bat --read-flash "C:\backups\m749-full.bin" --channel PCAN_USBBUS1
```

The output directory must already exist. An existing completed output is never
overwritten. PCAN needs the PEAK driver and matching PCAN-Basic/JNI libraries;
use native Windows Java, not a WSL JVM. SLCAN uses the included jSerialComm
dependency. With an explicit `--slcan PORT`, unrelated ports are not scanned.

Start with a bounded read when validating a target/adapter combination:

```sh
bash bin/m749-cli.sh --read-flash m749-sample.bin --slcan /dev/ttyACM0 \
  --start 0x08000000 --length 128 --chunk-size 128
```

The helper remains active after a successful read unless `--reset-after` is
specified. After the small read, start the full backup without uploading again:

```sh
bash bin/m749-cli.sh --read-flash m749-full.bin --slcan /dev/ttyACM0 --helper-running
```

`--helper-running` checks the one-byte keepalive, reads back all 6,656 helper
bytes from RAM, and requires an exact match to the bundled resource. Use it
only while that helper is still running. Without it, the command performs the
session/security/RAM-upload sequence. It does not automatically switch modes
or replay uploads after a failure.

## Progress, verification and resume

For output `m749-full.bin`, the reader uses:

| File | Purpose |
| --- | --- |
| `m749-full.bin.part` | Contiguous completed data blocks while reading |
| `m749-full.bin.properties` | Address, length, completed byte count, SHA-256, helper hash, CPU identification and completion state |
| `m749-full.bin` | Final binary, published only after complete coverage and a saved-data SHA-256 check |

Every new block is read twice and saved only if both copies match. Boot and
application probes must also return stable, nonuniform contents, helping detect
uniform blocked/invalid reads before a backup is trusted. Those probes read
32 bytes at `0x08000000` and `0x08080000`, even for a smaller requested range.
They are transfer/access checks, not a firmware-version or authenticity check.

The reader forces block data to disk before replacing its checkpoint. The
filesystem must support atomic checkpoint replacement. Advisory locks live in
the local temporary directory, including when output is on a Windows UNC path.
Keep both partial-data and properties files together. An interrupted command
leaves the last checkpoint available; bytes beyond it are discarded on resume.
No reset is sent on failure, and there are no automatic diagnostic retries.

Resume while the helper remains active:

```sh
bash bin/m749-cli.sh --read-flash m749-full.bin --slcan /dev/ttyACM0 \
  --resume --helper-running
```

After a power cycle back to an eligible application, omit `--helper-running`
to upload again. Repeat `--start` and `--length` for a nondefault range. Chunk
size and transport pacing may be changed. Before extending a resumed backup,
the reader checks the saved-file hash, CPU identification, and **every saved
byte against the ECU**. CPU identification is a part/revision value, not a
unique ECU serial number; the byte comparisons prevent mixing incompatible
saved contents. A mismatch leaves the saved prefix intact and stops.

The final properties file records `complete=true` and the SHA-256 printed by
the command. Image CRC validity is not used to discard a complete backup:
preserving the ECU's exact bytes also matters when its existing firmware is
damaged. The binary is a raw memory image whose offset zero is the requested
start address.

## Adapter and pacing options

| Option | Default / meaning |
| --- | --- |
| `--serial-baud` | `115200`; host serial speed, distinct from CAN speed |
| `--slcan-bus` | `1`; optional rusEFI bus tags `&` for 2 and `$` for 3 |
| `--chunk-size` | `1024`; 1..4080 data bytes per read request |
| `--block-size` | `16`; ISO-TP receive block size, 0 means unlimited |
| `--stmin` | PCAN/SocketCAN: 1 ms. SLCAN: at least 3 ms, increased for low serial baud rates. Override 0..127 ms |
| `--start`, `--length` | Default complete 4032 KiB main-flash range; decimal or `0x` integers |
| `--resume` | Validate and continue the existing partial backup |
| `--helper-running` | Verify and use the already-running bundled helper |
| `--reset-after` | Request reset only after publishing the complete backup |

SLCAN initialization sends `C`, `S6`, `O`; CAN is 500 kbit/s. On a rusEFI
sniffer, `S6` does not change the ECU's physical CAN configuration: configure
the selected bus to 500 kbit/s separately. The parser accepts optional SLCAN
timestamps and bus tags, ignores extended/RTR/other-bus traffic, and fails on
adapter errors or malformed frames. All traffic uses classic CAN; diagnostic
requests are on `0x7E0`, replies on `0x7E8`.

CANable 2 firmware can omit setup acknowledgements and return a revision string
instead of `Vhhhh`. After a close-command timeout, the CLI probes `V` and only
enables this compatibility mode for a recognized CANable firmware response.
It checks a fresh version response after each remaining setup command. This
confirms adapter responsiveness; ECU replies establish CAN connectivity. Other
silent adapters still fail initialization. CANable supports bus 1 only.

The helper bootstrap attempts DTC control (`85 02`) and communication control
(`28 01 01`). Explicit service-unavailable replies (NRC `11` or `7F`) permit
continuing to RAM upload. Other errors stop. Session/security entry, every RAM
write and helper launch still require their exact positive acknowledgements.

The adapter must deliver replies promptly, including short USB serial packets.
Some older rusEFI sniffer firmware buffers partial USB packets until more output
arrives; use firmware that flushes those packets. This reader does not inject
extra version queries to work around that behavior.

Keep the conservative pacing until the adapter has completed repeated small
reads. A 4080-byte response needs 584 CAN frames. The host must send each flow
control promptly; the helper's wait is approximately 100 ms. Larger blocks and
zero STmin increase receiver/serial load. Per-read deadlines include the chosen
STmin; missing frames, sequence errors, mismatched address echoes and incorrect
response lengths stop without certifying the block.

For installations that require existing paired authorization, the command also
accepts `--pair-file ECU.pair` or `--immo-backup PAIRED_FULLFLASH.bin`, mutually
exclusive and only when launching the helper. These use the existing normal
authorization flow and its power-cycle prompt. Successful authorization does
not by itself establish session-60 availability. Credentials are not recorded
in backup metadata.

`--reset-after` requires `51 01`; application return is not verified. A reset
failure after publication does not invalidate the saved binary, but the CLI
returns a failure exit code. Without this option, it leaves the helper active.
Exit codes are 0 for completion/help, 2 for invalid options and 1 for runtime
failure. Detailed read help:

```sh
bash bin/m749-cli.sh --read-flash --help
```

## Validation status

Host checks cover full-range content/hash, RAM upload and start, reuse of a
verified running helper, segmented responses, finite flow-control blocks and
sequence wrap, serial framing, partial reads and resume, changed/corrupt saved
data, interruption, output collisions and failure before adapter access.
The CLI runtime builds with the bundled helper included.

On 2026-09-24, SLCAN hardware validation identified software
`I812TA01_w2243v21`, part `8450086874`, accepted session 60 and application
security, uploaded all 6656 helper bytes and completed repeated boot and
second-bank sample reads. DTC control returned NRC 7F; communication control
and RAM writes succeeded. CANable 2 with 4080-byte chunks, block size 16 and
STmin 0 completed the second-bank sample at about 10.5 kB/s including duplicate
reads. Flash protection was not changed. These results apply to this target
and adapter; other combinations still need bounded sample validation.

The same bench completed the full 4,128,768-byte main-flash backup in about
414 seconds at 10,009 verified bytes/s, with every block read twice. SHA-256:
`317a949f10c3a783c78ed5f471ecc6ee933885c255848f76a5cd92625cb3dd60`.
The independently checked file hash and earlier samples match. Boot CRC
`4F256CD9` matches; application/calibration CRC validation remains unresolved
for this I812 version. After `--reset-after`, a separate automatic SLCAN scan
and identity query confirmed application session 01 and the same software/part.
