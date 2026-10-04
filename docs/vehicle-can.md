# Lada vehicle CAN profiles

The board-specific **M74.9 CANbus** panel contains the **Lada CANbus profile**
selector. It controls vehicle receive and dashboard transmit on CAN1
(500 kbit/s, G0 RX, G1 TX); diagnostics remain available in every selection.

| Value | Selection | Software applications |
| --- | --- | --- |
| 0 | Disabled | No board vehicle RX/TX |
| 1 | Largus | I835LB52 / I865LB52 |
| 2 | Niva / Granta | Niva Legend I812NA01, Niva Travel I812TA01, Granta I832GA01 |

Unknown values also disable board vehicle RX/TX. New configurations and tunes
missing the field default to Largus. The setting is saved with the tune and
is not forced by board overrides. Select the matching vehicle application;
the ECU loader version does not automatically select the CAN profile.

**Upgrade from the former checkbox:** save/export the tune before updating,
then import it using the new INI and explicitly select the profile. The field
is now a byte enum at offset 15784, replacing a bit at offset 15780 bit 12;
the configuration grows from 16228 to 16232 bytes. Old binary settings fail
the storage size check and are not migrated in place. Legacy yes/no labels
are not the new enum labels; do not assume an imported checkbox picked the
right profile. Review the imported tune, including knock tables, before use.

Changes apply live; a previously queued transmit frame may finish. Live
configuration apply releases the fallback speed sensor slot and invalidates
its value/counter history. Every profile change requires a fresh speed frame.
An explicitly enabled generic CAN VSS or assigned frequency VSS pin takes
precedence in both profiles. Changing that underlying generic CAN/pulse source
still requires restart for the source's full initialization.

## Largus receive

| Field | Contract (zero-based bytes) |
| --- | --- |
| ID and length | Standard 0x29A, exactly 8 bytes, data frames only, CAN1 only |
| Speed | Bytes 4..5, unsigned big-endian, 0.01 km/h per bit |
| Unavailable | Raw 0xFFFF immediately invalidates the sensor |
| Upper limit | Other raw values above 50000 clamp to 500 km/h |
| Alive counter | Byte 6 bits 3:0; any change is accepted, including skip/backward/wrap |
| Checksum | Byte 7 = one's complement of the modulo-256 sum of bytes 0..6 |
| Freshness | The last valid speed expires when its age exceeds 100 ms |

The checksum covers all of byte 6, not just the counter nibble. It does not
include the CAN ID. A first counter of zero is accepted. Bad checksums,
duplicate counters and malformed frames leave the last reading and its
timestamp unchanged. Counter state persists across timeouts, so a stuck
stream cannot periodically refresh the reading. A valid changed counter
recovers immediately; a valid 0xFFFF frame also advances counter state.
This receiver deliberately rejects integrity failures instead of consuming
their speed values. Invalid and timed-out readings are unavailable through
the sensor API, rather than valid zero-speed readings.

Example 50.00 km/h frame: `00 00 00 00 13 88 00 64`. The following frame may
be `00 00 00 00 13 88 01 63`. Send at 20 ms intervals. No dashboard bias or
averaging is applied to the received vehicle speed.

Other vehicle receive IDs are not decoded here.

## Largus transmit

All frames are standard data frames on CAN1, with the DLCs below. They have
no application alive counter or payload checksum. The profile also respects
the global CAN write enable. Frames are staggered on the shared 5 ms CAN
worker: at most four profile frames per tick, 661 frames per second when all
sensors are valid. The first second includes all nineteen IDs. Transmission
continues while enabled; it does not implement an ignition sleep timer.

| Period | IDs (DLC) |
| --- | --- |
| 10 ms | 0x186 (7), 0x189 (8), 0x18A (6), 0x1F6 (8) |
| 20 ms | 0x217 (8), 0x2A9 (1), 0x2C6 (6) |
| 100 ms | 0x36E (4), 0x3A5 (3), 0x41A (8), 0x41D (4), 0x511 (7), 0x522 (8), 0x5DA (8), 0x648 (8), 0x65C (3), 0x66A (8), 0x6FD (4) |
| 1000 ms | 0x5E2 (2) |

| Live field | Encoding and source |
| --- | --- |
| 0x186 bytes 0..1 | Big-endian RPM * 8, including cranking RPM |
| 0x1F6 byte 2 bits 7:6 | 0 stopped, 1 cranking/spinning up, 2 running |
| 0x217 byte 2 | 0x70 while RPM is positive, otherwise zero |
| 0x217 byte 3 and byte 4 high nibble | Indicated speed in 0.1 km/h; positive speed uses approximately 1.02 gain and 1.95 km/h offset with fixed-point rounding; zero/unavailable stays zero |
| 0x5DA byte 0 | Coolant degC + 40 |
| 0x5DA byte 1 | Current idle target / 8 rpm |
| 0x5DA byte 4 | Configured hard RPM limit / 32 rpm |
| 0x65C byte 0 bits 7:1 | Intake degC + 40 |

