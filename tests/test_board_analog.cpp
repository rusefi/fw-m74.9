#include "pch.h"
#include "adc_inputs.h"
#include "board_analog.h"
#include "board_overrides.h"
#include "functional_sensor.h"
#include "init.h"
#include "thermistor_func.h"

class M749Analog : public ::testing::Test {
	std::optional<setup_custom_get_adc_float_type> savedSupply = custom_board_getThermistorSupplyVoltage;
	std::optional<setup_custom_get_adc_float_type> savedDivider = custom_board_getAnalogInputDividerCoefficient;

	void SetUp() override {
		setupM749AnalogInputs();
	}

	void TearDown() override {
		custom_board_getThermistorSupplyVoltage = savedSupply;
		custom_board_getAnalogInputDividerCoefficient = savedDivider;
	}
};

TEST_F(M749Analog, TemperatureChannels) {
	EngineTestHelper eth(engine_type_e::TEST_ENGINE);
	engineConfiguration->analogInputDividerCoefficient = 2.0f;
	engineConfiguration->clt.adcChannel = EFI_ADC_39;
	engineConfiguration->iat.adcChannel = EFI_ADC_32;
	// Test fixture curve, independent of the board's sensor calibration choice.
	engineConfiguration->clt.config = {0, 30, 100, 32500, 7550, 700, 1500};
	engineConfiguration->iat.config = engineConfiguration->clt.config;
	setM749ThermistorDefaults();
	initThermistors();

	for (auto type : {SensorType::Clt, SensorType::Iat}) {
		const auto channel = type == SensorType::Clt ? EFI_ADC_39 : EFI_ADC_32;
		EXPECT_FLOAT_EQ(1.0f, getAnalogInputDividerCoefficient(channel));
		auto sensor = static_cast<const FunctionalSensor*>(Sensor::getSensorOfType(type));
		ASSERT_NE(nullptr, sensor);
		auto chain = static_cast<thermistor_t*>(sensor->getFunction());
		for (auto point : {std::pair{32500.0f, 0.0f}, {7550.0f, 30.0f}, {700.0f, 100.0f}}) {
			const float voltage = 3.3f * point.first / (2150.0f + point.first);
			auto result = chain->convert(voltage * getAnalogInputDividerCoefficient(channel));
			ASSERT_TRUE(result.Valid);
			EXPECT_NEAR(point.second, result.Value, 0.01f);
			EXPECT_NEAR(point.first, chain->get<resist>().getLastResistance(), 0.1f);
		}
		EXPECT_EQ(UnexpectedCode::Low, chain->convert(0.0f).Code);
		EXPECT_EQ(UnexpectedCode::High, chain->convert(3.3f).Code);
		EXPECT_FLOAT_EQ(0.0f, chain->get<resist>().getLastResistance());
	}
}

TEST_F(M749Analog, OtherInputsKeepConfiguredScaling) {
	EngineTestHelper eth(engine_type_e::TEST_ENGINE);
	for (float divider : {2.0f, 1.75f}) {
		engineConfiguration->analogInputDividerCoefficient = divider;
		for (auto channel : {EFI_ADC_0, EFI_ADC_10, EFI_ADC_11, EFI_ADC_12, EFI_ADC_13}) {
			EXPECT_FLOAT_EQ(divider, getAnalogInputDividerCoefficient(channel));
			EXPECT_FLOAT_EQ(5.0f, custom_board_getThermistorSupplyVoltage.value()(channel));
		}
		EXPECT_FLOAT_EQ(1.0f, getAnalogInputDividerCoefficient(EFI_ADC_39));
		EXPECT_FLOAT_EQ(1.0f, getAnalogInputDividerCoefficient(EFI_ADC_32));
	}
}

TEST_F(M749Analog, DefaultsPreserveAssignmentAndCurve) {
	EngineTestHelper eth(engine_type_e::TEST_ENGINE);
	engineConfiguration->clt.adcChannel = EFI_ADC_NONE;
	engineConfiguration->iat.adcChannel = EFI_ADC_NONE;
	const auto clt = engineConfiguration->clt.config;
	const auto iat = engineConfiguration->iat.config;
	setM749ThermistorDefaults();
	EXPECT_EQ(EFI_ADC_NONE, engineConfiguration->clt.adcChannel);
	EXPECT_EQ(EFI_ADC_NONE, engineConfiguration->iat.adcChannel);
	EXPECT_FLOAT_EQ(2150.0f, engineConfiguration->clt.config.bias_resistor);
	EXPECT_FLOAT_EQ(2150.0f, engineConfiguration->iat.config.bias_resistor);
	EXPECT_FLOAT_EQ(clt.resistance_2, engineConfiguration->clt.config.resistance_2);
	EXPECT_FLOAT_EQ(iat.resistance_2, engineConfiguration->iat.config.resistance_2);
	EXPECT_FLOAT_EQ(clt.tempC_2, engineConfiguration->clt.config.tempC_2);
	EXPECT_FLOAT_EQ(iat.tempC_2, engineConfiguration->iat.config.tempC_2);
}
