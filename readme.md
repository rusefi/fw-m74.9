Custom firmware for M74.9 ECUs

Board configuration starts from rusEFI `firmware/config/boards/m74_9`
for the AT32F435ZMT7 MCU and L9779 driver. The build entry point is
`bash compile_firmware.sh`.

See https://github.com/rusefi/rusefi/wiki/Custom-Firmware

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
