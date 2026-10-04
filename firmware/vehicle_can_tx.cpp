#include "pch.h"
#include "vehicle_can.h"
#include "can_msg_tx.h"
#include "idle_thread.h"

namespace {
struct PeriodicFrame {
    uint16_t id;
    uint8_t length;
    uint8_t periodTicks;
    uint8_t phaseTicks;
    uint8_t baseline[8];
};

// Periods and phases are in 5 ms worker ticks. Stagger the slow frames to
// leave queue space for diagnostics and other enabled CAN services.
// Unassigned fields retain fixed profile baselines; they are not live sensors.
constexpr PeriodicFrame largusFrames[] = {
    {0x186, 7,   2,  0, {0x00, 0x00, 0x32, 0x03, 0x20, 0x00, 0x20, 0x00}},
    {0x189, 8,   2,  1, {0x32, 0x03, 0x20, 0x32, 0x00, 0xB9, 0x00, 0x00}},
    {0x18A, 6,   2,  0, {0x32, 0x00, 0x00, 0x06, 0xFE, 0x00, 0x00, 0x00}},
    {0x1F6, 8,   2,  1, {0x00, 0x00, 0x02, 0x2E, 0x00, 0x00, 0x00, 0x00}},
    {0x217, 8,   4,  0, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x88}},
    {0x2A9, 1,   4,  1, {0xF4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x2C6, 6,   4,  2, {0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00}},
    {0x36E, 4,  20,  3, {0x09, 0x99, 0x09, 0x99, 0x00, 0x00, 0x00, 0x00}},
    {0x3A5, 3,  20,  4, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x41A, 8,  20,  5, {0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x41D, 4,  20,  6, {0x48, 0xFE, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x511, 7,  20,  7, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x522, 8,  20,  8, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x5DA, 8,  20,  9, {0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0x00}},
    {0x648, 8,  20, 10, {0xC3, 0xFF, 0xF0, 0x00, 0x00, 0x7F, 0xFE, 0x18}},
    {0x65C, 3,  20, 11, {0x00, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x66A, 8,  20, 12, {0x08, 0xFF, 0x00, 0x01, 0x01, 0xC0, 0x00, 0x00}},
    {0x6FD, 4,  20, 13, {0x00, 0x00, 0x99, 0x20, 0x00, 0x00, 0x00, 0x00}},
    {0x5E2, 2, 200, 19, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
};

// Shared Niva/Granta layout. Unassigned fields use fixed compatibility values;
// they do not describe live torque, warning lamps or other vehicle state.
constexpr PeriodicFrame nivaGrantaFrames[] = {
    {0x1F9, 8,  2,  0, {0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x180, 8,  2,  0, {0x00, 0x00, 0x30, 0x23, 0x86, 0x11, 0x40, 0x00}},
    {0x160, 7,  2,  0, {0x2D, 0xA3, 0x02, 0x00, 0x00, 0xC8, 0x00, 0x00}},
    {0x182, 8,  2,  1, {0x00, 0x00, 0x00, 0x00, 0x16, 0x00, 0x00, 0x00}},
    {0x186, 7,  2,  1, {0x00, 0x00, 0x30, 0x20, 0x00, 0x00, 0x00, 0x00}},
    {0x18A, 6,  2,  1, {0x2D, 0xA8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x189, 8,  2,  1, {0x2D, 0xA3, 0x20, 0x38, 0x60, 0xD9, 0x00, 0x00}},
    {0x35D, 8, 20,  0, {0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00}},
    {0x551, 8, 20,  2, {0x00, 0x00, 0x01, 0x80, 0x01, 0x00, 0x00, 0x00}},
    {0x6E2, 6, 20,  4, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x5DA, 8, 20,  6, {0x00, 0x76, 0x00, 0x00, 0xBE, 0x00, 0x00, 0x00}},
    {0x65C, 2, 20,  8, {0xC8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x314, 8, 20, 10, {0x90, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {0x68E, 8, 20, 12, {0x0A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xAA}},
};

float bounded(float value, float low, float high) {
    if (!std::isfinite(value)) {
        return low;
    }
    return value < low ? low : value > high ? high : value;
}

uint16_t indicatedSpeed(float speed) {
    // Keep the fixed-point rounding of the indicated-speed conversion.
    // Zero or unavailable speed stays zero; positive speed gets gain/offset.
    const uint32_t q6 = bounded(speed, 0, 500) * 64;
    const uint32_t indicated = q6 ? ((q6 * 16712) >> 14) + 125 : 0;
    const uint32_t raw = (indicated * 5) / 32;
    return raw > 4095 ? 4095 : raw;
}
}

void M749VehicleCanTransmitter::update() {
    const auto profile = config->ladaCanbusProfile;
    if (profile != m_profile || !engineConfiguration->canWriteEnabled) {
        m_tick = 0;
        m_counter1F9 = m_counter35D = m_counter551 = 0;
        m_profile = profile;
    }
    const PeriodicFrame* frames;
    size_t frameCount;
    switch (profile) {
    case LadaCanbusProfile::Largus:
        frames = largusFrames;
        frameCount = efi::size(largusFrames);
        break;
    case LadaCanbusProfile::NivaGranta:
        frames = nivaGrantaFrames;
        frameCount = efi::size(nivaGrantaFrames);
        break;
    default:
        m_tick = 0;
        return;
    }
    if (!engineConfiguration->canWriteEnabled) {
        m_tick = 0;
        return;
    }
    const bool largus = profile == LadaCanbusProfile::Largus;

    const auto rpm = bounded(Sensor::getOrZero(SensorType::Rpm), 0, 8191.875f);
    const auto clt = Sensor::get(SensorType::Clt);
    const auto iat = Sensor::get(SensorType::Iat);
    const auto state = rpm == 0 ? 0 : engine->rpmCalculator.isRunning() ? 2 : 1;

    for (size_t index = 0; index < frameCount; index++) {
        const auto& frame = frames[index];
        if (m_tick % frame.periodTicks != frame.phaseTicks) {
            continue;
        }
        // No temperature fault encoding is defined by this profile. Stop the
        // affected frame instead of transmitting a fabricated healthy reading.
        if ((frame.id == (largus ? 0x5DA : 0x551) && (!clt || !std::isfinite(clt.Value)))
            || (largus && frame.id == 0x65C && (!iat || !std::isfinite(iat.Value)))) {
            continue;
        }

        CanTxMessage msg(CanCategory::NBC, frame.id, frame.length, 0, false);
        for (unsigned i = 0; i < frame.length; i++) {
            msg[i] = frame.baseline[i];
        }
        switch (frame.id) {
        case 0x180:
        case 0x186: {
            if (frame.id == 0x186 && !largus) {
                break;
            }
            const uint16_t raw = rpm * 8;
            msg[0] = raw >> 8;
            msg[1] = raw;
            break;
        }
        case 0x1F6:
            msg[2] = (msg[2] & 0x3F) | (state << 6);
            break;
        case 0x217: {
            const auto raw = indicatedSpeed(Sensor::getOrZero(SensorType::VehicleSpeed));
            msg[2] = rpm > 0 ? 0x70 : 0;
            msg[3] = raw >> 4;
            msg[4] = (msg[4] & 0x0F) | ((raw & 0x0F) << 4);
            break;
        }
        case 0x5DA:
            if (!largus) {
                break;
            }
            msg[0] = bounded(clt.Value, -40, 215) + 40;
            msg[1] = bounded(engine->module<IdleController>().unmock().idleTarget, 0, 2040) / 8;
            msg[4] = bounded(engineConfiguration->rpmHardLimit, 0, 8160) / 32;
            break;
        case 0x65C:
            if (largus) {
                msg[0] = static_cast<uint8_t>(bounded(iat.Value, -40, 87) + 40) << 1;
            }
            break;
        case 0x1F9:
            msg[0] = (msg[0] & 0x0F) | (m_counter1F9 << 4);
            m_counter1F9 = (m_counter1F9 + 1) & 0x0F;
            break;
        case 0x35D:
            msg[6] = (msg[6] & 0x0F) | (m_counter35D << 4);
            m_counter35D = (m_counter35D + 1) & 0x0F;
            break;
        case 0x551:
            msg[1] = bounded(clt.Value, -40, 215) + 40;
            msg[5] = (msg[5] & 0x0F) | (m_counter551 << 4);
            m_counter551 = (m_counter551 + 1) & 0x0F;
            break;
        default:
            break;
        }
    }
    m_tick = (m_tick + 1) % 200;
}

void updateM749VehicleCan(CanCycle) {
    static M749VehicleCanTransmitter transmitter;
    transmitter.update();
}
