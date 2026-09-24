#include "pch.h"
#include "../firmware/vehicle_can.h"
#include "can_msg_tx.h"
#include "idle_thread.h"
#include <map>
#include <vector>
#include <limits>

namespace {
class M749CanTx : public ::testing::Test {
protected:
    EngineTestHelper eth{engine_type_e::TEST_ENGINE};
    M749VehicleCanTransmitter transmitter;

    void SetUp() override {
        config->ladaCanbusProfile = true;
        engineConfiguration->canWriteEnabled = true;
        engineConfiguration->cranking.rpm = 400;
        engineConfiguration->rpmHardLimit = 5920;
        engine->module<IdleController>().unmock().idleTarget = 1096;
        Sensor::setMockValue(SensorType::Rpm, 0);
        Sensor::setMockValue(SensorType::VehicleSpeed, 0);
        Sensor::setMockValue(SensorType::Clt, 25);
        Sensor::setMockValue(SensorType::Iat, 21);
        txCanBuffer.clear();
    }

    std::map<uint32_t, CANTxFrame> onePeriod() {
        std::map<uint32_t, CANTxFrame> result;
        for (unsigned tick = 0; tick < 200; tick++) {
            transmitter.update();
            while (txCanBuffer.getCount()) {
                const auto frame = txCanBuffer.get();
                result[CAN_ID(frame)] = frame;
            }
        }
        return result;
    }
};
}

TEST_F(M749CanTx, ExactIdsLengthsPeriodsAndBoundedBurst) {
    const std::map<uint32_t, std::pair<unsigned, unsigned>> expected = {
        {0x186, {7, 2}}, {0x189, {8, 2}}, {0x18A, {6, 2}}, {0x1F6, {8, 2}},
        {0x217, {8, 4}}, {0x2A9, {1, 4}}, {0x2C6, {6, 4}},
        {0x36E, {4, 20}}, {0x3A5, {3, 20}}, {0x41A, {8, 20}},
        {0x41D, {4, 20}}, {0x511, {7, 20}}, {0x522, {8, 20}},
        {0x5DA, {8, 20}}, {0x648, {8, 20}}, {0x65C, {3, 20}},
        {0x66A, {8, 20}}, {0x6FD, {4, 20}}, {0x5E2, {2, 200}},
    };
    std::map<uint32_t, unsigned> counts, lastTick;
    for (unsigned tick = 0; tick < 400; tick++) {
        transmitter.update();
        EXPECT_LE(txCanBuffer.getCount(), 4U);
        while (txCanBuffer.getCount()) {
            const auto frame = txCanBuffer.get();
            const auto id = CAN_ID(frame);
            ASSERT_TRUE(expected.count(id)) << id;
            EXPECT_FALSE(CAN_ISX(frame));
            EXPECT_FALSE(CAN_ISRTR(frame));
            EXPECT_EQ(frame.DLC, expected.at(id).first);
            if (counts[id]) {
                EXPECT_EQ(tick - lastTick[id], expected.at(id).second) << id;
            }
            lastTick[id] = tick;
            counts[id]++;
        }
    }
    ASSERT_EQ(counts.size(), 19U);
    for (const auto& item : expected) {
        EXPECT_EQ(counts[item.first], 400 / item.second.second) << item.first;
    }
}

TEST_F(M749CanTx, StationaryGoldenPayloadsHaveNoInventedCounters) {
    const std::map<uint32_t, std::vector<uint8_t>> expected = {
        {0x186, {0x00,0x00,0x32,0x03,0x20,0x00,0x20}},
        {0x189, {0x32,0x03,0x20,0x32,0x00,0xB9,0x00,0x00}},
        {0x18A, {0x32,0x00,0x00,0x06,0xFE,0x00}},
        {0x1F6, {0x00,0x00,0x02,0x2E,0x00,0x00,0x00,0x00}},
        {0x217, {0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x88}},
        {0x2A9, {0xF4}}, {0x2C6, {0x00,0x00,0x00,0x40,0x00,0x00}},
        {0x36E, {0x09,0x99,0x09,0x99}}, {0x3A5, {0x00,0x00,0x00}},
        {0x41A, {0x00,0x03,0x00,0x00,0x00,0x00,0x00,0x00}},
        {0x41D, {0x48,0xFE,0xFF,0x00}}, {0x511, {0,0,0,0,0,0,0}},
        {0x522, {0,0,0,0,0,0,0,0}},
        {0x5DA, {0x41,0x89,0x00,0x00,0xB9,0xFF,0xFF,0x00}},
        {0x648, {0xC3,0xFF,0xF0,0x00,0x00,0x7F,0xFE,0x18}},
        {0x65C, {0x7A,0xFF,0x00}},
        {0x66A, {0x08,0xFF,0x00,0x01,0x01,0xC0,0x00,0x00}},
        {0x6FD, {0x00,0x00,0x99,0x20}}, {0x5E2, {0,0}},
    };
    for (unsigned repeat = 0; repeat < 2; repeat++) {
        const auto actual = onePeriod();
        ASSERT_EQ(actual.size(), expected.size());
        for (const auto& item : expected) {
            const auto& frame = actual.at(item.first);
            for (unsigned byte = 0; byte < item.second.size(); byte++) {
                EXPECT_EQ(frame.data8[byte], item.second[byte]) << item.first << " byte " << byte;
            }
        }
    }
}

