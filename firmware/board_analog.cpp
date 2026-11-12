#include "pch.h"
#include "board_analog.h"
#include "board_overrides.h"

static bool isBufferedThermistorInput(adc_channel_e channel) {
	// CLT: PF5 / ADC3 IN15; IAT: PF6 / ADC3 IN4.
	return channel == EFI_ADC_39 || channel == EFI_ADC_32;
}

float m74_9_getAnalogInputDividerCoefficient(adc_channel_e channel) {
	// Narrowband lambda and buffered thermistors have no DC divider.
	if (channel == EFI_ADC_37 || isBufferedThermistorInput(channel)) {
		return 1.0f;
	}
	switch (channel) {
		case EFI_ADC_1:
		case EFI_ADC_10:
		case EFI_ADC_11:
		case EFI_ADC_12:
		case EFI_ADC_13:
			return 1.555f;

		case EFI_ADC_6:
		case EFI_ADC_7:
			// F2 (Ignition) and H1+H2 (VBatt after main relay) inputs have 100K + 15K dividers
			// TODO: confirm exact values
			return (100.0 + 15.0) / 15.0;

		default:
			return engineConfiguration->analogInputDividerCoefficient;
	}
}

void setupM749AnalogInputs() {
	custom_board_getAnalogInputDividerCoefficient = m74_9_getAnalogInputDividerCoefficient;
	custom_board_getThermistorSupplyVoltage = [](adc_channel_e channel) {
		return isBufferedThermistorInput(channel) ? 3.3f : 5.0f;
	};
}

void setM749ThermistorDefaults() {
	engineConfiguration->clt.config.bias_resistor = 2150;
	engineConfiguration->iat.config.bias_resistor = 2150;
}
