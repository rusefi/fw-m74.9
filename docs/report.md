# Work report

## 2026-10-18: Add brake and clutch switch configuration

| Change | Behavior |
| --- | --- |
| Board defaults | Brake PG11 active high; clutch-down PG13 active low; MCU pulls disabled |
| Connector metadata | BB3/PG13, BC2/PG11 and BC3/PG12 available as switch inputs |
| Generated pin names and INI | Three GPIO labels added at existing enum values 109/110/111 |
| Technical guide | Wiring, input modes, stored-tune behavior and validation limits |

Defaults apply to fresh configurations; existing stored tunes retain their
assignments. PG12 can be selected manually with inverted mode. This change
adds no dual-brake plausibility check or independent clutch-bottom switch.
Connector ordering is provisional pending target-board continuity and
released/pressed pedal checks.

Validation: configuration generator and production build with -j12 passed.
Refreshed stale live-data fragments to resolve the initial missing
instantRpmRange metadata error. Kept unrelated generated changes out of this
patch; the checked-in INI change is limited to the three switch labels and
matches the generated build values. A matching fully generated INI for the
local firmware build is saved as firmware/build/rusefi_re74.9.ini beneath
ext/rusefi. Existing stored configuration layout/signature is unchanged by
the checked-in metadata correction. git diff --check passed.

No electrical or pedal-state hardware test and no ECU programming were
performed. Verify both pedals before enabling dependent features. Existing
submodule selection was preserved. No commit or push.

## 2026-10-18: Default the intake-runner valve to the long path

The unassigned PE6 valve control had an internal pull-up, which could leave
the manifold valve energized. PE6 now starts as a push-pull output with a low
latch and no pull, before normal application startup. Low switches the valve
off for the long path; high energizes it for the short path.

| Change | Result |
| --- | --- |
| board.h | PE6 output low during early GPIO initialization |
| connectors/m74_9.yaml and generated names | AA2 / 1X-A2 mapped to E6 and labeled in the output selector |
| Generated output inventory and INI | Added the valve; existing enum value 72 |
| tests/test_gpio_startup.py | Production early GPIO startup regression |
| readme-technical-details.md | Pin, polarity, default and optional controller behavior |

The startup regression failed before the correction and passes afterward,
including an inherited high latch. Configuration generation and the production
firmware build with -j12 passed. Refreshed stale live-data fragments for the
build, then removed unrelated regeneration changes and preserved existing
pedal metadata. The checked-in INI adds only the output label; its layout and
signature are unchanged. The build's matching generated INI is retained at
ext/rusefi/firmware/build/rusefi_re74.9.ini. git diff --check passed.

A later GPPWM/Lua assignment can take ownership of PE6. No automatic RPM
switching is enabled. No hardware test or programming was performed; verify
long/off and short/on operation on the target valve and vacuum plumbing.
Existing submodule selection was retained. No commit or push.

## 2026-10-18: Use GPPWM tune settings for the intake-runner valve

Supersedes the early GPIO drive described in the preceding entry, following
the user's request to control the valve through the relevant rusEFI feature
and tune table. Restored board.h and removed the fixed-startup regression.

| Change | Result |
| --- | --- |
| default_tune.cpp / default_tune.h | Fresh-tune GPPWM1 assignment to PE6, named Intake runner |
| board_configuration.cpp | Registers intake defaults through BeforeTuneDefaults |
| GPPWM1 tune settings | On/off mode (0 Hz), RPM x TPS axes, 0% table and error duty, 60% on / 50% off thresholds |
| tests/test_intake_runner.cpp / board_unit_tests.mk | Default settings and standard GPPWM table/fallback coverage |
| Technical guide and CLAUDE.md | Current control policy and existing-tune instructions |

All table cells default to long/off. A 100% table cell selects short/on;
no switching RPM is assumed. Standard GPPWM owns the output. The AA2/PE6
selector label remains. Existing stored tunes need the channel settings/table
updated and an ECU restart for the pin/frequency; firmware installation does
not migrate them. Unrelated displacement defaults were left unchanged.

Validation: host build -j12 passed; all 8 selected tests passed across
M749IntakeRunner, GpPwm and M749Analog. Coverage includes a range of RPM/TPS
values with the default table, a 100% tune edit, and invalid RPM or TPS
returning the command to off. Production build -j12 passed and generated
ELF/HEX/SREC. Saved its paired generated INI beside the ignored firmware build
artifacts, then restored unrelated generation changes while preserving pedal
and PE6 labels. git diff --check passed; existing submodule selection retained
and its internal worktree is clean.

