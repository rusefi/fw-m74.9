#pragma once

#include <cassert>
#include <cstddef>
#include <cstdint>

#define TRUE 1
#define FALSE 0
#define HAL_USE_ADC TRUE
#define EFI_ADC3_SLOW TRUE
#define EFI_USE_FAST_ADC TRUE
#define STM32F4
#define NO_CACHE
#define MS2NT(value) (int64_t(value) * 1000)

// Use the production interrupt configuration, including the shared error IRQ.
#include "hw_layer/ports/at32/interrupt_priority.h"
#include "hw_layer/ports/at32/at32f4/cfg/mcuconf.h"

#ifdef _MSC_VER
#define __attribute__(x)
#endif
#include "controllers/algo/rusefi_hw_adc_enums.h"

using adcsample_t = uint16_t;
using adcerror_t = uint32_t;
constexpr adcerror_t ADC_ERR_OVERFLOW = 1;
constexpr uint32_t ADC_SAMPLE_144 = 6;
constexpr uint32_t ADC_CR2_SWSTART = 1U << 30;
enum adcstate_t { ADC_STOP, ADC_READY, ADC_ACTIVE, ADC_ERROR };
struct ADC_TypeDef {};
struct ADCDriver;
struct ADCConversionGroup {
	bool circular;
	size_t num_channels;
	void (*end_cb)(ADCDriver*);
	void (*error_cb)(ADCDriver*, adcerror_t);
	uint32_t cr1, cr2, smpr1, smpr2, htr, ltr, sqr1, sqr2, sqr3;
};
struct ADCDriver {
	adcstate_t state = ADC_STOP;
	const ADCConversionGroup* grpp = nullptr;
	adcsample_t* samples = nullptr;
	size_t depth = 0;
};
extern ADCDriver ADCD1, ADCD2, ADCD3;
extern ADC_TypeDef adc3Registers;
#define ADC3 (&adc3Registers)

namespace efi {
template<typename T, size_t N> constexpr size_t size(const T (&)[N]) { return N; }
}
struct TestEngine {
	struct { unsigned slowAdcErrorCount = 0; unsigned slowAdcOverrunCount = 0; } outputChannels;
};
extern TestEngine* engine;
int64_t getTimeNowNt();
void criticalError(const char*, ...);
void adcStart(ADCDriver*, const void*);
void adcStartConversionI(ADCDriver*, const ADCConversionGroup*, adcsample_t*, size_t);
void adcStopConversionI(ADCDriver*);
