# OEM 707 and rusEFI full images

`I832GA01_w2304v2_8450110707.bin` is the complete OEM 707 reference image:

| Property | Value |
| --- | --- |
| Software / part | I832GA01_w2304v2 / 8450110707 |
| Address range | 0x08000000-0x083EFFFF |
| Size | 4,128,768 bytes (0x3F0000) |
| SHA-256 | f574718ecaa776876cab89d0520054dff23c4ac69535f64e0fbe19d45eeade10 |
| Boot CRC | E3186D26 |
| OEM software CRC | DF2857AA |
| Calibration CRC | D7BA65B9 |

From the repository root, build the current board firmware and combine it with
this backup (requires the normal firmware build prerequisites and Python 3):

```sh
bash bin/build-707.sh
```

The output is `build/707/rusefi-707-full.bin`. Additional arguments to the shell
wrapper are passed to `compile_firmware.sh`. To use an already built ELF,
including on Windows with Python 3, run:

```sh
python3 bin/m749_707_image.py --elf ext/rusefi/firmware/build/rusefi.elf \
  --output build/707/rusefi-707-full.bin
```

The Python command also defaults to these paths, relative to the checkout,
regardless of the current directory. Explicit relative paths use the current
directory. It validates the OEM hash and all three CRCs, the ELF load addresses
and vectors, and M749ACT3 I832 compatibility. It fills unused software bytes with
FF and computes the replacement software CRC using the normal image builder.

Only `0x08001000-0x0805FFFF` and `0x08080000-0x080FFFFF` are replaced. Every other
byte comes unchanged from the OEM backup, including calibration, loader, boot
marker, identity, pairing and storage. The full BIN starts at `0x08000000` and
contains no MCU option bytes. It carries this backup's stored identity/data;
it is not a per-ECU backup or a tune tailored to a particular engine.

For ordinary CAN installation, use `ext/rusefi/firmware/build/rusefi.hex` or
`rusefi.srec` with the [CAN uploader](../cli-uploader.md). The generated full BIN
is for full-image programming workflows; do not pass it to the uploader's OEM
BIN restore mode. These generation scripts do not connect to or flash hardware.

Offline validation:

```sh
python3 -m unittest discover -s tests -p 'test_707_image.py'
python3 tests/validate_oem_overlays.py ext/rusefi/firmware/build/rusefi.hex \
  docs/oem/I832GA01_w2304v2_8450110707.bin
```