Fields saturate at their representable limits, including RPM at 8191.875,
indicated speed at 409.5 km/h, coolant at -40..215 degC and intake at
-40..87 degC. No additional speed averaging is applied. Invalid/non-finite
RPM and speed produce zero. Invalid/non-finite coolant or intake suppresses
its entire frame (0x5DA or 0x65C) until the sensor recovers, since no sensor
fault encoding is defined here.

Remaining fields use the fixed baselines in `firmware/vehicle_can_tx.cpp`.
Torque, demand, warning/MIL flags, auxiliary speed, consumption and other
unassigned fields are not synthesized from unrelated sensors. This is a
bounded dashboard profile, not complete vehicle compatibility: those fields,
warning lamps, receiver acceptance and applicability to each Lada model still
need bench/vehicle validation. It does not implement immobilizer traffic.

## Niva / Granta receive

Standard data frame **0x28C, exactly two bytes**, carries unsigned big-endian
speed in bytes 0..1 at 0.01 km/h per bit. It has no counter or checksum.
CAN1, standard data frames only; other IDs, lengths, remote frames and extended
frames are rejected. A repeated identical valid payload refreshes freshness.
Raw FFFF immediately invalidates speed; other values above 50000 clamp to
500 km/h. The last valid reading expires after 100 ms. Example 50 km/h: `13 88`.
The exact-length, unavailable-value and freshness rules are this receiver's
validation policy. No 0x29A decoding or 0x217 speed forwarding is enabled.

## Niva / Granta transmit

Fourteen periodic standard data frames are staggered on the same 5 ms worker,
at most four per tick and 770 per second with valid coolant. The global CAN
write enable applies to this profile too.

| Period | IDs (DLC) |
| --- | --- |
| 10 ms | 0x1F9 (8), 0x180 (8), 0x160 (7), 0x182 (8), 0x186 (7), 0x18A (6), 0x189 (8) |
| 100 ms | 0x35D (8), 0x551 (8), 0x6E2 (6), 0x5DA (8), 0x65C (2), 0x314 (8), 0x68E (8) |

| Live field | Encoding and source |
| --- | --- |
| 0x180 bytes 0..1 | Big-endian RPM * 8, including cranking RPM; saturates at 8191.875 rpm |
| 0x551 byte 1 | Coolant degC + 40; saturates to -40..215 degC |
| 0x1F9 byte 0 high nibble | Independent modulo-16 counter |
| 0x35D byte 6 high nibble | Independent modulo-16 counter |
| 0x551 byte 5 high nibble | Independent modulo-16 counter |

Counters begin at zero and advance once per emitted message, preserving the
other nibble. They continue across scheduler wrap, and reset when profiles
change or global transmission is disabled. A suppressed 0x551 frame does not
advance its counter. Invalid/non-finite RPM becomes zero; invalid/non-finite
coolant suppresses 0x551 until recovery.

Shared IDs have different layouts: **0x186 bytes 0..1 and 0x5DA byte 0 stay
zero**. 0x65C is two bytes, with byte 1 zero. The three non-periodic services
0x70F/0x711/0x6D7 are not sent. There is no Largus 0x217 or 0x1F6 output.

This implementation drives RPM and coolant; other fields use fixed
compatibility baselines in `firmware/vehicle_can_tx.cpp`. In particular,
Niva/Granta's 0x65C temperature-type field and 0x5DA fields are not assigned
the Largus intake/idle-target/RPM-limit behavior. Status, torque, warning lamps,
engine state and other unassigned fields still need application-specific
validation. Shared message layouts do not imply identical vehicle calibrations
or complete vehicle compatibility. Neither profile implements immobilizer
traffic or ignition sleep management.

## Validation

Build host tests with `bash bin/make_unit_tests.sh -j12`. From
`ext/rusefi/unit_tests`, run `build/rusefi_test --gtest_filter='M749Can*.*'`.
Tests cover wire payloads, cadence across the scheduler wrap, burst size,
source selection, profile/global gates, sensor failures, bounds, RX
freshness, independent counter wrap and transitions between both profiles. Build the production firmware with `bash compile_firmware.sh -j12`.

On the bench, verify RPM/speed/temperatures, stop the received speed stream,
switch among Disabled, Largus and Niva / Granta, check alternate speed-source
precedence, and verify diagnostic requests throughout. No live hardware
validation is implied by the host tests.
