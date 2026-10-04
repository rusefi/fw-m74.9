#include "pch.h"
#include "../firmware/vehicle_can.h"

namespace {
CANRxFrame speedFrame(uint16_t raw, uint8_t counter) {
    CANRxFrame frame = {};
    CAN_SID(frame) = 0x29A;
    frame.DLC = 8;
    frame.data8[4] = raw >> 8;
    frame.data8[5] = raw;
    frame.data8[6] = counter;
    uint8_t sum = 0;
    for (unsigned i = 0; i < 7; i++) {
        sum += frame.data8[i];
    }
    frame.data8[7] = static_cast<uint8_t>(~sum);
    return frame;
}

class M749CanRx : public ::testing::Test {
protected:
    EngineTestHelper eth{engine_type_e::TEST_ENGINE};
    M749VehicleSpeedSensor speed;

    void SetUp() override {
        Sensor::inhibitTimeouts(false);
        config->ladaCanbusProfile = LadaCanbusProfile::Largus;
        engineConfiguration->enableCanVss = false;
        engineConfiguration->vehicleSpeedSensorInputPin = Gpio::Unassigned;
    }

    void receive(const CANRxFrame& frame, size_t bus = 0) {
        speed.processFrame(bus, frame, getTimeNowNt());
    }

    void expectSpeed(float expected) {
        const auto value = speed.get();
        ASSERT_TRUE(value);
        EXPECT_FLOAT_EQ(value.Value, expected);
    }
};
}

TEST_F(M749CanRx, KnownWireVectorAndInitialZeroCounter) {
    EXPECT_FALSE(speed.get());
    // 50.00 km/h, first counter zero; 0x13 + 0x88 + 0x64 = 0xFF.
    CANRxFrame frame = {};
    frame.SID = 0x29A;
    frame.DLC = 8;
    frame.data8[4] = 0x13;
    frame.data8[5] = 0x88;
    frame.data8[7] = 0x64;
    receive(frame);
    expectSpeed(50);
    receive(speedFrame(12345, 1));
    expectSpeed(123.45f);
    receive(speedFrame(0, 2));
    expectSpeed(0);
}

TEST_F(M749CanRx, ChecksWholePayloadIncludingUpperCounterBits) {
    // Sum overflows 8 bits: FF + FE + FD + FC + 13 + 88 + A3 = 534.
    auto frame = speedFrame(5000, 0xA3);
    frame.data8[0] = 0xFF;
    frame.data8[1] = 0xFE;
    frame.data8[2] = 0xFD;
    frame.data8[3] = 0xFC;
    frame.data8[7] = 0xCB;
    receive(frame);
    expectSpeed(50);
    for (unsigned i = 0; i < 8; i++) {
        M749VehicleSpeedSensor receiver;
        auto corrupt = frame;
        corrupt.data8[i] ^= 0x10;
        receiver.processFrame(0, corrupt, getTimeNowNt());
        EXPECT_FALSE(receiver.get()) << "byte " << i;
    }
    // The checksum is not the complement of just the alive byte.
    frame = speedFrame(5000, 4);
    frame.data8[7] = 0xFB;
    receive(frame);
    expectSpeed(50);
}

TEST_F(M749CanRx, FiltersBusIdFormatRemoteAndEveryWrongLength) {
    auto frame = speedFrame(1000, 0);
    receive(frame, 1);
    EXPECT_FALSE(speed.get());
    frame.SID = 0x29B;
    receive(frame);
    EXPECT_FALSE(speed.get());
    frame = speedFrame(1000, 0);
    frame.IDE = CAN_IDE_EXT;
    frame.EID = 0x29A;
    receive(frame);
    EXPECT_FALSE(speed.get());
    frame = speedFrame(1000, 0);
    frame.RTR = CAN_RTR_REMOTE;
    receive(frame);
    EXPECT_FALSE(speed.get());
    for (unsigned length = 0; length <= 15; length++) {
        if (length == 8) {
            continue;
        }
        frame = speedFrame(1000, 0);
        frame.DLC = length;
        receive(frame);
        EXPECT_FALSE(speed.get()) << "DLC " << length;
    }
    receive(speedFrame(1000, 0));
    expectSpeed(10);
}

TEST_F(M749CanRx, CounterChangeSkipsBackwardAndWrap) {
    receive(speedFrame(1000, 1));
    receive(speedFrame(2000, 3));
    expectSpeed(20);
    receive(speedFrame(3000, 2));
    expectSpeed(30);
    receive(speedFrame(4000, 15));
    expectSpeed(40);
    receive(speedFrame(5000, 0));
    expectSpeed(50);
    // Changing the upper nibble does not advance the alive counter.
    receive(speedFrame(6000, 0xA0));
    expectSpeed(50);
}

