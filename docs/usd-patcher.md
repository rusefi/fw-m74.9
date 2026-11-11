# USD RAM-option maintenance

The M74.9 console and CLI include an explicit option-maintenance workflow for
I862BA02 with application CRC B167C266 and loader CRC D7B6B894. It changes
EOPB0=00FF to 05FA, selecting 384 KiB SRAM, while preserving flash protection
and the other 4094 raw USD bytes. It is not part of normal firmware upload.

**Live erase/restore passed on one protected I862 unit on 2026-11-09.** Applying erases the entire
USD area and restores it from RAM. Stable power is essential: losing power or
an external watchdog reset during the transaction can leave incomplete options.
There is no automatic rollback or recovery replay. Only the independent
watchdog is serviced by the payload.

The supported OEM application must be running and accept session 60. An ECU
left in the resident loader after a failed firmware upload cannot launch this
patcher. Restoring usable OEM application software is a separate operation;
the maintenance commands do not perform it or clear flash protection.

## Console

In the M74.9 tab, select the connector and endpoint, then use **USD maintenance**:

1. **Inspect and save options**: choose an existing folder. The console creates
   a new `usd-inspection-<timestamp>` subfolder inside it and logs that path.
   Inspection saves the full 4096-byte page and checked result there, then
   resets the reader and checks that OEM application session 01 returned.
   It does not erase options. Use the new subfolder for Apply and Verify.
2. **Apply RAM option patch**: choose that inspection directory. The dialog
   validates the backup and displays its CRC before asking to erase/restore.
   The ECU repeats all target, protection and snapshot checks before erase.
3. **Verify applied patch**: choose the same directory once the ECU responds in
   loader session 02. This checks the retained result and compares all 4096 USD
   bytes against the backup with only EOPB0 changed. It sends no reset or session
   transition. The ECU may return to its default session on its own.

The operation runs on the transfer worker with other transfers and background
queries suspended. Automatic queries stay paused after maintenance. Use
**Scan / query again** only when ready to inspect ECU state. A lost apply reply
is displayed as **unconfirmed**, not as successful. Use verification instead of
repeating Apply or cycling power.

## CLI

From the board repository root, using an already configured SocketCAN interface:

```sh
bash bin/m749-cli.sh --inspect-usd usd-inspection --socketcan can0
bash bin/m749-cli.sh --patch-usd usd-inspection --dry-run
bash bin/m749-cli.sh --patch-usd usd-inspection --confirm-usd-erase --socketcan can0
bash bin/m749-cli.sh --verify-usd-patch usd-inspection --socketcan can0
```

Run and assess each step separately. Replace `--socketcan can0` with
`--slcan PORT` or `--channel PCAN_USBBUS1` as appropriate. Standard flow-control
and TX-gap options are supported. Dry run is offline and accepts no connection
options. No credential import or automatic pairing workflow is added.

The inspect directory must not already exist. Inspection writes
`usd-before.bin` and `inspection.bin` without overwriting files; both must pass
size, encoding, identity and CRC checks before apply. A standalone `--read-usd`
backup alone is insufficient because it lacks the successful inspection record.
An inspection failure before saving both files is not a usable apply input.
A CRC-valid rejection is saved separately as `inspection-rejected.bin`; the
error names its status, stage, offset and destructive-started flag.
If backup collection fails, no cleanup reset is sent; check ECU state first.

Apply requires exactly one of `--dry-run` or `--confirm-usd-erase`. A durable
`apply-requested.txt` marker is created before issuing the single launch request.
The same directory cannot launch apply twice. Keep that marker after any lost
reply, interrupted transfer or crash. Do not remove it to retry an ambiguous
operation; first establish ECU state and use a fresh inspection only after the
previous attempt has been resolved.

A successful apply resets the ECU before an F000 reply is returned. Exit code
**3** means the outcome is unconfirmed and requires the separate verification
command. It is not success and must not be used as a retry signal. Other failures
return nonzero; requests are never automatically replayed. A payload that
returns without resetting is read back: already-configured options are reported
as unchanged, while rejected commands are reported as failures. The reader
helper remains active in that returning path.

Verification requires the ECU already in loader session 02. Every compared byte
must accept its expected value and reject a wrong candidate. A valid result is
saved as `verification.bin`; existing records are never overwritten. The report
binds to the original page CRC, target MCU and application, but contains no
sequence number: its age is not established. The full live option comparison
establishes current contents. Cold-boot operation, application readiness and
physical option retention remain separate checks.

See [payload source and ABI](../firmware/usd_patcher/README.md) for the build,
memory requirements and exact restrictions.

## Live inspection limitation (2026-11-09)

The connected I862BA02_w2309v4 passed application entry and target/protection
checks but inspection stopped at USD offset 048 with status 16, stage 0 and
destructive=0. Its raw halfwords at offsets 048 and 04A are both 0000; the
other halfwords have supported encodings. A complete 4096-byte duplicate-read
backup was collected and matched the earlier saved page exactly.

That build refused these reserved zero halfwords before erase. The revised
build excludes all wholly reserved byte ranges in table 5-5 (012..013,
01C..033, 044..04B) from complement-format and all-FFFF erase checks. It saves
the raw halfwords and restores their complete 16 bits without normalization;
reserved fields that already match after erase need no write. Final readback
still checks every byte. Defined fields, FAP and command/CRC guards remain
unchanged. Reserved bits within a defined field do not exempt the whole field.

The reference manual does not specify a special reserved-field restore
procedure or guarantee raw write acceptance. If the hardware ignores or
transforms a reserved write, final verification must fail and the payload must
not reset. The revised build subsequently passed the live test below. No USD programming
or cleanup reset was requested in the initial rejected inspection, and the
reader helper was left active until the later test.

Reserved ranges above follow table 5-5, pages 99-100 of the
[AT32F435/437 reference manual, Rev 2.07](https://www.arterychip.com/download/RM/RM_AT32F435_437_V2.07_EN.pdf).
They refer to wholly reserved halfwords, not reserved bits inside named options.

## Successful live test (2026-11-09)

The revised 1804-byte payload completed inspection and one explicit apply on
I862BA02_w2309v4 / 8450043732 over SocketCAN. The retained result reported
applied, stage 6, zero failures, snapshot CRC 659C4806 and final CRC 3594D0D0.
Independent byte comparisons checked all 4096 USD bytes with a wrong-candidate
control for each byte. Only offsets 010/011 changed: EOPB0=00FF -> 05FA.
FAP remained FFFF, and reserved halfwords 048/04A remained 0000.

The successful payload reset produced no final F000 reply; the CLI returned
unconfirmed (3), then separate verification returned success (0). No launch
was repeated. Verification began in session 02, but the final read-only identity
query returned F186=01 with the stock software and part number. No host reset
was requested after apply. Do not assume the session remains 02 after an idle
interval. This proves preservation for this unit and snapshot, not arbitrary
reserved-value behavior or power-loss recovery.

After the user reported a physical power cycle, the ECU again returned stock
identity I862BA02_w2309v4 / 8450043732 in session 01. Session-60 reader entry
succeeded, and two matching reads of every 1024-byte USD block produced a
4096-byte page identical to the verified post-apply page. EOPB0 remained 05FA,
FAP FFFF and reserved 048/04A both 0000. The reader was reset and session 01
was confirmed again. No additional patch was applied. This confirms option
retention and stock diagnostic operation after that power cycle; high-RAM
application operation remains untested.
