# Restore dev unit to OEM

Use this procedure to prepare the development ECU for an OEM-to-rusEFI CAN-only
installation test. Restore both the OEM application/calibration and the original
MCU options. Restoring an OEM BIN through CAN alone does not restore options.

## Saved options for this development unit

- File: [oem-fuses.bin](oem-fuses.bin).
- Size: 4096 bytes, mapped at `0x1FFFC000-0x1FFFCFFF`.
- SHA-256: `4555ad14f259d2ee1ffa6f51bd884be83a321ec4179330c01c4badf8349a0930`.
- Original access-protection halfword at `0x1FFFC000`: `5AA5`.
- Original EOPB0 halfword at `0x1FFFC010`: `FFFF` (128 KiB SRAM).

This file was read from the I832 development ECU before the J-Link RAM correction.
It is that unit's option backup, not a universal fuse image for other ECUs.
The separate `evidence/i832-jlink/before-flash.bin` was captured after installing
rusEFI and must not be used as the OEM application source.

## Restore sequence

1. Save the current flash/options and identify the resident loader. Select a
   genuine full OEM backup matching this ECU's loader and calibration. The
   I832GA01 reference must have loader CRC E3186D26, OEM software CRC DF2857AA
   and calibration CRC D7BA65B9. Supply your saved backup's path explicitly.
   Validate it before connecting:

   ```sh
   bash bin/write-flash.sh "path/to/matching-OEM-full-backup.bin" --dry-run
   ```

2. Restore the OEM application and calibration first:

   ```sh
   bash bin/write-flash.sh "path/to/matching-OEM-full-backup.bin" --slcan /dev/ttyACM0
   ```

   This replaces `0x08001000-0x080FFFFF` and performs the loader metadata/reset
   transaction. It preserves the connected ECU's loader, identity, pairing,
   EEPROM, rusEFI settings banks and options. Require OEM F186=01 and the expected
   F189/F192 identity before proceeding. Do not restore 128 KiB options while
   rusEFI remains installed: old images cannot start, and new images will
   configure the erased RAM option back to 384 KiB.

3. Restore `docs/oem-fuses.bin` using the AT32-aware debugger/ICP USD operation,
   with the core halted and no automatic reset between erase and write. Check
   the selected bank/address is the 4096-byte USD region at `0x1FFFC000`.
   Changing `05FA` back to `FFFF` requires an option-page erase; the one-halfword
   first-install programming sequence cannot reverse it. After that erase,
   restore the complete saved USD contents, including access protection, and compare
   all 4096 readback bytes against the file before resetting. Leave erased FFFF
   halfwords erased: blindly programming FF option bytes can generate complement
   00 and change them to 00FF. Do not use the
   generic `fuses-default.bin`: it selects the development 384 KiB RAM layout
   and does not reproduce the OEM starting state. Do not issue a main-flash or
   whole-chip erase as part of the option restore.

4. Detach the debugger, power-cycle the ECU and query over CAN. Require the
   expected OEM identity in application session 01. Save the option readback
   and identity log with the test evidence. This restores the application and
   options used for the test; it does not clone every factory NVM byte.

## Test the CAN-only installation

With the debugger detached, upload a new rusEFI HEX/SREC containing the early
RAM bootstrap using the ordinary CAN uploader. Require successful readiness,
software/calibration CRC and normal-marker checks after reset. Then power-cycle
the ECU and repeat the read-only activation checks. Do not preconfigure 384 KiB
RAM before this test, since that would skip the first-install path being tested.

The debugger is used only to return the development unit to its original test
state. The OEM-to-rusEFI installation under test must use CAN alone.
