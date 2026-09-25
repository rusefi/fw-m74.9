# rusEFI for M74.9 ECUs

Custom rusEFI firmware for the Lada M74.9 engine control unit. This guide covers
installing and updating the firmware. Build instructions, memory layout and
tooling internals are in [readme-technical-details.md](readme-technical-details.md).

Download the latest bundle:

https://rusefi.com/build_server/rusefi_bundle_re74.9.zip

Hardware notes and wiring: https://github.com/rusefi/m74.9

## What you need

- An M74.9 ECU on the bench or in the vehicle, with the engine stopped.
- A CAN adapter connected to the ECU diagnostic CAN bus at 500 kbit/s:
  - PEAK PCAN-USB on Windows, with the PEAK driver installed, or
  - an SLCAN serial adapter such as CANable on Linux, macOS or Windows, or
  - a Linux SocketCAN interface for command-line use.
- Java, version 11 or newer recommended. On Windows the Java installation must match the PCAN
  driver architecture (64-bit Java for a 64-bit driver).
- The extracted rusEFI bundle. Run `rusefi_updater.exe` on Windows or
  `rusefi_updater.sh` on Linux/macOS to open the console.

Close any other software that uses the CAN adapter before starting. Windows
Subsystem for Linux cannot load Windows PCAN libraries; use native Windows Java
or expose the adapter as a Linux SocketCAN interface.

## Before you start: back up your ECU

The firmware keeps the OEM bootloader, calibration and identity data, but a
complete backup is the only way to return to the original state. Make one before
the first installation and keep it somewhere safe.

Linux/macOS, SLCAN adapter:

```sh
bin/read-flash.sh
```

Windows:

```bat
bin\read-flash.bat
bin\read-flash-pcan.bat
```

Each command finds the single connected adapter and writes a timestamped
`m749-full-...bin` file in the current directory. Reading takes several minutes
and shows progress. If it stops, run it again with the same filename and
`--resume`. Details and options are in [the backup guide](docs/cli-flash-reader.md).

Some ECUs require the original pairing (immobilizer) credential before they
allow reading. See [readme-grab-key.md](readme-grab-key.md) for reading a
`.pair` file from an ECU that already has one.

## Check which firmware is installed

Before changing anything, confirm the console sees your ECU:

```sh
bash bin/m749-cli.sh --identify --slcan auto
```

```bat
bin\m749-cli.bat --identify --channel PCAN_USBBUS1
```

The output reports either the OEM firmware identity or an installed rusEFI
version. No response usually means ECU power, CAN wiring or the bus speed is
wrong. A running rusEFI image can be quiet between requests; a single timeout
does not mean the ECU is missing.

## M74.9 Java UI

Open the rusEFI console and select the **M74.9** tab.

1. Select **Connector**: SLCAN (default), PCAN or SocketCAN. The tab queries
   the selected endpoint automatically and fills in **Installed firmware** using
   read-only identification requests.
2. SLCAN takes a serial port or `auto`; automatic selection requires exactly one
   adapter. SocketCAN takes an interface such as `can0`, already up at 500 kbit/s.
   PCAN lists channels in its selector and requires a choice when multiple
   adapters are available. Successful automatic identification pins subsequent
   operations to that adapter. Press Enter after editing the endpoint/settings,
   or use **Scan / query again** to retry. Identification failures are retried
   at ten-second intervals until an ECU responds.
3. If OEM authorization is required, choose the ECU's `.pair` file or original
   paired `.bin` backup under **OEM credentials**. An already installed M74.9
   rusEFI application updates without a credential.
4. Press **Flash rusEFI**. The button reads **Update rusEFI** when a rusEFI
   M74.9 application is already installed. The bundled firmware file name is
   shown next to the button; **Scan / query again** re-reads it.
5. Watch the **Messages** tab. When it asks you to power-cycle the ECU, switch
   ECU power off and on while leaving the adapter connected. An installed
   rusEFI application updates without a power cycle.
6. Wait until Messages reports the completed upload. The tab checks the written
   image and the application startup after reset; a message saying the transfer
   finished is not yet a completed installation.

The scan and other buttons are disabled during an upload. Do not close the tab
or unplug the adapter while it runs; an interrupted flash usually needs a full
retry from step 4. Your calibration is preserved: the button only replaces the
engine software.

For a chosen file, use the separate **Read flash...** and **Write firmware...**
buttons. Identification, the bundled updater, and both file actions use the
same **Connector**, endpoint and transport settings. **Serial baud** and
**SLCAN bus** apply only to SLCAN. **Receive block** and **STmin ms** apply to all
connectors; `auto` STmin uses the CLI's transport-specific default.

- **Read flash...** opens a save dialog for a full OEM `.bin` backup. The dialog
  also offers resume and already-running-helper options. Existing completed
  files are never overwritten. Progress, byte counts, speed and final SHA-256
  appear in **Messages**. After a successful read the UI requests ECU reset;
  application return is not verified. Reading requires the OEM application's
  session-60 helper support, which is unavailable in the rusEFI application.