Follow-up: apply settings to existing tunes and verify connector continuity,
long/off and short/on valve movement, then tune the desired switching region.
No hardware programming, commit or push.

## 2026-10-18: Investigate intermittent Java UI CI failure

Investigated Build Unit Tests run 39136598212, job 117702250521, at
85c26e254726. The Java test/package step failed: 213 tests completed, one
failure in M749UiTransferTest.readUsesSaveSelectionProgressAndResumeOptionsWithoutBlockingEdt
at line 51 (the command-argument equality assertion). Memory-contract and
bundle-packaging checks passed; the subsequent firmware unit-test step was
skipped. The job has no uploaded test-report artifacts, so CI's exact actual
argument list is unavailable.

The same assertion failed in earlier run 39086569335, before the intake-runner
change; intervening run 39131994717 passed. No Java UI, submodule-reference
or unit-test workflow changes occur between that passing head and the failed
head. The failure is consistent with an existing discovery/selection race.

Confirmed the race with a temporary test outside the checkout. Paused the
worker after its channels callback was queued but before busy(true), then
clicked Read on the EDT. The dropdown already selected PCAN_USBBUS2 and the
button was enabled, but transferFile captured selectedConnection with channel
"auto". The resulting arguments differ from the failing test's expected list
only at that endpoint. showChannels updates selectedChannel and controls
before the later connection callback resolves selectedConnection; uploading
then makes that callback skip its update. Checking enabled and clicking in
one EDT event does not close this earlier window.

Validation: unmodified M749UiTransferTest passed all 4 tests. The exact CI
Gradle task selection (:custom-java-ui:test :ui:shadowJar) passed locally with
213 tests, zero failures, and a packaged UI. A separate deterministic probe
passed while explicitly asserting the current bad behavior described above.
The local Gradle test worker used Java 11. The working checkout is ee8ceb63;
its two commits after the failed head contain generated files, not Java UI
changes. Temporary probe source/init script and logs remain under /tmp;
no production or repository test code was changed.

Follow-up: keep controls disabled through discovery until the connection
snapshot matches the selected endpoint; make the UI fixture wait for that
resolved connection, and retain deterministic coverage of the callback gap.
Upload JUnit XML/HTML on CI failure so future assertions include expected
and actual values. No workflow rerun, commit, push or hardware operation.

## 2026-10-18: Fix the Java UI discovery/transfer race

Read and Write now remain disabled until the selected connection's discovery
callback has completed. M749Panel tracks that completion separately from the
short-lived querying flag, which can still be false when the channel dropdown
first populates. Endpoint/connector edits and panel reattachment invalidate
the completion state. A completed identification failure still enables the
existing backup/restore flow; successful firmware identification is not a new
requirement.

Added a deterministic M749UiTransferTest regression. Its backend pauses the
worker between the channel-list callback and the busy/resolved-connection
callbacks. The test checks that PCAN_USBBUS2 is displayed while both transfer
buttons remain disabled, a disabled click does nothing, and a subsequent read
uses PCAN_USBBUS2 after discovery completes.

Validation: first asserted the old bad behavior in a passing reproduction,
then changed the expectations and observed failure before the production fix.
The transfer and connector tests passed after the fix, including identification
failure behavior. The exact CI task selection,
`bash bin/java-ui.sh --console=plain :custom-java-ui:test :ui:shadowJar`, passed:
214 tests, zero failures/errors/skips, and successful UI packaging. Java 11 is
the configured test toolchain. git diff --check passed. No hardware operation,
workflow rerun, commit or push; submodule worktree unchanged.

## 2026-10-18: Add the LIN master protocol core

Added a transport-independent 19200-baud LIN master with an 11-slot schedule:
three periodic publishers, seven response polls and one optional queued
publisher. The normal cycle is 100 ms; the optional eight-byte classic frame
adds 20 ms when queued. Other frames use enhanced checksum. Complete transmit
payloads must be explicitly supplied before starting.

| File | Change |
| --- | --- |
| firmware/vehicle_lin.h / vehicle_lin.cpp | Schedule, parity/checksum, publisher snapshots, echo/response validation, timeouts and sample freshness |
| board.mk / board_unit_tests.mk | Production and host compilation plus focused test registration |
| tests/test_vehicle_lin.cpp | Eight protocol tests including malformed/late frames and timer wrap |
| docs/lin-bus.md / readme-technical-details.md | API, transport requirements, frame table and live-support limits |
| CLAUDE.md | Variant-dependent UART routing and schematic direction discrepancy |

