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

/* USB serial on OTG1, PA11 D- and PA12 D+. */
#define EFI_USB_SERIAL              TRUE

#include "ext/rusefi/firmware/config/stm32f4ems/efifeatures.h"
