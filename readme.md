Custom firmware for M74.9 ECUs

Board configuration starts from rusEFI `firmware/config/boards/m74_9`
for the AT32F435ZMT7 MCU and L9779 driver. The build entry point is
`bash compile_firmware.sh`.

See https://github.com/rusefi/rusefi/wiki/Custom-Firmware

## Resident bootloader compatibility

The firmware follows [the M74.9 memory contract](docs/memory-layout-and-bootloader-details.md).
Build with `bash compile_firmware.sh`. The resulting
`ext/rusefi/firmware/build/rusefi.hex` and `rusefi.srec` contain complete,
4 KiB-aligned software ranges at `0x08001000-0x0805FFFF` and
`0x08080000-0x080FFFFF`, including erased padding and the software CRC.
Calibration and every bootloader/NVM region are excluded. Vectors start at
`0x08001000`, the initial SP is `0x20020000`, and Thumb startup at `0x08080000`
relocates VTOR before entering the C runtime. SRAM `0x20000000` is reserved
without initialization so both OEM boot-intent tokens survive startup.

Raw BIN, generic DFU, and replacement bootloader builds are disabled. Do not use
old `.bin`/`.dfu` files left over from earlier builds. ELF files are debugging
inputs, not ready-to-flash images: CRC trailers are added to HEX/SREC by
`bin/m749_image.py`. Bundle builds include these addressed images.