There is no active UART, GPIO owner, timer thread or sensor-field assignment.
USART1 PA9/PA10 and USART3 PD8/PD9 are variant-dependent options, and the
schematic's K_RX/K_TX direction needs actual continuity validation. The live
transport also needs qualified transceiver/master pull-up and device signal
mappings. No operating payloads, engine meanings or tune defaults were assumed.
Only complete valid responses refresh samples, and callers choose a maximum
sample age. Stop clears queued optional traffic and invalidates receive data.

Validation: board host make -j12 and M749Lin.* pass all eight tests. Cases cover
frame order/lengths, parity/checksum carry and diagnostic checksum exceptions,
explicit payload requirements, immutable in-flight data, all response lengths,
bad echoes/checksums, missing/late responses, freshness, transport errors,
stop/restart, optional scheduling, missed slots and clock wrap. Production
build -j12 passed and compiled the new source, generating ELF/HEX/SREC.
Unused protocol sections may be removed by the linker until a transport is
connected; a successful build is not a claim of live LIN operation.

Preserved a matching generated INI in the ignored firmware build directory,
then restored unrelated generated output. Submodule worktree is clean and
both source/documentation whitespace checks pass. Follow-up: verify actual
routing, add the UART/IRQ/timer transport, qualify signal mappings and test on
hardware. No ECU programming, commit or push.


## 2026-10-27: Reject unsupported RAM setup before software erase

Added a read-only RAM eligibility check after loader profile matching and
before application erase, including software --check-target. Valid existing
384/448/512 KiB options pass with protection enabled. First-boot adjustment
requires EOPB0=FFFF, access=5AA5, the supported MCU and no SLIB restriction.
Non-erased insufficient/invalid options and failed or untrustworthy reads
stop before erase, download, metadata writes or activation reset.

| File | Change |
| --- | --- |
| M749RamPreflight.java / M749Uploader.java | Startup eligibility gate and observed-value diagnostics before software mutation |
| M749ChecksumReader.java | Separate one-byte comparison path limited to 12 option/identity/SLIB bytes; flash-reader bounds unchanged |
| M749RamPreflightTest.java / M749UploaderTest.java | Accepted/rejected configurations, malformed/lost/negative replies, wrong-candidate controls, address bounds and request-order assertions |
| M749OemImageTest.java | All OEM profiles restore with protected 128 KiB options |
| docs/cli-uploader.md | Gate behavior, scope and first-boot validation limits |

Validation: all 222 custom-console tests passed with no failures/errors/skips.
Built :ui:shadowJar and :custom-java-ui:installM749Cli with board discovery
settings. Packaged console includes the new class and
use_canbus_connector=true. Live --check-target over can0 rejected
EOPB0=00FF access=FFFF MCU=70084540 in about three seconds, with expected exit
1 and no erase/programming request. The local software image CRC 46577FAF
was used only for preflight and was not uploaded. OEM recovery and
calibration-only updates retain their existing workflow.

Eligibility does not replace startup CRC, marker or option-write validation.
No live accepted-case upload or option change was performed. The updated
console must be used to obtain the new check; older bundles are unchanged.
Tracked submodule files remain clean; unrelated local files were preserved.
No commit or push.


## 2026-10-30: Read flash includes a separate FF01 USD backup

Read flash now saves the main-flash BIN followed by a separate 4096-byte
<filename>.usd.bin covering 1FFFC000..1FFFCFFF. The UI passes --with-usd;
CLI callers can select it explicitly. The main-flash BIN format is unchanged.

| Change | Behavior |
| --- | --- |
| M749UsdReader / M749ChecksumReader | Read each physical USD byte using FF01, require matching and wrong-candidate controls, retain complements/reserved/user data |
| FlashReadFile | Separate USD checkpoint format, 64-byte progress, saved-byte verification on resume and no overwrite |
| M749ReadFlashCli / M749Cli | Publish main flash, reset helper, reauthorize pairing if supplied, validate resident loader, read USD; standalone --read-usd for recovery |
| M749Panel | Read flash includes USD and shows companion filename in the save dialog |
| Tests / CLI guide | Complete capture, session ordering, rejected admission, failures, preserved main image, resume, bounds and usage |

