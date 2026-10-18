#include "pch.h"
#include "../default_tune.h"
#include "board_overrides.h"
#include "gppwm_channel.h"
#include "mocks.h"

class M749IntakeRunner : public testing::Test {
    std::optional<setup_custom_board_overrides_type> savedDefaults = custom_board_BeforeTuneDefaults;

    void SetUp() override {
        custom_board_BeforeTuneDefaults = setM749IntakeRunnerDefaults;
    }

    void TearDown() override {
        custom_board_BeforeTuneDefaults = savedDefaults;
    }
};

TEST_F(M749IntakeRunner, TuneDefaults) {
    EngineTestHelper eth(engine_type_e::TEST_ENGINE);
    const auto& cfg = engineConfiguration->gppwm[0];
    EXPECT_EQ(Gpio::E6, cfg.pin);
    EXPECT_STREQ("Intake runner", engineConfiguration->gpPwmNote[0]);
    EXPECT_EQ(0, cfg.pwmFrequency);
    EXPECT_EQ(GPPWM_Rpm, cfg.rpmAxis);
    EXPECT_EQ(GPPWM_Tps, cfg.loadAxis);
    EXPECT_EQ(0, cfg.dutyIfError);
    EXPECT_GT(cfg.onAboveDuty, cfg.offBelowDuty);
    for (const auto& row : cfg.table) {
        for (const auto& duty : row) {
            EXPECT_FLOAT_EQ(0, duty);
        }
    }
    // Other channels remain available for user assignments.
    EXPECT_EQ(Gpio::Unassigned, engineConfiguration->gppwm[1].pin);
}

TEST_F(M749IntakeRunner, TableControlsValveAndSensorFailureTurnsItOff) {
    EngineTestHelper eth(engine_type_e::TEST_ENGINE);
    auto& cfg = engineConfiguration->gppwm[0];
    Map3D<GPPWM_RPM_COUNT, GPPWM_LOAD_COUNT, uint8_t, int16_t, int16_t> table{"intake"};
    table.initTable(cfg.table, cfg.rpmBins, cfg.loadBins);
    testing::StrictMock<MockOutputPin> pin;
    GppwmChannel channel;
    channel.init(false, nullptr, &pin, &table, &cfg);

    for (float rpm : {0, 800, 3500, 7000, 9000}) {
        Sensor::setMockValue(SensorType::Rpm, rpm);
        for (float tps : {0, 50, 100}) {
            Sensor::setMockValue(SensorType::Tps1, tps);
            EXPECT_CALL(pin, setValue(0, false));
            EXPECT_FLOAT_EQ(0, channel.update(0).Result);
        }
    }

    // A tune edit selects the short path through the standard controller.
    for (auto& row : cfg.table) {
        for (auto& duty : row) {
            duty = 100;
        }
    }
    for (auto missing : {SensorType::Rpm, SensorType::Tps1}) {
        Sensor::setMockValue(SensorType::Rpm, 3500);
        Sensor::setMockValue(SensorType::Tps1, 50);
        EXPECT_CALL(pin, setValue(1, false));
        EXPECT_FLOAT_EQ(100, channel.update(0).Result);
        Sensor::setInvalidMockValue(missing);
        // A missing axis turns it off, despite the table requesting short.
        EXPECT_CALL(pin, setValue(0, false));
        EXPECT_FLOAT_EQ(0, channel.update(0).Result);
    }
}