See [deployment artifacts and tools](docs/memory-layout-and-bootloader-details.md#deployment-artifacts-and-tools)
for why a single `.bin` is unsuitable and the exact address/length of each upload
range. The [Java PCAN uploader](docs/cli-uploader.md) implements the I865 OEM
loader transaction and persistent activation. Live bench validation is still pending.

Calibration must be handled separately. Given a complete, retained or deliberately
modified dump of `0x08060000-0x0807FFFB` (131,068 bytes, without its CRC), generate
its addressed payload with:

```sh
python3 bin/m749_image.py --calibration --format hex calibration.bin calibration.hex
```

The tool checks the write whitelist and complete page ranges and computes each
CRC independently using CRC-32/MPEG-2. It performs no device writes or activation.
The Java tab and CLI validate both CRC domains,
activation and application startup before reporting a completed upload;
transfer-exit alone is insufficient. See [CLI usage and limitations](docs/cli-uploader.md).

Physical CAN diagnostics use `0x7E0/0x7E8` at 500 kbit/s. With the engine stopped,
ISO-TP single-frame `02 10 02` receives `06 50 02 00 32 01 F4 00`. Only after
confirmed CAN transmission does the application write `0x4DF9123B` to SRAM and
reset. Timeout/error leaves the application running. Suppressed-response requests
are rejected, since this handoff requires the positive response.

**Settings are currently volatile.** The generic AT32 MFS backend overwrites
protected loader flash, so it is disabled. Tune and learned-data changes do not
persist across reset until a compatible calibration storage backend is implemented.
Bench verification of CAN acknowledgement/reset timing and actual resident-loader
boot remains required; host tests and cross-compilation cannot establish it.

Run the contract tests with
`python3 -m unittest discover -s tests -p 'test_memory_contract.py' -v`.
After building both bundles, run `python3 tests/check_bundles.py` to check their
payloads and ensure obsolete binaries or generic flash tools are absent.

## M74.9 Java UI

The custom console tab scans for PCAN adapters in the background. Its indicator
shows green **PCAN detected** or red **PCAN not detected**. On detection it uses
the first available channel at 500 kbit/s to query the ECU automatically.
Channels already in use are reported without opening them.

The **Installed firmware** label distinguishes positively detected OEM firmware
and rusEFI, including older M74.9 images without the general rusEFI identity DID.
No response leaves the firmware status unknown. Select the intended **PCAN
channel** before flashing; the upload never switches to another adapter.

**Flash rusEFI** installs the bundled software SREC through the OEM resident
loader. It becomes **Update rusEFI** when an M74.9 rusEFI application is detected.
The tab uses the console updater's SREC discovery helpers: a `re74.9` target
artifact in the bundle or firmware archive, then the usual input-directory/current-
directory SREC lookup. The selected filename is shown, with its full path in the
tooltip and Messages. **Scan / query again** refreshes the file selection. The
standalone Gradle launcher searches `ext/rusefi/firmware/build`.

For OEM conversion requiring authorization, select the ECU's `.pair` file or
original paired `.bin` backup in **OEM credentials**. Follow the startup power-cycle
prompt in Messages. An installed M74.9 rusEFI application updates without a pair
file or startup power cycle. Generic rusEFI identity alone does not establish
M74.9 update support, so that state disables flashing.

Image/credential validation, transfers, verification and activation run in the
background. Scanning and other actions are disabled during upload. Completion
requires the same CRC/boot-marker checks across reset as the CLI. Calibration is
preserved; the UI button only uploads the software domain. Closing the tab stops
the worker and releases its channel; an interrupted flash may need a full retry.

The lower **Messages** tab shows VIN (DID F190), identity records and metadata
as hex and printable ASCII, along with any unavailable-record responses or
communication errors. Other records retain their raw DID labels because their
meaning and availability depend on ECU firmware and stored data.

Identification uses physical CAN IDs 0x7E0/0x7E8, extended diagnostic session 03,
selector-00 security access, and 17 individual ReadDataByIdentifier requests.
It supports ISO-TP multi-frame responses and response-pending replies. A failed
authentication or transport timeout stops the query. The adapter indicator
reports PCAN presence even if the ECU does not respond. Use **Scan / query again**
to retry after checking ECU power and CAN wiring. Unplugging and reconnecting
the adapter also permits a new automatic query.

### Build and standalone Sandbox

Use Java 11 with the checked-in Gradle wrapper. From the repository root:

```sh
# Tests and custom module JAR
bash bin/java-ui.sh :custom-java-ui:test :custom-java-ui:jar

# Open just the M74.9 tab
bash bin/java-ui.sh :custom-java-ui:runM749Tab
```

On Windows:

```bat
bin\java-ui.bat
bin\java-ui.bat :custom-java-ui:runM749Tab
```

The standalone launcher is `com.rusefi.m749.M749TabSandbox` in the module's
test sources. Its Gradle task sets the working directory and native library
path to `ext/rusefi/java_console`, using the existing `PCANBasic.dll` and
`PCANBasic_JNI.dll`. Install the PEAK driver and use a JVM matching the DLL
architecture. In an IDE, import the Gradle build under `ext/rusefi` with
`RUSEFI_CUSTOM_JAVA_UI_DIR` set to the absolute path of `java-custom-ui`; use
the same working directory/native library path when launching the Sandbox.

Windows DLLs cannot be loaded by the Linux JVM in WSL2. Build and unit tests
work there; live PCAN access requires native Windows Java or the Linux PCAN
driver and matching native libraries. Missing libraries are reported in Messages.

The packaged console is `ext/rusefi/console/rusefi_console.jar`. Local bundle
and CI builds also include the custom module through `RUSEFI_CUSTOM_JAVA_UI_DIR`.
Closing the Sandbox or disposing the tab cancels the query and releases its
PCAN channel.

## Java PCAN uploader

```sh
# Validate the rebuilt software without opening a CAN adapter
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --dry-run

# Program via one explicitly selected adapter, then activate and check boot
bash bin/m749-cli.sh --upload ext/rusefi/firmware/build/rusefi.hex --channel PCAN_USBBUS1
```

Use `bin\m749-cli.bat` on native Windows. Software must include the current
`M749ACT1` activation ABI; calibration uses a separate `--calibration` upload.
See [the uploader guide](docs/cli-uploader.md) for the required metadata handshake,
verification choices, exit codes and pending hardware validation.

## Hardware

We have some notes at https://github.com/rusefi/m74.9