Validation: all 228 custom-console tests passed, zero failures/errors/skips.
Built :ui:shadowJar and :custom-java-ui:installM749Cli with ABSOLUTE_BOARD_DIR.
Confirmed packaged M749UsdReader, UI --with-usd flag and
use_canbus_connector=true. Installed CLI --read-usd --help passed without
adapter access. git diff --check passed.

A failure in the USD stage retains the completed main-flash image and does
not request another reset. --read-usd with --resume retries the partial USD
image without uploading the helper again. The read path sends no erase,
programming or metadata-write commands. No physical ECU/CAN/debug access was
performed; full USD export and the combined session transition still need
hardware validation. Existing submodule pointer and unrelated files were
preserved. No commit or push.


## 2026-10-30: Replace USD FF01 reads with RAM-helper block reads

Reimplemented the USD stage at user request using the existing RAM helper's
SID 23. Main flash and the separate 4096-byte USD image now share one helper
session; there is no reset or resident-loader authorization between stages.

| Change | Result |
| --- | --- |
| M749RamHelper / M749UsdReader | Dedicated USD bounds, up to 4080 bytes per request, two matching reads per new block |
| Read CLI | --with-usd uses the current helper; standalone --read-usd supports --helper-running and --chunk-size |
| FlashReadFile | M749USD2 checkpoint, helper hash, CPU identity and duplicate-read semantics; old FF01 progress rejected |
| Tests / CLI guide / CLAUDE.md | Updated single-session behavior, resume, boundaries and validation limits |

Removed the added USD-specific FF01 path from M749ChecksumReader; existing
preflight and diagnostic behavior remains unchanged. Complete main-flash
output survives USD failure, with no failure reset. Resume verifies saved
USD bytes before extending the file. The helper binary itself is unchanged.

Validation: all 229 custom-console tests passed, zero failures/errors/skips.
Coverage includes all raw byte values, 4080+16 byte split, two reads per
block, mismatched/short/wrong-address/lost replies, cancellation, saved-byte
mismatch, retained main image, running-helper resume, fresh bootstrap,
probe rejection and old-format checkpoint rejection before adapter access.
The combined test checks one helper upload and only one final reset after
both outputs exist. Built console/CLI, verified packaged helper-based USD
class and use_canbus_connector=true, checked CLI help and git diff --check.
No ECU/CAN/debugger access was performed. Full physical USD export remains
unvalidated. Existing unrelated changes were preserved. No commit or push.


## 2026-10-30: Live RAM-helper USD read attempt over can0

Tested the rebuilt console against the failed-upload I862 unit at the user's
request. can0 was up at 500 kbit/s with no reported CAN errors. Before and
after the attempt, identity returned F186=02, F189=M749-29444F39 and
F192=8450043732. --read-usd sent 10 60 and received 7F 10 11. The command
exited 1 with zero verified bytes and no published USD BIN. It stopped before
security access, RAM upload or helper execution; no erase, download, option
programming or reset was requested.

This confirms rejection of helper bootstrap from the current resident-loader
state. Full USD reads through the helper remain physically unvalidated. A
working OEM application session is required even when option memory is intact.
Documented this limitation; no code changes, firmware restoration or debugger
access. No commit or push.


## 2026-10-30: Console HeadlessException on Linux

The console failed to start with java.awt.HeadlessException although
DISPLAY=:1 was set. Only openjdk-21-jre-headless and openjdk-17-jre-headless
were installed; their lib directories contain libawt_headless.so but no
libawt_xawt.so, so Swing cannot open a window. Fix: install a full runtime
(openjdk-21-jre). Added this requirement to readme.md. No code changes, no
ECU access. No commit or push.


## 2026-10-30: Console missing the M74.9 tab

ext/rusefi/console/rusefi_console.jar built at 13:59 contained no
com/rusefi/m749 classes, had use_canbus_connector=false and no Board-Git
manifest attribute: it was built without RUSEFI_CUSTOM_JAVA_UI_DIR and
ABSOLUTE_BOARD_DIR. Rebuilt with
ABSOLUTE_BOARD_DIR="$PWD" bash bin/java-ui.sh :ui:shadowJar. The new jar has
76 com/rusefi/m749 entries, use_canbus_connector=true, Board-Git 633703a26c78
and Rusefi-Git 8271de1fb099. Documented the command in
readme-technical-details.md. GUI startup not checked by me; no ECU access.
No commit or push.


