# Local development knowledge

- This is a public repository. Use repository-relative paths or portable
  placeholders in documentation, reports, comments and examples; do not include
  machine-specific absolute paths. Do not name, link to or expose private
  repositories or their contents. Keep private supporting material outside this
  checkout; public guidance should stand on its own.

- Never commit or push; leave changes for the human. Preserve unrelated local
  changes, especially generated configuration files and submodule worktrees.
- Append completed work and validation to `docs/report.md`. Record durable
  tooling/protocol knowledge here; keep reports and guidance in plain ASCII.

- Keep documentation, reports and comments focused on product behavior,
  implementation requirements, operating instructions and validation outcomes.

## M74.9 loader and tools

- Keep `use_canbus_connector=true` in
  `shared_io.resources/shared_io.properties`; this is a key setting for this
  repository's UI. It makes console discovery treat serial adapters as SLCAN
  CAN endpoints. Setting it to false switches discovery to direct serial ECU
  connections and prevents the intended SLCAN discovery path.
- The I865 OEM resident loader is not OpenBLT. OpenBLT replacement and generic
  AT32 MFS memory layouts must not be applied to an ECU retaining the OEM loader.
  M74.9 uses its own two 256 KiB MFS banks at 0x08300000-0x0837FFFF. Preserve
  them during software updates; do not substitute the generic sector 0/32 layout.
  These pages were previously unclassified; OEM non-use and storage reliability
  still require hardware validation. MFS can initialize/erase banks during mount.
- The Artery port actually built by this checkout lives in ChibiOS, not the
  similarly named AT32 driver under ChibiOS-Contrib. Its bank-2 register view is
  `FLASH2` at 0x40023C40, with `KEYR`, `STS`, `CTRL`, `ADDR`; names differ from
  Artery SDK `unlock2/sts2/ctrl2/addr2`. Do not substitute STM32 flash semantics.
- I865 boot-mode selection depends on the live validity marker and SRAM token;
  do not replace it with a constant result.
- The programming-history page at 0x0824E000 is empty in the supplied I865 full
  image. I865 region 6 has read/write flags 3; it differs from the earlier I835
  table. Its 16 records are append-only, with no automatic page erase. Loader
  initialization selects the first slot whose bytes 0 and 8 are both FF.
- FF01 is only a 16-bit additive checksum. A match for arbitrary multi-byte
  contents cannot prove byte equality. A single byte is unambiguous; an all-FF
  256-byte slot is also unambiguous because 256 * 255 = 65280 cannot wrap.
- Start TransferData's counter at zero for this loader. Its advertised 0x0802
  maximum includes the SID and counter, leaving 2048 firmware bytes. TransferExit
  neither validates the image CRC nor makes it bootable.
- The Java launchers build the CLI runtime, then invoke Java directly. Passing
  user arguments through a space-split Gradle property loses quoted file paths.
  Linux tests/dry runs need no PCAN native library; live Windows PCAN requires
  native Windows Java and matching PEAK JNI/driver libraries, not a WSL JVM.

Use [the memory contract](docs/memory-layout-and-bootloader-details.md) for the
write whitelist and [CLI guide](docs/cli-uploader.md) for the I865 reset and
activation contract. Firmware builds can regenerate files inside the
rusEFI submodule; leave those generated changes unstaged.

- FF01 byte reads must confirm one matching candidate and reject a wrong
  candidate before saving the byte. Pair files omit unknown indices; 00 and FF
  are known values. Resume verifies saved entries before filling missing bytes.
  Reading can request session 02 and reset into the loader, but a rejected
  programming transition stops before security/checksum requests.

- AT32's TIM5 scheduler uses the STM32 PWM HAL. Set
  STM32_PWM_TIM5_IRQ_PRIORITY to EFI_IRQ_SCHEDULING_TIMER_PRIORITY; the generic
  STM32_IRQ_TIM5_PRIORITY setting does not replace that driver's priority.
  The HAL default 7 conflicts with the scheduler's 3, raises a critical startup
  error and prevents CAN initialization even after successful boot activation.

