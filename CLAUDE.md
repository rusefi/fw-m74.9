# Local development knowledge

- Never commit or push; leave changes for the human. Preserve unrelated local
  changes, especially generated configuration files and submodule worktrees.
- Append completed work and validation to `docs/report.md`. Record durable
  tooling/protocol knowledge here; keep reports and guidance in plain ASCII.

## M74.9 loader and tools

- The I865 OEM resident loader is not OpenBLT. OpenBLT replacement and generic
  AT32 MFS memory layouts must not be applied to an ECU retaining the OEM loader.
- The Artery port actually built by this checkout lives in ChibiOS, not the
  similarly named AT32 driver under ChibiOS-Contrib. Its bank-2 register view is
  `FLASH2` at 0x40023C40, with `KEYR`, `STS`, `CTRL`, `ADDR`; names differ from
  Artery SDK `unlock2/sts2/ctrl2/addr2`. Do not substitute STM32 flash semantics.
- The native I865 reader at 0x08204B54 reads the live validity marker and SRAM
  token; its result is not constant 1.
- The programming-history page at 0x0824E000 is empty in the supplied I865 full
  image. I865 region 6 has read/write flags 3; it differs from the earlier I835
  table. Its 16 records are append-only, with no automatic page erase. Native
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
write whitelist and [native tests](tests/validate_i865_activation.py) for the
I865-specific reset evidence. Firmware builds can regenerate files inside the
rusEFI submodule; leave those generated changes unstaged.