## 2026-10-30: compile_firmware.sh bundle built a generic console

compile_firmware.sh exported neither RUSEFI_CUSTOM_JAVA_UI_DIR nor
ABSOLUTE_BOARD_DIR, so its bundle target rebuilt
ext/rusefi/console/rusefi_console.jar without the M74.9 tab and with
use_canbus_connector=false, overwriting an earlier correct bin/java-ui.sh
build. _compile_bundle.sh set only RUSEFI_CUSTOM_JAVA_UI_DIR. Both scripts now
export both variables, as CI does. tests/check_bundles.py also requires
use_canbus_connector=true in the bundled console's shared_io.properties.

Validation: bash compile_firmware.sh bundle -j12 exited 0. The console jar and
the jar inside artifacts/rusefi_bundle_re74.9.zip both register
com.rusefi.m749.M749TabProvider, have use_canbus_connector=true and carry
Board-Git/Rusefi-Git manifest attributes. GUI display of the tab was not
checked here; no ECU access. No commit or push.


## 2026-10-30: Clearer read-flash diagnostics in resident-loader state

Console log from the I862 unit (F186=02, F189=M749-29444F39, F192=8450043732)
over can0: identification succeeded, F1A0/F1A4 returned NRC 31, and two
Read flash attempts stopped at 10 60 with 7F 10 11, as recorded earlier today.
The log had three misleading parts:

- every negative response also printed "UDS failure ... no ECU flow control
  received" with a transport dump, although a complete single-frame reply had
  arrived. UdsClient now leaves NRC reporting to the caller and labels
  single-frame requests as such in transport-failure dumps;
- the 10 60 rejection gave no reason. M749RamHelper now explains that NRC
  11/12/7E/7F means session 60 needs a running OEM application, that an ECU
  left in the resident loader rejects it, and that nothing was uploaded,
  erased or reset. The typed NegativeResponse is still thrown;
- the failure text referred to saved progress and --helper-running before the
  helper had started. M749ReadFlashCli now says the read stopped before the
  RAM helper started and no flash data was read.

Validation: 230 custom-console tests, 0 failures/errors/skips, including new
M749RamHelperTest and UdsClientTest cases. Rebuilt the console jar with
board settings. No ECU access in this step; the read limitation itself is
unchanged. No commit or push.


## 2026-11-09: Add explicit USD maintenance to CLI and console

Added an I862 option-maintenance workflow that preserves flash protection and
all raw USD contents except EOPB0=00FF -> 05FA. It is separate from ordinary
firmware upload and requires the supported OEM application in session 60.

| Change | Result |
| --- | --- |
| firmware/usd_patcher | Standalone source, build and resource-consistency target; 1812-byte production image |
| CLI | --inspect-usd, --patch-usd with dry-run or explicit erase confirmation, --verify-usd-patch |
| Console | USD maintenance chooser for inspection, confirmed apply and verification on the selected connection |
| Backup and replay controls | Full saved page plus validated inspection; write-once outputs and apply-attempt marker before launch |
| Result handling | Lost apply reply is unconfirmed (exit 3); no automatic replay or cleanup reset |
| Verification | Checked retained result and all 4096 live USD bytes, each with a wrong-candidate control |
| Transport | Explicit bounded long-running response wait; normal request/flow-control timing retained |
| Documentation | Standalone usage, exact target constraints, failure handling and qualification limits |

Validation: all 243 custom-console Java tests passed with no failures, errors
or skips. Coverage includes malformed snapshots/reports, reserved encodings,
wrong target/session, staging failure, duplicate apply rejection, full-page
comparison, lost replies, late responses, UI cancellation and transfer exclusion.
Rebuilt the payload with GCC 14.2.1; check-bundle confirms exact resource bytes
and SHA-256 6c638b2df07937561f36463bb9798d338507b6d7eed81cb8369d2f2b0b6eb4fe.
CLI and console packaging completed; resource and discovery-setting checks are
recorded with the final session validation.

No ECU hardware access or option programming was performed. Physical option
erase/restore, full launch/collection lifecycle, watchdog timing and reset/power
retention remain to be qualified. A resident-loader-only ECU still needs usable
OEM application software before launch. Existing generated files and submodule
changes were preserved. No commit or push.

Final packaging checks passed: CLI --patch-usd --help exits zero without device
access; console JAR contains the exact production USD BIN and maintenance
classes; shared_io.properties retains use_canbus_connector=true. Whitespace
checks and ASCII checks for the new guides passed.


