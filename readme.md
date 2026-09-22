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

Calibration must be handled separately. Given a complete, retained or deliberately
modified dump of `0x08060000-0x0807FFFB` (131,068 bytes, without its CRC), generate
its addressed payload with:

```sh
python3 bin/m749_image.py --calibration --format hex calibration.bin calibration.hex
```

The tool checks the write whitelist and complete page ranges and computes each
CRC independently using CRC-32/MPEG-2. It performs no device writes or activation.
The Java tab remains identification-only. There is no enabled CAN flash writer:
the loader's validity-finalization sequence is still unknown. A future writer
must verify every programmed range, both CRCs, activation, reset, and application
startup before reporting success; transfer-exit alone is insufficient.

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

The lower **Messages** tab shows VIN (DID F190), identity records and metadata
as hex and printable ASCII, along with any unavailable-record responses or
communication errors. Other records retain their raw DID labels because their
meaning and availability depend on ECU firmware and stored data.

Identification uses physical CAN IDs 0x7E0/0x7E8, extended diagnostic session 03,
selector-00 security access, and 26 individual ReadDataByIdentifier requests.
It supports ISO-TP multi-frame responses and response-pending replies. A failed
authentication or transport timeout stops the query. The adapter indicator
reports PCAN presence even if the ECU does not respond. Use **Scan / query again**
to retry after checking ECU power and CAN wiring. Unplugging and reconnecting
the adapter also permits a new automatic query.

### Build and standalone Sandbox

Use Java 11 with the checked-in Gradle wrapper. From the repository root:

```sh
# Tests and console JAR, including the custom tab
bash bin/java-ui.sh

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

## Hardware

We have some notes at https://github.com/rusefi/m74.9