- A running M74.9 rusEFI image can be quiet between diagnostic requests. Older
  installed images answer F1A0..F1A3 but not OEM session/identity DIDs, including
  F186. A timeout on F186 does not mean the ECU is absent or needs a power cycle.
  Confirm the M749ACT1 interface separately from the general rusEFI identity;
  other rusEFI boards need not use the I865 resident loader.

- The packaging fixture includes bundle.mk without the normal top-level make
  defaults, so test_bundle_packaging.py passes PYTHON=sys.executable to make
  itself. Without it, make tries to execute the non-executable image script
  directly. This does not require changing the script mode. Since upstream
  GeneratedIniValidator (bundle.mk validate-bundle-ini), every bundle zip also
  needs CONFIG_DEFINITION_JAR; java_tools.mk normally defines it, so the fixture
  sets it to the real config_definition-all.jar and runs Gradle
  :config_definition:shadowJar only when that jar is missing.
- The default host test configuration does not enable EFI_CAN_SUPPORT. Test
  receive sensors through CanListener::processFrame and the sensor registry;
  the full shared receive dispatcher is validated by the production build and
  still needs hardware testing for live diagnostic coexistence.

- Board fields declared in board_config.txt belong to persistent `config`, not
  `engineConfiguration`. Board default setup and the INI `defaultValue` entry
  serve different paths (fresh ECU settings versus missing imported fields);
  neither is a migration that overwrites an existing stored tune. Keep the
  board menu/options/constants-extension inputs as the sources for INI changes.


- The primary console and SLCAN VCP can share a USB device identity; use the
  shared SlcanPortScanner classification, not port enumeration order, for
  automatic flash-reader selection. Discovery proves the adapter protocol,
  not which ECU is connected to its CAN bus. Use a one-shot scan for a reader
  so a background scanner cannot reopen its serial port during a transfer.
- The rusefi Gradle build uses the configuration cache: build scripts must
  not start processes (git) at configuration time or capture Project objects
  in task actions; use providers.exec and file providers. M749Cli prints
  `M749 build: board <hash>, rusefi <hash>, built <UTC>` first, from
  build.properties generated by java-custom-ui/build.gradle; the same values
  are manifest attributes Board-Git, Rusefi-Git and Build-Date in custom-java-ui.jar and rusefi_console.jar.
  A CLI run that rejects a known-good adapter is suspect until this line
  proves the build postdates the fix.
- PCAN works on Windows (PEAK DLLs) and macOS (libpcanbasic_jni.dylib over
  MacCAN libPCBUSB, brew install pcbusb); Linux has no PCAN-Basic binding and
  the CLI rejects --channel there. The macOS bridge built before upstream
  buffer marshalling never copies GetValue results back: PcanDevice seeds
  PCAN_CHANNEL_CONDITION with -1 and reports "PCAN_USBBUS1 (assumed)" when it
  stays untouched. MacCAN is single-client and CAN_Read never blocks; plug the
  adapter in before the first open. bin/m749-cli.sh must pass java.library.path.
- bin/m749-cli.sh is stored without an executable bit. Wrappers must invoke
  it with bash; direct exec fails even when the wrapper itself is executable.

- CANable 2 can report a USB product revision different from its live V reply.
  The 2026-09-24 adapter reports 16e7497-dirty at runtime and omits C/S6/O
  acknowledgements. A close timeout triggers a bounded V probe; only a
  recognized CANable revision enables setup with fresh V response checks.
  Version replies establish serial responsiveness, not ECU presence.
  WeAct USB2CANFDV1 answers V with `WeAct Studio V1.0.0.3_bb264e71` and is
  treated as CANable family. SlcanVersion in java_console/io is the single
  V-reply rule for discovery, the console connector and SlcanTransport; do
  not add adapter-specific regexes elsewhere.
- Live I812TA01_w2243v21 / 8450086874 accepts session 60 and application
  security, but rejects 85 02 with NRC 7F. Continuing without that optional
  DTC-control step allowed communication control, thirteen 512-byte RAM writes,
  helper launch and repeated flash reads. Do not broaden this exception to
  admission/security/RAM writes or to arbitrary negative responses.

