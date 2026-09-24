# Lada vehicle CAN profile

The board-specific **M74.9 CANbus** panel contains the **Lada CANbus profile**
checkbox. It defaults to enabled in new ECU configurations and when the field
is missing from an imported tune. The setting is saved with the tune; it is
not forced back on by board overrides. Existing ECU tune values are retained;
check the checkbox after upgrading from firmware without this setting.
It gates vehicle-specific receive and
transmit on CAN1 (500 kbit/s, G0 RX, G1 TX). Diagnostics remain available when
it is off. Changes apply live; a previously queued transmit frame may finish.

Standard data frame 0x29A provides `SensorType::VehicleSpeed` as the fallback
when the profile is enabled. An explicitly enabled generic CAN VSS or assigned
frequency VSS pin takes precedence. Live configuration apply releases the
fallback's sensor slot and invalidates its value/counter history, so re-enabling
the profile needs a fresh frame. Changing the underlying generic CAN/pulse
source still requires restart for that source's full initialization.

## Receive

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

## Transmit

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

## Validation

Build host tests with `bash bin/make_unit_tests.sh -j12`. From
`ext/rusefi/unit_tests`, run `build/rusefi_test --gtest_filter='M749Can*.*'`.
Tests cover wire payloads, cadence across the scheduler wrap, burst size,
source selection, profile/global gates, sensor failures, bounds and RX
freshness. Build the production firmware with `bash compile_firmware.sh -j12`.

On the bench, verify RPM/speed/temperatures, stop the received speed stream,
toggle the checkbox in both directions, check alternate speed-source
precedence, and verify diagnostic requests throughout. No live hardware
validation is implied by the host tests.
