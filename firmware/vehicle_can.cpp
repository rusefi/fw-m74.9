#include "pch.h"
#include "vehicle_can.h"

M749VehicleSpeedSensor::M749VehicleSpeedSensor()
    : CanSensorBase(0x29A, SensorType::VehicleSpeed, MS2NT(100)) {
}

bool M749VehicleSpeedSensor::acceptFrame(size_t busIndex, const CANRxFrame& frame) const {
    if (!useM749VehicleSpeed() || busIndex != 0 || CAN_ISX(frame) || CAN_ISRTR(frame)) {
        return false;
    }
    switch (config->ladaCanbusProfile) {
    case LadaCanbusProfile::Largus:
        return CAN_SID(frame) == 0x29A && frame.DLC == 8;
    case LadaCanbusProfile::NivaGranta:
        return CAN_SID(frame) == 0x28C && frame.DLC == 2;
    default:
        return false;
    }
}

void M749VehicleSpeedSensor::decodeFrame(const CANRxFrame& frame, efitick_t nowNt) {
    unsigned offset = 0;
    if (config->ladaCanbusProfile == LadaCanbusProfile::Largus) {
        uint8_t sum = 0;
        for (unsigned i = 0; i < 7; i++) {
            sum += frame.data8[i];
        }
        if (frame.data8[7] != static_cast<uint8_t>(~sum)) {
            return;
        }

        const uint8_t counter = frame.data8[6] & 0x0F;
        if (m_haveCounter && counter == m_counter) {
            return;
        }
        // Any change is sufficient, including skips, backward steps and wrap.
        // Remember the counter across a timeout so a stuck stream cannot revive it.
        m_haveCounter = true;
        m_counter = counter;
        offset = 4;
    }

    // Niva/Granta's two-byte speed frame has no counter or checksum.
    const uint16_t raw = (uint16_t(frame.data8[offset]) << 8) | frame.data8[offset + 1];
    if (raw == 0xFFFF) {
        invalidate();
        return;
    }

    const uint16_t limited = raw > 50000 ? 50000 : raw;
    setValidValue(limited * 0.01f, nowNt);
}

bool useM749VehicleSpeed() {
    const bool supported = config->ladaCanbusProfile == LadaCanbusProfile::Largus
        || config->ladaCanbusProfile == LadaCanbusProfile::NivaGranta;
    return supported && !engineConfiguration->enableCanVss
        && !isBrainPinValid(engineConfiguration->vehicleSpeedSensorInputPin);
}

void M749VehicleSpeedSensor::reset() {
    invalidate();
    m_haveCounter = false;
    m_counter = 0;
}

#if EFI_CAN_SUPPORT
static M749VehicleSpeedSensor speed;

void initM749VehicleCan() {
    // Keep the listener registered for live profile changes; RX checks selection.
    registerCanListener(speed);
    startM749VehicleCan();
}

void startM749VehicleCan() {
    // Explicitly configured sources take precedence, even before initialization.
    if (useM749VehicleSpeed() && !Sensor::hasSensor(SensorType::VehicleSpeed)) {
        speed.Register();
    }
}

void stopM749VehicleCan() {
    // Release only our slot before configuration applies another speed source.
    speed.unregister();
    speed.reset();
}
#endif
