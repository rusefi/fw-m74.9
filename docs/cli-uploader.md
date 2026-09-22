# PCAN firmware uploader

The Java CLI programs the I865 OEM resident loader over standard CAN IDs
0x7E0/0x7E8 at 500 kbit/s. The Swing tab remains identification-only. The CLI
and the application activation routine have offline tests and native ARM
emulation coverage. Normal paired IMMO authorization and programming-session
entry have been validated on the restored I865 bench using native Windows PCAN.
Live flashing and a physical power cycle of the replacement firmware remain
unvalidated.

## Commands

Build with Java 11 and the checked-in Gradle wrapper:

```sh
bash compile_firmware.sh
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --dry-run
bash bin/m749-cli.sh --list
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --channel PCAN_USBBUS1
```

On native Windows, use `bin\m749-cli.bat` with the same arguments and Windows
file paths. Install the PEAK driver and matching PCAN-Basic/JNI libraries.
Windows DLLs cannot be used from a WSL JVM. Linux live access requires Linux
PCAN drivers/native libraries. The launchers retain the caller's working
directory and preserve quoted paths containing spaces.

The launcher builds `:custom-java-ui:installM749Cli`, then runs
`com.rusefi.m749.M749Cli` directly with the installed runtime JARs. A missing
adapter/native library does not prevent `--help` or `--dry-run`.

Software HEX and SREC are equivalent inputs. Raw BIN, ELF, sparse images,
overlaps, mixed software/calibration domains, bad record checksums, missing
terminators, invalid vectors and bad CRC trailers are rejected before opening
an adapter. Software upload also requires the `M749ACT1` descriptor emitted by
the current firmware build. An explicit, available PCAN channel is required;
upload never tries another ECU after a failure.

For the paired I865 bench that rejects programming entry with NRC 22, add the
original full backup as the credential source:

```bat
bin\m749-cli.bat --upload rusefi.hex --channel PCAN_USBBUS1 --immo-backup "bin\Read_FULLFLASH_I865LB52_w2404b1____(240626_103727).bin"
```

`--immo-backup` is optional and currently accepts only the validated backup
with SHA-256 `ac052cd5cacf0385b4c2de794f6b1ad476e3f9f1badab5b8c54619854e428b39`.
It reads the paired key locally; it does not program the backup, change pairing
or disable the immobilizer. Its file length and hash are checked before opening
PCAN, including during `--dry-run`. Key bytes are never printed.

If the ECU already reports programming session 02, the CLI proceeds directly
to the existing loader checks. Otherwise wait for `IMMO listener ready`, then
cycle the ECU bench power while leaving PCAN connected. The listener waits up
to 60 seconds, proves the normal 713/714 exchange, sends the encrypted permission
message and verifies its acknowledgement before allowing the uploader to run.
An existing peer, wrong proof, wrong acknowledgement or timeout stops the attempt
before any flash request. It sends no ignition/cranking simulation or key-learning
messages. This is a paired bench entry route, not a universal OEM credential.

Calibration is a separate operation:

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

1. Validate the entire input and optional paired backup. If requested, complete
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
   records, then request reset. The native loader writes the SRAM return token.
6. Before actuator initialization, the new application checks the software,
   calibration and pinned I865 loader CRCs. It preserves the validity page's
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

This enters extended session 03, authenticates and reads identification DIDs.
It sends no flash erase/download requests. Capture its output, particularly
session/authentication results and DID F189. NRC 22 alone does not identify a
specific unmet condition. The replacement firmware checks that the engine is
stopped; the OEM application has separate admission checks, so do not infer
an engine-running diagnosis from that code alone on an OEM bench ECU.

On the restored I865 bench, session 03 and its seed/key authentication succeeded
while an immobilizer-related programming-entry condition remained unsatisfied.
Live RAM reads and native execution confirmed that distinction. Adding the
identification authentication sequence is therefore not an established fix.
The normal OEM authorization exchange is now recovered and validated live;
use the explicit `--immo-backup` option above for this paired bench. The CLI
does not automatically retry a rejected session or change admission state via
a debug probe. See the sibling research repository's
[IMMO evidence](../../m749-ghidra/docs/i865-immo-authorization.md).

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
that page. Before any application erase, the CLI requires the next native-selected
slot to be fully FF and refuses a full/partially written journal. Full-page
compaction, history browsing/export and a metadata-free activation route remain
TODOs. The uploader does not erase protected NVM to make space.

The payload whitelist still excludes every loader/metadata/NVM region. The only
additional writes are the resident loader's own programming-state/history
transactions and the application's narrow, CRC-gated validity-page transaction.

## Evidence and limits

- `M749ImageTest`, `UdsClientTest`, `M749UploaderTest`, `M749CliUploadTest` cover
  validation before hardware access, ISO-TP flow control, sequence/counter wrap,
  protected ranges, metadata capacity, failures and application confirmation.
- `M749ImmoTest` compares synthetic-key results with native OEM instruction
  output, including split streams across blocks, normal proof/permission,
  rejection, timeout, competing peer and already-programming behavior. The live
  CAN entry evidence uses the equivalent Python reference; the Java handshake
  itself has unit-test coverage, not a second live startup run.
- `tests/test_boot_activation.cpp` exercises the marker policy, preservation,
  failures and CRC boundaries; it runs with the Python memory-contract suite.
- `tests/validate_i865_activation.py` executes the SHA-pinned original loader:
  native DID parsing, region permissions, record encoding, completion callbacks,
  reset-token selection and application entry. Only physical flash I/O and
  reset/jump boundaries are modeled.
- `tests/validate_activation_firmware.py` executes the built ARM activation
  routine with modeled bank-2 registers. It checks actual CRC execution and
  confines physical erase/program operations to the validity page. It injects
  bad calibration and a program error.

Run the native scripts with a Unicorn-enabled Python and explicit input paths:

```sh
python3 tests/validate_i865_activation.py /path/to/validate_i865_loader_protocol.py
python3 tests/validate_activation_firmware.py --original-image /path/to/fullflash.bin
```

The loader script requires the original-image harness and its image fixture.
The firmware script requires the original I865 full image, ARM binutils and a
current ELF; an optional positional argument selects a different ELF.
Neither script sends CAN frames or uses a probe.
Evidence is recorded in [evidence](evidence/2026-09-22-cli-activation.json).

OpenBLT replacement and generic AT32 MFS layouts are incompatible with this
resident-loader contract. The local narrow bank-2 driver follows the register
sequence in [Artery's SDK](https://github.com/ArteryTek/AT32F435_437_Firmware_Library/blob/master/libraries/drivers/src/at32f435_437_flash.c)
and [AT32F435/437 reference manual](https://www.arterychip.com/download/RM/RM_AT32F435_437_V2.07_EN.pdf).

Bench follow-up: native Windows launcher, real PCAN flow control and flash
timing, physical cold boot, interrupted-power recovery, and preservation of
protected ECU data. Offline tests cannot establish those hardware properties.