- M749ACT3 keeps one fixed replacement software layout for I812/I832/I865,
  while selecting retained calibration by validated loader CRC: I812 4F256CD9
  -> 08069000, I832 E3186D26 and I865 D7B6B894 -> 08060000. Preserve the entire
  60000..7FFFF gap;
  leftover I812 OEM software at 60000..68FFF is outside the replacement CRCs.
  Old M749ACT1 payloads are I865-only; ACT2 supports I812/I865.
  Calibration-only payloads remain I865-only.
- All three supported loaders use the six programming DIDs and writable append-only
  journal at 0824E000 for return-token activation. Do not infer these permissions
  from application-session behavior. Preflight sends no metadata write/reset;
  the OEM loader may later return to session 01 on its own timeout.
- SLCAN after USB reattachment can start with partial frames and line endings
  buffered before raw port configuration. Flush stale queues before any request
  and synchronize fragments only during the first close command. Keep status
  errors and active parsing strict; arbitrary malformed runtime frames are not
  safe to discard. The I812 target preflight passed after this correction.


- Full OEM I812TA01 backups have application vector words 00000000/08080001
  at 08001000, while I865LB52 uses 20020000/08080001. A universal nonzero-SP
  check rejects a valid I812 backup even when all three CRCs pass. Keep OEM
  vector checks profile-specific; replacement firmware still uses 20020000.
- OEM application return can be checked with F186=01 after the loader metadata
  transaction/reset. OEM does not implement replacement activation DIDs F1A0..3;
  session return is not evidence of those CRC/marker reports or of cold boot.
- I832GA01 combines boot CRC E3186D26 and calibration start 08060000 with
  OEM application vectors 00000000/08080001. Do not infer its calibration
  layout from the zero stack vector. Java OEM BIN restore supports this
  profile. M749ACT3 replacement firmware supports it; M749ACT1/M749ACT2
  still omit its boot CRC and must be rejected on I832 before erase.
- M749ACT3 retains the 32-byte descriptor at 0805FFE0 by encoding magic plus
  three CRC/calibration-start pairs; count 3 and activation protocol 1 are
  implicit. Update firmware and uploader together. I832 offline loader checks
  and live activation pass, including an I832 physical power cycle
  after the separate SRAM option correction described below.
- Readiness polling must preserve the actual F1A0 failure in the displayed
  message. Retry startup timeouts/negative ECU replies and not-ready values.
  The I832 CAN-only first boot succeeded with CRC A470151C and changed only
  EOPB0 FFFF -> 05FA, but WeAct V1.0.0.6 rejected the early F1A0 with BELL.
  An immediate poll after a normal reset reproduced BELL followed by readiness
  one second later. Retry a typed SLCAN rejection only for bounded read-only
  F1A0 startup polling; stop on other transport/protocol failures and on BELL
  during any programming/reset request. F186=02
  with F189=M749-<CRC> establishes loader session plus programming history,
  not application startup. The first I832 ACT3 hardware upload reached this
  state because the MCU was configured for insufficient SRAM.
- firmware/m749.ld places application RAM at 20020000..2005FFFF, requiring
  at least 384 KiB total SRAM. The live I832GA01 had erased EOPB0=FF at
  1FFFC010, selecting 128 KiB; it HardFaulted during C++ constructors before
  activation. Loader/CRC checks alone cannot establish RAM compatibility.
  The CAN uploader itself does not inspect or change this option. New firmware
  handles the erased option before C startup in m749PrepareRam; older ACT3
  images (including 2EE6A467) require the separate debugger correction.
- On that I832 bench, programming only erased EOPB0 to FA through the AT32 USD
  controller produced halfword 05FA (hardware complement), selecting 384 KiB.
  No option erase was needed; a full 4 KiB before/after comparison confirmed
  only those two bytes changed. The same software CRC 2EE6A467 then activated
  and answered all ready/CRC/marker DIDs after software reset and a physical
  power cycle. Preserve all other USD bytes and access protection; do not
  generalize the erased-option
  procedure to non-erased options. Evidence: docs/evidence/i832-jlink/.