## 2026-11-09: Live USD maintenance connection check

Attempted identification on the user-provided can0 before USD inspection or
apply. The interface was up at 500000 bit/s, initially ERROR-ACTIVE. All five
identification requests (F186/F189/F192/F1A4/F1A0) timed out with no ECU reply.
The interface subsequently reported ERROR-PASSIVE. CLI exited 1.

No session change, security request, RAM upload, reset, option erase or option
programming was sent. Inspection and apply were not reached. Follow-up: confirm
ECU power/key-on, CAN wiring and adapter selection before retrying. No code
changes, commit or push; physical patch validation remains outstanding.


## 2026-11-09: Retry USD maintenance connection check

Retried read-only identification after the user confirmed readiness. All five
DID requests timed out again, with no ECU responses. can0 remains ERROR-PASSIVE
at 500000 bit/s. A host-interface restart was attempted but denied by OS
permissions; noninteractive sudo requires a password. No interface change or
ECU reset occurred. Asked the user to restart can0 locally before another check.
No session change, security request, RAM upload or option programming was sent.
USD inspection and apply remain untested on hardware. No commit or push.


## 2026-11-09: Live I862 inspection and rejection diagnostics

Stock I862BA02_w2309v4 / 8450043732 responded on can0 after connection recovery.
Session 60, security, RAM payload upload and inspection return succeeded.
The inspection report matched MCU 70084540, loader D7B6B894 and application
B167C266, but rejected the option contents with status 16, stage 0, offset 048,
EOPB0=00FF and destructive=0.

Collected the complete raw 4096-byte USD page, confirming each block twice.
Reserved halfwords at offsets 048 and 04A are 0000. The full page matches the
previous backup exactly. Their preservation across erase is not established,
so the option guard remains unchanged and no apply was attempted. No USD
erase/programming or cleanup reset was sent; the RAM reader was left active.

| Change | Result |
| --- | --- |
| Inspection diagnostics | Save CRC-valid rejection record; show stage, offset, EOPB0 and destructive flag |
| Regression | Reserved 0000 values remain rejected; no usable apply inspection published |
| Documentation | Describe the observed limitation and required preservation follow-up |

Validation: 244 custom-console Java tests pass with no failures/errors/skips;
CLI and console rebuilt. Live inspection and full USD readback are now checked,
but physical erase/restore and reset/power retention remain unqualified. No
payload changes, commit or push. Existing unrelated work was preserved.


## 2026-11-09: Preserve raw reserved USD values

Updated USD maintenance at user request after checking reference-manual table
5-5. Wholly reserved byte ranges 012..013, 01C..033 and 044..04B now bypass
complement-format and all-FFFF erase checks. Named fields retain their checks,
including fields containing individual reserved bits.

| Change | Result |
| --- | --- |
| Preflight and command validation | Accept raw values in all 17 reserved halfwords |
| Restore | Pass each saved reserved value as a complete 16-bit store, without normalization; skip already-matching slots |
| Verification | Require exact final page contents; ignored or transformed writes fail and cannot trigger reset |
| Production resource | 1804 bytes; SHA-256 b112975af46925c3ca1f651a18a97fd68d733f38b3fa7d050eea3852428b213e |
| Documentation | Exact reserved ranges, raw restore contract and physical validation limits |

Validation: 245 custom-console Java tests passed with zero failures/errors/skips,
including every reserved halfword and adjacent defined-field boundaries. Both
payload builds match the bundled resource; CLI and console rebuilt with the
new integrity guard, and use_canbus_connector=true remains packaged. The
complete saved USD page is now accepted by offline input validation without
changing its bytes. A fresh successful live inspection is still required.

The manual does not guarantee arbitrary reserved raw-write acceptance. Exact
readback remains mandatory; this revision has not been applied on hardware.
No ECU requests, reset, erase or programming occurred in this work unit.
No commit or push. Existing unrelated changes were preserved.


## 2026-11-09: Successful live USD maintenance apply

Ran the revised payload on protected I862BA02_w2309v4 / 8450043732 via can0.
Saved and verified the full page before applying; inspection passed and the
ECU returned to stock session 01. Launched apply exactly once. The expected
missing final reply produced exit 3, followed by separate successful verification.

