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
