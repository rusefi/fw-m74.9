/**
 * @file boards/m74_9/efifeatures.h
 *
 * @brief In this header we can override efifeatures.h.
 *
 * @date Jan 01, 2023
 * @author Andrey Gusakov, 2023
 */

/* Override some settings */
#define HAL_USE_MMC_SPI FALSE
#define EFI_FILE_LOGGING FALSE

/* This board has no USB wired out */
#define EFI_USB_SERIAL              FALSE

#include "ext/rusefi/firmware/config/stm32f4ems/efifeatures.h"
