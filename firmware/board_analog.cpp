#include "pch.h"
#include "board_analog.h"
#include "board_overrides.h"

static bool isBufferedThermistorInput(adc_channel_e channel) {
	// CLT: PF5 / ADC3 IN15; IAT: PF6 / ADC3 IN4.
	return channel == EFI_ADC_39 || channel == EFI_ADC_32;
}

void setupM749AnalogInputs() {
	custom_board_getAnalogInputDividerCoefficient = [](adc_channel_e channel) {
		// The series protection/filter resistors do not form a DC divider.
		return isBufferedThermistorInput(channel) ? 1.0f : engineConfiguration->analogInputDividerCoefficient;
	};
	custom_board_getThermistorSupplyVoltage = [](adc_channel_e channel) {
		return isBufferedThermistorInput(channel) ? 3.3f : 5.0f;
	};
}

void setM749ThermistorDefaults() {
	engineConfiguration->clt.config.bias_resistor = 2150;
	engineConfiguration->iat.config.bias_resistor = 2150;
}