TEST_F(M749CanTx, LiveValuesAndSharedNibblePacking) {
    engine->rpmCalculator.setRpmValue(1256);
    Sensor::setMockValue(SensorType::Rpm, 1256);
    Sensor::setMockValue(SensorType::VehicleSpeed, 50);
    Sensor::setMockValue(SensorType::Clt, 90);
    Sensor::setMockValue(SensorType::Iat, 30);
    engine->module<IdleController>().unmock().idleTarget = 800;
    engineConfiguration->rpmHardLimit = 6400;
    const auto frames = onePeriod();
    EXPECT_EQ(frames.at(0x186).data8[0], 0x27);
    EXPECT_EQ(frames.at(0x186).data8[1], 0x40);
    EXPECT_EQ(frames.at(0x186).data8[2], 0x32);
    EXPECT_EQ(frames.at(0x1F6).data8[2], 0x82);
    EXPECT_EQ(frames.at(0x217).data8[2], 0x70);
    EXPECT_EQ(frames.at(0x217).data8[3], 0x21);
    EXPECT_EQ(frames.at(0x217).data8[4], 0x10);
    EXPECT_EQ(frames.at(0x217).data8[7], 0x88);
    EXPECT_EQ(frames.at(0x5DA).data8[0], 130);
    EXPECT_EQ(frames.at(0x5DA).data8[1], 100);
    EXPECT_EQ(frames.at(0x5DA).data8[4], 200);
    EXPECT_EQ(frames.at(0x65C).data8[0], 140);
}

TEST_F(M749CanTx, CrankingAndStoppedState) {
    engine->rpmCalculator.setRpmValue(200);
    Sensor::setMockValue(SensorType::Rpm, 200);
    EXPECT_EQ(onePeriod().at(0x1F6).data8[2], 0x42);
    engine->rpmCalculator.setRpmValue(0);
    Sensor::setMockValue(SensorType::Rpm, 0);
    const auto frames = onePeriod();
    EXPECT_EQ(frames.at(0x1F6).data8[2], 0x02);
    EXPECT_EQ(frames.at(0x186).data8[0], 0);
    EXPECT_EQ(frames.at(0x186).data8[1], 0);
    EXPECT_EQ(frames.at(0x217).data8[2], 0);
}

TEST_F(M749CanTx, ClampsInsteadOfWrapping) {
    Sensor::setMockValue(SensorType::Rpm, 10000);
    Sensor::setMockValue(SensorType::VehicleSpeed, 500);
    Sensor::setMockValue(SensorType::Clt, 250);
    Sensor::setMockValue(SensorType::Iat, 100);
    engine->module<IdleController>().unmock().idleTarget = 4000;
    engineConfiguration->rpmHardLimit = 10000;
    const auto frames = onePeriod();
    EXPECT_EQ(frames.at(0x186).data8[0], 0xFF);
    EXPECT_EQ(frames.at(0x186).data8[1], 0xFF);
    EXPECT_EQ(frames.at(0x217).data8[3], 0xFF);
    EXPECT_EQ(frames.at(0x217).data8[4], 0xF0);
    EXPECT_EQ(frames.at(0x5DA).data8[0], 0xFF);
    EXPECT_EQ(frames.at(0x5DA).data8[1], 0xFF);
    EXPECT_EQ(frames.at(0x5DA).data8[4], 0xFF);
    EXPECT_EQ(frames.at(0x65C).data8[0], 0xFE);
    Sensor::setMockValue(SensorType::Clt, -100);
    Sensor::setMockValue(SensorType::Iat, -100);
    const auto cold = onePeriod();
    EXPECT_EQ(cold.at(0x5DA).data8[0], 0);
    EXPECT_EQ(cold.at(0x65C).data8[0], 0);
}

TEST_F(M749CanTx, InvalidSensorsAndNonFiniteValues) {
    Sensor::setInvalidMockValue(SensorType::Rpm);
    Sensor::setInvalidMockValue(SensorType::VehicleSpeed);
    Sensor::setInvalidMockValue(SensorType::Clt);
    Sensor::setInvalidMockValue(SensorType::Iat);
    auto frames = onePeriod();
    EXPECT_EQ(frames.size(), 17U);
    EXPECT_FALSE(frames.count(0x5DA));
    EXPECT_FALSE(frames.count(0x65C));
    EXPECT_EQ(frames.at(0x186).data8[0], 0);
    EXPECT_EQ(frames.at(0x217).data8[3], 0);
    Sensor::setMockValue(SensorType::Rpm, std::numeric_limits<float>::infinity());
    Sensor::setMockValue(SensorType::VehicleSpeed, std::numeric_limits<float>::quiet_NaN());
    Sensor::setMockValue(SensorType::Clt, std::numeric_limits<float>::quiet_NaN());
    Sensor::setMockValue(SensorType::Iat, std::numeric_limits<float>::infinity());
    frames = onePeriod();
    EXPECT_EQ(frames.size(), 17U);
    EXPECT_EQ(frames.at(0x186).data8[1], 0);
    EXPECT_EQ(frames.at(0x217).data8[4], 0);
}

TEST_F(M749CanTx, ProfileAndGlobalTransmitGates) {
    config->ladaCanbusProfile = false;
    EXPECT_TRUE(onePeriod().empty());
    config->ladaCanbusProfile = true;
    EXPECT_EQ(onePeriod().size(), 19U);
    engineConfiguration->canWriteEnabled = false;
    EXPECT_TRUE(onePeriod().empty());
    engineConfiguration->canWriteEnabled = true;
    EXPECT_EQ(onePeriod().size(), 19U);
    config->ladaCanbusProfile = false;
    EXPECT_TRUE(onePeriod().empty());
}
