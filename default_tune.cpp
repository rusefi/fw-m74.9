#include "pch.h"
#include "default_tune.h"

void boardTuneDefaults() {
    engineConfiguration->displacement = 1.6;
}

void setM749IntakeRunnerDefaults() {
    auto& intake = engineConfiguration->gppwm[0];
    strcpy(engineConfiguration->gpPwmNote[0], "Intake runner");
    intake.pin = Gpio::E6;
    intake.pwmFrequency = 0; // On/off solenoid, controlled by the tune table.
    intake.rpmAxis = GPPWM_Rpm;
    intake.loadAxis = GPPWM_Tps;
    intake.onAboveDuty = 60;
    intake.offBelowDuty = 50;
    intake.dutyIfError = 0;

    for (auto& row : intake.table) {
        for (auto& duty : row) {
            duty = 0; // Valve off selects the long path; 100 selects short.
        }
    }
}
