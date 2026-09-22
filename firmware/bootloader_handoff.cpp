#include "pch.h"
#include "bootloader_handoff.h"
#include "board_overrides.h"
#include "can.h"

// This section is NOLOAD and excluded from the C runtime's RAM clear ranges.
__attribute__((section(".boot_intent"), used)) volatile uint32_t m749BootIntent;

// Own CAN1 while awaiting this exact frame's physical completion. The normal
// HAL return means only "mailbox loaded". Suppress its TX ISR temporarily so
// TXOK cannot be cleared before we inspect it; RX/error interrupts stay enabled.
static bool transmitCompleted(const CANTxFrame& frame) {
    CanTxMessage::removeDevice(0);
    const auto start = chVTGetSystemTimeX();
    constexpr auto timeout = TIME_MS2I(50);
    const uint32_t empty = CAN_TSR_TME0 | CAN_TSR_TME1 | CAN_TSR_TME2;
    while ((CAND1.can->TSR & empty) != empty) {
        if (chVTTimeElapsedSinceX(start) >= timeout) {
            CanTxMessage::setDevice(0, &CAND1);
            return false;
        }
        chThdSleepMilliseconds(1);
    }

    chSysLock();
    constexpr auto txIrq = static_cast<IRQn_Type>(STM32_CAN1_TX_NUMBER);
    const auto txInterrupt = NVIC_GetEnableIRQ(txIrq);
    // Mask at the NVIC, including an interrupt already pending from the drain.
    NVIC_DisableIRQ(txIrq);
    __DSB();
    __ISB();
    CAND1.can->TSR = CAN_TSR_RQCP0;
    can_lld_transmit(&CAND1, 1, &frame);
    chSysUnlock();

    uint32_t status;
    do {
        status = CAND1.can->TSR;
        if (status & CAN_TSR_RQCP0) {
            break;
        }
        chThdSleepMilliseconds(1);
    } while (chVTTimeElapsedSinceX(start) < timeout);

    const bool completed = (status & (CAN_TSR_RQCP0 | CAN_TSR_TXOK0)) ==
                           (CAN_TSR_RQCP0 | CAN_TSR_TXOK0);
    chSysLock();
    if (!completed) {
        CAND1.can->TSR = CAN_TSR_ABRQ0;
    }
    if (txInterrupt) {
        NVIC_EnableIRQ(txIrq);
    }
    chSysUnlock();
    CanTxMessage::setDevice(0, &CAND1);
    return completed;
}

static void processDiagnosticRequest(size_t bus, const CANRxFrame& frame, efitick_t) {
    m749::handleRequest(bus, CAN_SID(frame), CAN_ISX(frame), CAN_ISRTR(frame),
        frame.data8, frame.DLC, engine->rpmCalculator.isStopped(),
        [](const uint8_t* payload) {
            CANTxFrame response = {};
            response.SID = 0x7E8;
            response.DLC = 8;
            memcpy(response.data8, payload, 8);
            return transmitCompleted(response);
        },
        [](uint32_t token) {
            __disable_irq();
            m749BootIntent = token;
            __DSB();
            NVIC_SystemReset();
        });
}

void initM749BootloaderHandoff() {
    custom_board_can_rx = processDiagnosticRequest;
}
