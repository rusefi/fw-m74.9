# LIN protocol core

`firmware/vehicle_lin.h` provides a transport-independent 19.2 kbit/s LIN master.
It is compiled by the board and host build rules. It does not initialize a UART,
claim pins, run a thread, or connect received fields to engine controls. Live LIN
support still requires a board transport and qualified device signal mappings.

| Slot | ID | Protected ID | Data bytes | ECU role | Checksum | Slot time |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 3B | FB | 8 | Publish once when queued | Classic | 20 ms |
| 1 | 29 | E9 | 4 | Publish | Enhanced | 10 ms |
| 2 | 0F | CF | 2 | Publish | Enhanced | 10 ms |
| 3 | 20 | 20 | 4 | Publish | Enhanced | 10 ms |
| 4 | 30 | F0 | 8 | Poll response | Enhanced | 10 ms |
| 5 | 12 | 92 | 2 | Poll response | Enhanced | 10 ms |
| 6 | 15 | 55 | 4 | Poll response | Enhanced | 10 ms |
| 7 | 18 | D8 | 2 | Poll response | Enhanced | 10 ms |
| 8 | 26 | A6 | 2 | Poll response | Enhanced | 10 ms |
| 9 | 05 | 85 | 2 | Poll response | Enhanced | 10 ms |
| 10 | 10 | 50 | 2 | Poll response | Enhanced | 10 ms |

IDs are six-bit frame identifiers; data lengths exclude the checksum. The normal
cycle is 100 ms. A queued slot 0 adds 20 ms on its next cycle. The first slot is
due 10 ms after starting. `protectedId` computes parity; `checksum` includes the
protected ID for enhanced mode and forces classic mode for diagnostic IDs 3C/3D.
See the [LIN 2.2A frame-layer specification](https://www.lin-cia.org/fileadmin/microsites/lin-cia.org/resources/documents/LIN_2.2A.pdf).

Use one owner for all `m749lin::Master` calls. Supply complete payloads for
periodic slots 1..3 through `setPublisher`, then call `start(nowMs)`. No transmit
payloads are supplied by default. Slot 0 is optional: each successful update
queues one transmission; another update replaces a still-queued payload. Changes
to a publisher preserve the snapshot of any transfer already in progress.

Call `poll(nowMs, transfer)` frequently. Each returned transfer requires a LIN
break of at least 13 dominant bits, a recessive delimiter, and then the supplied
bytes at 19200 baud, 8N1. The byte array starts with 55 and the protected ID; an
ECU publisher also includes its payload and checksum. Missed slots produce one
new transfer and rebase the next deadline, without a burst of overdue messages.
The millisecond clock wraps modulo 32 bits; service intervals must stay below
half that range.

Deliver the local break through `onBreak`, and all subsequent received bytes
through `onByte`, including local sync/PID/publisher echo. A framing-error zero
must be classified by the transport's break status; an ordinary zero data byte
is not a break. Deliver overrun, noise and other UART failures through
`onTransportError`. An unexpected second break aborts the current transaction.
Only correct echoes and complete responses with matching checksums finish a
transaction. Late responses are rejected at the slot deadline even if `poll`
has not yet run. Statistics count received/published frames, errors and timeouts.

Use `read(slot, nowMs, maxAgeMs, sample)` to obtain a complete subscribed payload
within the caller's freshness limit. A failed frame never refreshes a prior
sample. Only the declared length is copied. `stop` aborts the transfer, clears
queued slot 0 and invalidates received samples; explicitly supplied publisher
payloads and cumulative statistics remain available for a later start.

The board routing must be verified before a transport is enabled. Relevant UART
options are USART1 TX PA9 / RX PA10 and USART3 TX PD8 / RX PD9, alternate function
7; they are variant-dependent. The schematic's K_RX/K_TX labels have a direction
discrepancy, and the connector catalog gives BD2 a K-line / CAN L variant role.
Confirm actual continuity, voltage levels, transceiver and master pull-up before
selecting pins. These pins are not initialized by this component.

`tests/test_vehicle_lin.cpp` covers frame order/lengths, checksum carry and
diagnostic exceptions, explicit payload requirements, echo and parity failures,
all response lengths, freshness, sporadic scheduling, partial/late responses,
UART failure, stop/restart, missed slots and timer wrap. Host tests and a firmware
build do not establish connector routing, electrical timing or device operation.
There is no LIN tune page or automatic assignment of raw fields to sensors.
