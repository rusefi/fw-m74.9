# CLT/IAT acquisition

| Input | Connector / MCU | Logical channel | ADC3 input |
| --- | --- | --- | --- |
| CLT | AD3 / PF5 | EFI_ADC_39 | IN15 |
| IAT | AC2 / PF6 | EFI_ADC_32 | IN4 |

Both circuits have 2150-ohm pull-ups to 3.3 V and unity buffering. The series
protection/filter resistors are not a DC divider. Supply and voltage scaling
are selected by physical channel; the tune still supplies the sensor curve
and bias value. Existing tunes need the bias checked for 2150 ohms with
pulldown disabled. CAN profile does not choose temperature calibration.

## Sampling contract

`EFI_ADC3_SLOW` enables a linear two-channel DMA group with eight samples per
channel. The normal 500 Hz slow-sensor update requests a batch when ADC3 is
ready. Only a completed batch enters the averaged cache. The scan excludes the
PA3 knock input and uses a separate buffer and conversion group from knock.

A knock start cancels an active temperature batch before checking ADC readiness
and starting its own conversion. Temperature updates never stop a knock batch.
DMA errors invalidate the cache. A temperature batch that has not completed
within 2 ms is cancelled and retried on the next slow-sensor update. A previous
completed batch can survive preemption only until its original 6 ms expiry.
Reading it does not renew its timestamp. Unsupported ADC3 channels return -1.

Invalid raw ADC readings do not refresh sensor timestamps. The existing CLT/IAT
10 ms sensor timeout and 2 Hz voltage filter are unchanged. Sampling therefore
cannot silently keep an old temperature valid indefinitely when ADC3 is busy
or its DMA interrupts stop arriving.

On this board the ADC error IRQ, all three ADC DMA IRQs and the fast-ADC timer
use scheduler priority 3 when sharing is enabled. ADCv2 shares its error IRQ
across the three converters; matching priorities prevent a knock start or an
ADC error from interrupting a HAL completion state transition. Thread access
uses the system lock. Other boards keep the previous priorities unless they
enable this feature and meet its compile-time priority checks.

## Validation and remaining checks

- `python3 tests/test_adc3_port.py` compiles the production ADC3 source against
  a deterministic HAL fixture. It checks the physical sequence, sample-time
  configuration, initialization, averaging, partial-batch cancellation, knock
  ownership, stale data, errors and lost-completion recovery. Set `CXX` to select
  a compiler; GCC/Clang and MSVC command-line styles are supported.
- Shared host tests cover controller membership and cache/preemption rules.
- Build production firmware with `bash compile_firmware.sh -j12`.

Fresh CLT/IAT assignments remain disabled pending sensor-curve qualification.
Generated analog selectors allow deliberate selection for testing. Before engine
use, check known-resistor readings across the operating range, open/short and
recovery behavior, sustained knock windows, crank/spark timing and watchdog
behavior under the changed interrupt priorities. No physical validation is
implied by a host test or successful build.