TEST_F(M749CanRx, DuplicateStreamExpiresAndChangedCounterRecovers) {
    receive(speedFrame(1000, 1));
    for (unsigned i = 0; i < 5; i++) {
        advanceTimeUs(20000);
        receive(speedFrame(2000, 1));
        expectSpeed(10);
    }
    advanceTimeUs(1);
    EXPECT_FALSE(speed.get());
    EXPECT_EQ(speed.get().Code, UnexpectedCode::Timeout);
    receive(speedFrame(2000, 1));
    EXPECT_FALSE(speed.get());
    advanceTimeUs(200000);
    receive(speedFrame(2000, 1));
    EXPECT_FALSE(speed.get());
    receive(speedFrame(2000, 2));
    expectSpeed(20);
}

TEST_F(M749CanRx, BadChecksumDoesNotRefreshOrConsumeCounter) {
    receive(speedFrame(1000, 1));
    advanceTimeUs(90000);
    auto corrupt = speedFrame(2000, 2);
    corrupt.data8[7] ^= 1;
    receive(corrupt);
    expectSpeed(10);
    advanceTimeUs(10001);
    EXPECT_FALSE(speed.get());
    receive(speedFrame(2000, 2));
    expectSpeed(20);
}

TEST_F(M749CanRx, MalformedFramesDoNotRefreshFreshness) {
    receive(speedFrame(1000, 0));
    advanceTimeUs(90000);
    auto shortFrame = speedFrame(2000, 1);
    shortFrame.DLC = 4;
    receive(shortFrame);
    receive(speedFrame(2000, 1), 1);
    expectSpeed(10);
    advanceTimeUs(10001);
    EXPECT_FALSE(speed.get());
    receive(speedFrame(2000, 1));
    expectSpeed(20);
}

TEST_F(M749CanRx, InvalidSentinelAndClamp) {
    receive(speedFrame(49999, 0));
    expectSpeed(499.99f);
    receive(speedFrame(50000, 1));
    expectSpeed(500);
    receive(speedFrame(65534, 2));
    expectSpeed(500);
    receive(speedFrame(65535, 3));
    EXPECT_FALSE(speed.get());
    receive(speedFrame(5000, 3));
    EXPECT_FALSE(speed.get());
    receive(speedFrame(5000, 4));
    expectSpeed(50);
}

TEST_F(M749CanRx, PublishesThroughSensorRegistry) {
    ASSERT_TRUE(speed.Register());
    receive(speedFrame(5000, 0));
    EXPECT_FLOAT_EQ(Sensor::getOrZero(SensorType::VehicleSpeed), 50);
    advanceTimeUs(100001);
    EXPECT_FALSE(Sensor::get(SensorType::VehicleSpeed));
    receive(speedFrame(7000, 1));
    EXPECT_FLOAT_EQ(Sensor::getOrZero(SensorType::VehicleSpeed), 70);
    receive(speedFrame(0xFFFF, 2));
    EXPECT_FALSE(Sensor::get(SensorType::VehicleSpeed));
    speed.unregister();
}

TEST(M749CanRxConfiguration, ExplicitSourcesTakePrecedence) {
    EngineTestHelper eth(engine_type_e::TEST_ENGINE);
    config->ladaCanbusProfile = LadaCanbusProfile::Largus;
    engineConfiguration->enableCanVss = false;
    engineConfiguration->vehicleSpeedSensorInputPin = Gpio::Unassigned;
    EXPECT_TRUE(useM749VehicleSpeed());
    engineConfiguration->enableCanVss = true;
    EXPECT_FALSE(useM749VehicleSpeed());
    engineConfiguration->enableCanVss = false;
    engineConfiguration->vehicleSpeedSensorInputPin = Gpio::A0;
    EXPECT_FALSE(useM749VehicleSpeed());
    engineConfiguration->enableCanVss = true;
    EXPECT_FALSE(useM749VehicleSpeed());
    // Selecting the fallback does not rewrite the user's configuration.
    EXPECT_TRUE(engineConfiguration->enableCanVss);
    EXPECT_EQ(engineConfiguration->vehicleSpeedSensorInputPin, Gpio::A0);
}

TEST_F(M749CanRx, ProfileDisableDropsRxAndReenableStartsFresh) {
    receive(speedFrame(5000, 0));
    expectSpeed(50);
    config->ladaCanbusProfile = LadaCanbusProfile::Disabled;
    speed.reset();
    EXPECT_FALSE(useM749VehicleSpeed());
    receive(speedFrame(8000, 1));
    EXPECT_FALSE(speed.get());
    config->ladaCanbusProfile = LadaCanbusProfile::Largus;
    receive(speedFrame(7000, 0));
    expectSpeed(70);
}

