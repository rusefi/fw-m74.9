#pragma once

// PF5 / ADC3 IN15 (CLT), PF6 / ADC3 IN4 (IAT). Keep the regular knock
// input PA3 out of this scan; knock uses a separate conversion group.
#define ADC3_SLOW_CHANNELS { EFI_ADC_39, EFI_ADC_32 }
