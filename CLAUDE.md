# Local development knowledge

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
  directly. This does not require changing the script mode.
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
- bin/m749-cli.sh is stored without an executable bit. Wrappers must invoke
  it with bash; direct exec fails even when the wrapper itself is executable.

- CANable 2 can report a USB product revision different from its live V reply.
  The 2026-09-24 adapter reports 16e7497-dirty at runtime and omits C/S6/O
  acknowledgements. A close timeout triggers a bounded V probe; only a
  recognized CANable revision enables setup with fresh V response checks.
  Version replies establish serial responsiveness, not ECU presence.
- Live I812TA01_w2243v21 / 8450086874 accepts session 60 and application
  security, but rejects 85 02 with NRC 7F. Continuing without that optional
  DTC-control step allowed communication control, thirteen 512-byte RAM writes,
  helper launch and repeated flash reads. Do not broaden this exception to
  admission/security/RAM writes or to arbitrary negative responses.

- M749ACT2 keeps one fixed replacement software layout for I812 and I865,
  while selecting retained calibration by validated loader CRC: I812 4F256CD9
  -> 08069000, I865 D7B6B894 -> 08060000. Preserve the entire 60000..7FFFF gap;
  leftover I812 OEM software at 60000..68FFF is outside the replacement CRCs.
  Old M749ACT1 payloads are I865-only; calibration-only payloads remain I865-only.
- Both supported loaders use the six programming DIDs and writable append-only
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
