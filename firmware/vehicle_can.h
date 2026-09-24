#pragma once

#include "can_sensor.h"

// CAN1 vehicle speed, in km/h. Integrity failures never refresh the sensor.
class M749VehicleSpeedSensor : public CanSensorBase {
public:
    M749VehicleSpeedSensor();
    void reset();
    bool acceptFrame(size_t busIndex, const CANRxFrame& frame) const override;

protected:
    void decodeFrame(const CANRxFrame& frame, efitick_t nowNt) override;

private:
    bool m_haveCounter = false;
    uint8_t m_counter = 0;
};

bool useM749VehicleSpeed();
void initM749VehicleCan();
void stopM749VehicleCan();
void startM749VehicleCan();

// Called once per 5 ms CAN worker tick; no blocking and no catch-up bursts.
class M749VehicleCanTransmitter {
public:
    void update();
private:
    uint16_t m_tick = 0;
};
void updateM749VehicleCan(CanCycle cycle);
