#pragma once

float m74_9_getAnalogInputDividerCoefficient(adc_channel_e channel);
// Install physical-channel scaling before sensors are initialized.
void setupM749AnalogInputs();
// Fresh-tune bias values only; does not assign ADC inputs or sensor curves.
void setM749ThermistorDefaults();