- The RAM bootstrap must precede _crt0_entry: ChibiOS selects PSP in high RAM
  before __early_init. Use a low MSP and no initialized globals/FPU/HAL. Copy
  USD programming code and its literals into low SRAM, validate all image CRCs
  before mutation, and keep other options unchanged. A correct existing
  384/448/512 KiB option needs no write; non-erased insufficient options must
  return to the loader instead of erasing USD. Test the compiled Thumb path
  with tests/validate_boot_ram.py, which maps only 128 KiB SRAM.
- To reproduce first installation on the development ECU, restore OEM software
  before docs/oem-fuses.bin. That exact 4096-byte USD backup has EOPB0 FFFF.
  A CAN OEM BIN restore does not touch USD; a rusEFI image left installed would
  either fail at 128 KiB (old image) or change the erased option again (new image).
  See docs/restore-dev-unit-to-oem.md. The generic fuses-default.bin is not the
  original OEM option state.
- Stale generated/console/binary/generated live-data fragments can cause INI
  validation errors for current template fields even when the C structs exist.
  Regenerate with META_OUTPUT_ROOT_FOLDER=../../../generated/ and BOARD_DIR=../../..
  using gen_live_documentation.sh from ext/rusefi/firmware before rebuilding.
- When building a replacement console jar with bin/java-ui.sh, set
  ABSOLUTE_BOARD_DIR to the board checkout. RUSEFI_CUSTOM_JAVA_UI_DIR adds the
  tab classes but does not select shared_io.resources; without the board
  environment, :ui:shadowJar uses generic discovery settings. Verify the jar's
  shared_io.properties includes use_canbus_connector=true before distribution.

- Live tune reads on WeAct V1.0.0.6 can stall after the first ISO-TP
  response frame (four TS body bytes received), even with F1A0 ready.
  Independent Java/Python readers completed identical five-page reads with
  20 ms SLCAN command pacing, but a later paced Java read still timed out;
  do not treat pacing as a validated fix or assume a smaller block cures it.
  The 2026-10-03 bundle also omits Gpio::L9779_PIN_KEY (280) from gpio_list,
  causing MSQ serialization of the board-default ignitionKeyDigitalPin to
  fail after a successful read. Preserve a multi-page binary backup rather
  than replacing that pin value to force export.

- The console tune-read stall above was resolved by enabling WeAct automatic
  CAN retransmission (`A1` before `O`) in SLCANConnector. One-shot adapter TX
  drops frames under bus contention; fixed host delays are not the fix.
  This console change adds no UDS/tune-operation replay and does not change
  the M74.9 loader uploader code. The ignition-key INI correction is described below.

### Ignition-key pin metadata

M74.9 stores L9779_PIN_KEY (280) as its ignition-key input. The generic
Gpio INI list only includes MCU pins 0..177. Use switch_input_pin_e for
ignitionKeyDigitalPin and include BF2/L9779_PIN_KEY in the board connector
switch inputs; both changes are needed for MSQ export. The C++ aliases have
identical U16 storage, so this metadata correction does not migrate tune bytes.
For an installed image, retain its signature and offsets when correcting the
INI; a newly generated INI also has a newly generated firmware signature.

- Swing addNotify does not mean a tab is selected: both startup and connected
  console install hidden M74.9 panels. Gate automatic identification on
  hierarchy SHOWING_CHANGED/isShowing state; otherwise M749ConsoleAccess
  disconnects the tuning stream and suppresses its watchdog reconnect.
- A newer bundle INI does not replace the older firmware signature's cached
  INI. When diagnosing a repeated metadata error, check the exact INI path
  selected after the live signature, not only the bundle file.
- First-boot CRC checks run before normal clock setup. On the OEM-option I832,
  C3A32B44 needed 27.7 seconds to reach the RAM-option write. Ten one-second
  SLCAN readiness polls expire too early. Allow 60 bounded read-only polls,
  one second apart, and instruct the operator to keep power on. The same
  image subsequently configured EOPB0, activated and passed a physical cold
  boot without reflashing; only option bytes 0x10/0x11 changed (FFFF -> 05FA).