TEST_F(M749CanRx, ExplicitSourceDropsVehicleRx) {
    engineConfiguration->enableCanVss = true;
    receive(speedFrame(5000, 0));
    EXPECT_FALSE(speed.get());
    engineConfiguration->enableCanVss = false;
    engineConfiguration->vehicleSpeedSensorInputPin = Gpio::A0;
    receive(speedFrame(5000, 0));
    EXPECT_FALSE(speed.get());
}

namespace {
CANRxFrame nivaSpeedFrame(uint16_t raw) {
    CANRxFrame frame = {};
    CAN_SID(frame) = 0x28C;
    frame.DLC = 2;
    frame.data8[0] = raw >> 8;
    frame.data8[1] = raw;
    return frame;
}
}

TEST_F(M749CanRx, NivaGrantaSpeedFreshnessSentinelAndClamp) {
    config->ladaCanbusProfile = LadaCanbusProfile::NivaGranta;
    EXPECT_TRUE(useM749VehicleSpeed());
    receive(nivaSpeedFrame(5000));
    expectSpeed(50);
    // Equal payloads refresh: this profile has no alive counter.
    advanceTimeUs(90000);
    receive(nivaSpeedFrame(5000));
    advanceTimeUs(90000);
    expectSpeed(50);
    advanceTimeUs(10001);
    EXPECT_FALSE(speed.get());
    receive(nivaSpeedFrame(12345));
    expectSpeed(123.45f);
    receive(nivaSpeedFrame(65534));
    expectSpeed(500);
    receive(nivaSpeedFrame(65535));
    EXPECT_FALSE(speed.get());
    receive(nivaSpeedFrame(0));
    expectSpeed(0);
}

TEST_F(M749CanRx, NivaGrantaFiltersAndSourcePrecedence) {
    config->ladaCanbusProfile = LadaCanbusProfile::NivaGranta;
    for (unsigned length = 0; length <= 15; length++) {
        if (length == 2) {
            continue;
        }
        auto frame = nivaSpeedFrame(5000);
        frame.DLC = length;
        receive(frame);
        EXPECT_FALSE(speed.get()) << "DLC " << length;
    }
    receive(speedFrame(5000, 0));
    receive(nivaSpeedFrame(5000), 1);
    auto frame = nivaSpeedFrame(5000);
    frame.SID++;
    receive(frame);
    frame = nivaSpeedFrame(5000);
    frame.IDE = CAN_IDE_EXT;
    frame.EID = 0x28C;
    receive(frame);
    frame = nivaSpeedFrame(5000);
    frame.RTR = CAN_RTR_REMOTE;
    receive(frame);
    EXPECT_FALSE(speed.get());
    engineConfiguration->enableCanVss = true;
    EXPECT_FALSE(useM749VehicleSpeed());
    receive(nivaSpeedFrame(5000));
    EXPECT_FALSE(speed.get());
    engineConfiguration->enableCanVss = false;
    engineConfiguration->vehicleSpeedSensorInputPin = Gpio::A0;
    EXPECT_FALSE(useM749VehicleSpeed());
    receive(nivaSpeedFrame(5000));
    EXPECT_FALSE(speed.get());
}

TEST_F(M749CanRx, ProfileSwitchResetsStateAndRejectsOtherProfile) {
    receive(speedFrame(5000, 0));
    expectSpeed(50);
    // Configuration apply calls reset via the board stop hook.
    speed.reset();
    config->ladaCanbusProfile = LadaCanbusProfile::NivaGranta;
    receive(speedFrame(8000, 1));
    EXPECT_FALSE(speed.get());
    receive(nivaSpeedFrame(7000));
    expectSpeed(70);
    speed.reset();
    config->ladaCanbusProfile = LadaCanbusProfile::Largus;
    receive(nivaSpeedFrame(8000));
    EXPECT_FALSE(speed.get());
    receive(speedFrame(6000, 0));
    expectSpeed(60);
}

TEST_F(M749CanRx, InvalidProfilesNeverEnableEitherReceiver) {
    for (unsigned raw = 0; raw <= 255; raw++) {
        if (raw == 1 || raw == 2) {
            continue;
        }
        config->ladaCanbusProfile = static_cast<LadaCanbusProfile>(raw);
        EXPECT_FALSE(useM749VehicleSpeed()) << raw;
        receive(speedFrame(5000, 0));
        receive(nivaSpeedFrame(5000));
        EXPECT_FALSE(speed.get()) << raw;
    }
}