| Check | Result |
| --- | --- |
| Payload SHA-256 | b112975af46925c3ca1f651a18a97fd68d733f38b3fa7d050eea3852428b213e |
| Retained result | Applied, stage 6, zero failures, flash status 20 |
| USD CRC | 659C4806 -> 3594D0D0 |
| Full-page verification | 4096 expected-byte matches and 4096 wrong-candidate rejections |
| Changed bytes | 010: FF -> FA; 011: 00 -> 05 (EOPB0=05FA, 384 KiB selection) |
| Preserved | FAP FFFF, reserved 048/04A=0000 and all other USD bytes |
| Final identity | F186=01, I862BA02_w2309v4, 8450043732 |

No host reset or second apply was sent after the apply request. Verification
began in session 02 but later identity returned session 01. Corrected the CLI
wording that assumed the ECU remained in the loader and rebuilt CLI/console.
Physical preservation and retained-result retrieval passed on this unit; no
cold power cycle, high-RAM application operation, interrupted-write recovery
or watchdog timing margin was tested. Exact preservation does not establish
whether reserved fields survived erase or were rewritten. Documentation and
durable guidance now distinguish this successful bench check from those limits.
No payload behavior change, commit or push; unrelated work preserved.

## 2026-11-09: USD option retention after physical power cycle

After the user reported a physical power cycle, verified the protected I862
ECU over can0. Stock identity and application session-60 reader entry worked.
Collected two matching reads of each 1024-byte USD block and compared the
complete page with the verified post-apply contents. No patch was reapplied.

| Check | Result |
| --- | --- |
| Software / part | I862BA02_w2309v4 / 8450043732 |
| USD retention | All 4096 bytes identical to verified post-apply contents |
| EOPB0 / FAP | 05FA / FFFF |
| Reserved 048 / 04A | Both 0000, preserved exactly |
| Cleanup | Reader reset acknowledged, stock identity and session 01 confirmed |

Identification, USD read and final identification all returned success.
Updated maintenance guidance and payload qualification notes. Power interruption
was reported by the user; no electrical power measurement was taken. High-RAM
runtime operation, watchdog margins and interrupted-write recovery remain
untested. No implementation change, commit or push.

## 2026-11-09: Prepare USD maintenance commit

User explicitly requested committing the completed USD maintenance work.
Reviewed the implementation, payload resource, tests, guidance and hardware
validation notes. Updated stale CLI/console wording to reflect the successful
single-unit apply and power-cycle retention checks. High-RAM runtime and
interrupted-write recovery remain untested.

The commit includes only USD maintenance changes and their report entries.
Unrelated simulator notes, generated files, local scripts and submodule work
remain outside the commit. No push or additional ECU operation was requested.

Validation: 245 Java tests passed with zero failures/errors/skips; CLI and
console builds succeeded. Bundled payload matches the 1804-byte production
image, console messages are current, and CAN connector discovery is enabled.
Whitespace checks passed.

## 2026-11-09: Fix console USD inspection folder selection

Inspection failed before adapter access when the directory chooser selected an
existing home folder. The CLI correctly required a new directory, but the UI
passed the selected folder directly and displayed only the path from the
FileAlreadyExistsException. The inspection did not reach the ECU.

| Change | Result |
| --- | --- |
| Console selection | Choose an existing parent; create a new timestamped inspection child |
| CLI diagnostics | Explain existing destinations and unusable parents before adapter access |
| Regression tests | Complete inspection into a fresh child, preserve parent files, reject existing destinations without opening an adapter |
| Guidance | Describe parent-folder selection and using the resulting child for Apply/Verify |

Validation: 247 Java tests passed with zero failures/errors/skips; CLI and
console builds succeeded. Reproduced the existing-folder rejection with the
installed CLI and confirmed the actionable error without opening CAN. Verified
the rebuilt console contains the fix, the exact unchanged 1804-byte payload
and use_canbus_connector=true. Whitespace checks passed. Tests use a simulated
transport; no new hardware inspection was run. Restart the console to load the
rebuilt JAR. Changes remain uncommitted; unrelated work was preserved.

## 2026-11-09: Amend USD maintenance commit with folder fix

At explicit user request, include the inspection-folder fix, regression tests,
updated guidance and related report entries in the existing USD maintenance
commit. Validation remains 247 passing Java tests and successful CLI/console
builds; staged whitespace and scope checked. No implementation changes since
that validation. Unrelated simulator notes, generated files, local scripts and
submodule changes remain uncommitted. No push or additional ECU operation.
