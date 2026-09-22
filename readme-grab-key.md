# Read an I865 pairing file over PCAN

Run this from a terminal opened in `C:\stuff\fw\fw-m74.9`. Keep the ECU's
normal bench power and PCAN connected at 500 kbit/s on `PCAN_USBBUS1`. Close
other software using that PCAN channel.

This bench already has `ecu-backup.pair`, a complete credential from its original
backup. The command uses it for authorization, then reads the 24 output bytes
from the ECU over CAN. It does not copy them from the credential into the output.
A locked ECU needs a valid existing credential before it permits these reads;
an empty output file cannot supply its own missing authorization key.

## Start the 60-second listener and read

1. Leave the ECU powered on so the CLI can check its current session.
2. Run this one command in PowerShell or Command Prompt:

   ```bat
   .\bin\m749-cli.bat --read-pair ecu-grab.pair --channel PCAN_USBBUS1 --pair-file ecu-backup.pair
   ```

3. Wait for the build to finish and for this exact prompt:

   ```text
   IMMO listener ready. Cycle ECU bench power now; waiting up to 60 seconds for its startup challenge.
   ```

4. Within those 60 seconds, switch ECU bench power off, then back on. Keep PCAN
   and its USB connection attached. The timer starts at the prompt, not when
   you launch the batch file. No reply or second command is needed.
5. Leave the process running. It authorizes the ECU, enters the OEM loader,
   authenticates, checks compatibility and reads the bytes. Each verified byte
   is saved immediately. The read itself may continue beyond the 60-second
   startup window.
6. Success ends with:

   ```text
   Saved pair index 23; 24/24 bytes known
   Pair file complete; no erase/download requests sent
   ```

The result is `C:\stuff\fw\fw-m74.9\ecu-grab.pair`. If the ECU already reports
programming session 02, the command skips the startup listener and begins loader
checks; do not power-cycle it unless prompted.

## Resume or verify

Repeat the same command. It first checks every saved byte against the ECU,
then reads only missing indices. With a complete file, expect `Pair file checked:
24/24 known bytes` followed by the completion message. An interrupted read keeps
the last saved checkpoint. Missing indices are unknown; `00` and `FF` are valid
known values. Progress does not print key bytes.

If the listener times out, let the command exit, rerun it, wait for a new
`IMMO listener ready` prompt, and only then cycle power. Cycling after the
process has exited cannot authorize the next attempt. NRC 22 means programming
entry was refused; no byte read or flash programming follows that rejection.

The command sends no erase, firmware download or metadata-write requests.
Entering the OEM loader can reset the ECU. Keep the credential and captured
`.pair` files local; they are excluded from git by default.
