#include "pch.h"
#include "ch.hpp"
#include "adc_onchip.h"

#include <algorithm>
#include <iostream>

ADCDriver ADCD1, ADCD2, ADCD3;
ADC_TypeDef adc3Registers;
TestEngine testEngine;
TestEngine* engine = &testEngine;
unsigned adc3TestLockDepth = 0;
static int64_t now = 0;
static unsigned starts = 0, stops = 0, driverStarts = 0;

int64_t getTimeNowNt() { return now; }
void criticalError(const char*, ...) { assert(false); }

// Model the linear ADC HAL contract: start/stop are I-class, DMA writes into
// the supplied buffer, and the driver becomes READY before the end callback.
void adcStart(ADCDriver* adc, const void*) {
	assert(adc->state == ADC_STOP);
	adc->state = ADC_READY;
	driverStarts++;
}
void adcStartConversionI(ADCDriver* adc, const ADCConversionGroup* group, adcsample_t* buffer, size_t depth) {
	assert(adc3TestLockDepth && adc == &ADCD3 && adc->state == ADC_READY);
	assert(!group->circular && group->num_channels == 2 && depth == 8);
	assert((group->sqr3 & 31) == 15);
	assert(((group->sqr3 >> 5) & 31) == 4);
	assert(((group->smpr1 >> 15) & 7) == ADC_SAMPLE_144);
	assert(((group->smpr2 >> 12) & 7) == ADC_SAMPLE_144);
	adc->state = ADC_ACTIVE;
	adc->grpp = group;
	adc->samples = buffer;
	adc->depth = depth;
	starts++;
}
void adcStopConversionI(ADCDriver* adc) {
	assert(adc3TestLockDepth && adc->state == ADC_ACTIVE);
	adc->state = ADC_READY;
	adc->grpp = nullptr;
	stops++;
}
int getAdcInternalChannel(ADC_TypeDef* adc, adc_channel_e channel) {
	assert(adc == ADC3);
	return channel == EFI_ADC_39 ? 15 : channel == EFI_ADC_32 ? 4 : -1;
}
int adcConversionGroupSetSeqInput(ADCConversionGroup* group, size_t index, size_t input) {
	assert(index < 2);
	group->sqr3 |= uint32_t(input) << (5 * index);
	return 0;
}

static void complete() {
	assert(ADCD3.state == ADC_ACTIVE);
	const auto* group = ADCD3.grpp;
	ADCD3.state = ADC_READY;
	group->end_cb(&ADCD3);
	if (ADCD3.state == ADC_READY && ADCD3.grpp == group) {
		ADCD3.grpp = nullptr;
	}
}

int main() {
	// Startup reads and calls before ADC initialization must remain invalid.
	adc3SlowUpdate();
	assert(starts == 0 && adc3SlowRead(EFI_ADC_39) == -1);
	initAdc3Slow();
	assert(driverStarts == 1);
	initAdc3Slow();
	assert(driverStarts == 1);
	adc3SlowUpdate();
	assert(starts == 1 && adc3SlowRead(EFI_ADC_32) == -1);
	for (size_t row = 0; row < 8; row++) {
		ADCD3.samples[2 * row] = uint16_t(1000 + 2 * row);
		ADCD3.samples[2 * row + 1] = uint16_t(2000 + 2 * row);
	}
	now = 100;
	complete();
	assert(adc3SlowRead(EFI_ADC_39) == 1007);
	assert(adc3SlowRead(EFI_ADC_32) == 2007);
	assert(adc3SlowRead(EFI_ADC_33) == -1);

	// Abort a partial temperature DMA batch at a knock deadline.
	now = 2000;
	adc3SlowUpdate();
	ADCD3.samples[0] = 4095;
	{
		chibios_rt::CriticalSectionLocker lock;
		adc3SlowPreemptI(&ADCD1);
		assert(stops == 0);
		adc3SlowPreemptI(&ADCD3);
		assert(stops == 1 && ADCD3.state == ADC_READY);
	}
	assert(adc3SlowRead(EFI_ADC_39) == 1007);
	// The knock owner keeps ADC3 busy: neither abort nor background start.
	ADCD3.state = ADC_ACTIVE;
	now = 6100;
	adc3SlowUpdate();
	{
		chibios_rt::CriticalSectionLocker lock;
		adc3SlowPreemptI(&ADCD3);
	}
	assert(stops == 1 && starts == 2 && ADCD3.state == ADC_ACTIVE);
	assert(adc3SlowRead(EFI_ADC_39) == -1);
	ADCD3.state = ADC_READY;
	adc3SlowUpdate();
	assert(starts == 3);
	std::fill_n(ADCD3.samples, 16, 1234);
	complete();
	assert(adc3SlowRead(EFI_ADC_39) == 1234);

	// DMA/overrun error invalidates both channels without refreshing timestamps.
	adc3SlowUpdate();
	const auto* group = ADCD3.grpp;
	ADCD3.state = ADC_ERROR;
	group->error_cb(&ADCD3, ADC_ERR_OVERFLOW);
	ADCD3.state = ADC_READY;
	ADCD3.grpp = nullptr;
	assert(engine->outputChannels.slowAdcErrorCount == 1);
	assert(engine->outputChannels.slowAdcOverrunCount == 1);
	assert(adc3SlowRead(EFI_ADC_39) == -1 && adc3SlowRead(EFI_ADC_32) == -1);
	adc3SlowUpdate();
	assert(starts == 5);
	// Missing completion interrupt: stop only our batch, then retry.
	now += 2000;
	adc3SlowUpdate();
	assert(stops == 2 && starts == 6);
	assert(engine->outputChannels.slowAdcErrorCount == 2);
	assert(adc3SlowRead(EFI_ADC_32) == -1);
	std::fill_n(ADCD3.samples, 16, 2345);
	complete();
	assert(adc3SlowRead(EFI_ADC_32) == 2345);
	assert(adc3TestLockDepth == 0);
	std::cout << "ADC3 port: startup, sequence, averaging, knock preemption, expiry, errors and recovery passed\n";
}