- **Write firmware...** opens a file dialog for rusEFI `.hex`/`.srec` or a
  supported OEM full-flash `.bin`. HEX/SREC software updates preserve
  calibration. OEM BIN restores replace software **and calibration**, preserving
  the connected ECU's loader, identity, pairing and storage. Only matching
  I812/I865 profiles are supported; partial dumps are rejected. See
  [write details and completion checks](docs/cli-uploader.md#selected-file-writes).

The buttons use the CLI transfer implementation and report progress and failures
in the existing Messages control. Other operations are disabled until the
transfer ends. In the embedded console, discovery is suspended and the console
connection is released for direct identification and transfers; reconnect the console manually afterward.

Command-line equivalents (Windows: use the matching `.bat` wrappers):

```sh
bash bin/read-flash.sh "ECU backup.bin" --slcan auto --reset-after
bash bin/write-flash.sh rusefi.hex --dry-run
bash bin/write-flash.sh rusefi.srec --slcan auto
bash bin/write-flash.sh "OEM full backup.bin" --channel PCAN_USBBUS1
```

See the [toolset review](docs/toolset-review.md) for remaining UI/CLI differences.

The **Messages** tab also lists the VIN and identity records read from the ECU.
If the ECU does not answer, check ECU power and CAN wiring, then use
**Scan / query again**. Unplugging and reconnecting the adapter also starts a
new query.

## Command-line installation and update

The same installation is available without the console UI. Linux/macOS with
an SLCAN adapter:

```sh
# Check the connected ECU against the firmware file without writing anything
bash bin/m749-cli.sh --check-target rusefi.hex --slcan auto

# Install or update
bash bin/m749-cli.sh --upload rusefi.hex --slcan auto
```

Windows with PCAN:

```bat
bin\m749-cli.bat --check-target rusefi.hex --channel PCAN_USBBUS1
bin\m749-cli.bat --upload rusefi.hex --channel PCAN_USBBUS1
```

Linux with SocketCAN, after [configuring `can0` at 500 kbit/s](docs/cli-uploader.md#linux-socketcan):

```sh
bash bin/m749-cli.sh --identify --socketcan can0
bash bin/m749-cli.sh --check-target rusefi.hex --socketcan can0
bash bin/m749-cli.sh --upload rusefi.hex --socketcan can0
```

For a backup, use `bash bin/m749-cli.sh --read-flash backup.bin --socketcan can0`.

Use the `rusefi.hex` or `rusefi_update.srec` file from the bundle. When the
ECU still runs OEM firmware and needs its pairing credential, add
`--pair-file ecu.pair` or `--immo-backup backup.bin`. Follow the power-cycle
prompt printed by the command. The command exits successfully only after the
new firmware has been verified and has started. All options, exit codes and
recovery steps are in [the uploader guide](docs/cli-uploader.md).

## Tuning

After installation, connect TunerStudio to the ECU using the
`rusefi_re74.9.ini` file from the bundle. Tune changes are stored in the ECU's
own flash and survive later firmware updates. The OEM calibration area is kept
unchanged; the firmware only uploads a new calibration when you explicitly ask
for it on the command line.

## Troubleshooting

- **PCAN not detected**: install the PEAK driver, use 64-bit Java with the
  64-bit driver, and close other programs using the adapter. Only native
  Windows Java can use the PEAK libraries; Linux CLI users can use `--socketcan`.
- **No SLCAN adapter found**: the wrappers need exactly one SLCAN adapter.
  Select it explicitly with `--slcan /dev/ttyACM0` or `--slcan COM5` when
  several serial devices are present.
- **ECU does not respond**: check ECU power, the CAN high/low wiring and that
  the bus runs at 500 kbit/s. The ECU must be powered and the engine stopped.
- **rusEFI detected but flashing is disabled**: the installed rusEFI build is
  not an M74.9 image with the update interface. Use the OEM credential route.
- **Upload interrupted**: the ECU may stay in its bootloader or return to the
  OEM application after a timeout. Power-cycle the ECU and start the upload
  again from the beginning.
- **Old firmware files**: use only the `.hex` and `.srec` files from the
  bundle. Do not flash `.bin`, `.dfu` or `.elf` files from earlier builds, and
  do not use generic STM32 or DFU tools on this ECU.

## Status

Installation, update and flash-read transactions have been exercised on bench
ECUs. Tune retention in the ECU's storage banks, recovery from power loss during
an upload and long-term running behavior still need more hardware validation.
Keep your backup until you are satisfied with the installed firmware.

## Getting help

Questions and hardware findings are welcome at https://github.com/rusefi/m74.9
and in the rusEFI community. Include the console **Messages** output or the
command-line output when reporting a problem.
