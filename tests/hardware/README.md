# Headless SLCAN investigation

Install `pyserial` in the Python environment that can open the adapter. Close
the console before running a probe. A Windows COM port requires Windows Python.

Read the signature without changing ECU settings:

```sh
python slcan_probe.py --port COM116 --poll-count 3 --trace signature.jsonl
```

Reproduce the stimulation command, recording every SLCAN line and validating
the response CRC:

```sh
python slcan_probe.py --port COM116 --command "enable self_stimulation" --gap-ms 20 --poll-count 20 --trace stimulation.jsonl
python slcan_probe.py --port COM116 --command "disable self_stimulation" --gap-ms 20
```

Use these commands only on a bench suitable for simulated engine operation.
The probe sends each command once, without retries or a settings burn. A timeout
does not prove that a command failed to execute. Stimulation can remain enabled
after a timeout; disable it explicitly or reset the ECU before another trial.
`--text` additionally reads the ECU text buffer. `--gap-ms 20` spaces consecutive
request frames; omit it to reproduce an unpaced burst. This is a diagnostic
workaround, not a production transport fix.

## Findings on the 2026-10-04 bench

- An unpaced enable request lost its third consecutive frame. ECU text reported
  18 bytes received instead of 28. A hardware breakpoint in the CAN RX FIFO
  overflow branch stopped before the HAL cleared the flag: RF0R was `0x1B`
  (three pending messages, FULL and FOVR). The overrun was present before the
  debugger halted execution. FULL alone (`0x08`) is not proof of overflow.
- With default flash settings, paced stimulation worked at 100 RPM but starved
  CAN at 1200 RPM. Debugger stacks showed trigger processing inside the TIM5
  interrupt, with CAN threads ready to run.
- Changing DIVR from /4 to /3 and enabling continuous reads allowed five
  enable/disable cycles at 1200 RPM and 100 intervening signature polls. These
  were volatile register changes, tested without an attached debugger.
- The faster flash settings did not eliminate receive overflow: 4/10 unpaced
  harmless text requests were rejected; 10/10 paced requests passed. A large
  text response also timed out during stimulation, while later signature and
  disable requests succeeded. High-load and bulk-transfer reliability remain
  unproven.

Do not treat a raw request trace as proof of execution. An execute response and
subsequent ECU observations are needed. ISO-TP currently advertises unlimited
blocks and zero separation time; negotiated pacing needs compatible support in
both firmware and host transports.

## J-Link cleanup

This application uses the Cortex-M DWT cycle counter. With J-Link V9.78,
disconnecting with only `SetDbgPowerDownOnClose=0` stopped the counter and left
firmware waiting in a polled delay. Use both commands before disconnecting:

```text
exec SetSkipDebugDeInit=1
exec SetDbgPowerDownOnClose=0
q
```

See [SEGGER's cycle-counter guidance](https://kb.segger.com/J-Link_Cortex-M_application_uses_cycle_counter).
Remove hardware breakpoints before disconnecting. Halting a running ECU can
also trigger its gap-in-time error; reset before collecting a clean trial.

## Console pacing configuration

The console reads `isotp_consecutive_frame_delay_ms` from
`shared_io.properties`. The shared default is 0 (unpaced). This board's
`shared_io.resources/shared_io.properties` sets 1 ms between consecutive
frames. Build with `ABSOLUTE_BOARD_DIR` pointing to the board checkout so
that the console jar includes this override. No delay is added to single
frames or after the final consecutive frame.

The board console passed 100 long requests and a 1200 RPM enable/20-poll/disable
trial at 1 ms on this bench, using the previously tested faster flash settings.
These results do not establish reliability at higher RPM or for all bulk
transfers. The setting controls host pacing; it does not implement negotiated
ISO-TP block size or STmin.
