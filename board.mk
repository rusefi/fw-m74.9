# List of all the board related files.
BOARDCPPSRC = $(BOARD_DIR)/board_configuration.cpp \
  $(BOARD_DIR)/firmware/bootloader_handoff.cpp \
  $(BOARD_DIR)/firmware/boot_activation.cpp \
  $(BOARD_DIR)/firmware/board_storage.cpp

override LDSCRIPT = $(BOARD_DIR)/firmware/m749.ld
ALLXASMSRC += $(BOARD_DIR)/firmware/m749_startup.S
BOARD_IMAGE_SCRIPT = $(BOARD_DIR)/bin/m749_image.py
BOARD_IMAGE_README = $(BOARD_DIR)/readme.md
DO_NOT_BUNDLE_STM32_PROG = yes
BUNDLE_PCAN = yes
ifneq ($(filter yes,$(USE_OPENBLT)),)
$(error M74.9 uses its resident OEM loader; OpenBLT replacement is forbidden)
endif

DDEFS += -DLED_CRITICAL_ERROR_BRAIN_PIN=Gpio::Unassigned

IS_AT32F435 = yes

# TIM5 drives the microsecond scheduler through the PWM HAL. Its default IRQ
# priority is 7, but the scheduling contract requires priority 3 on this port.
DDEFS += -DSTM32_PWM_TIM5_IRQ_PRIORITY=EFI_IRQ_SCHEDULING_TIMER_PRIORITY
# TIM6 invokes fastAdcStartTrigger through the GPT HAL, which otherwise uses 7.
DDEFS += -DSTM32_GPT_TIM6_IRQ_PRIORITY=EFI_IRQ_ADC_PRIORITY

# Use a distinct filename so simulator VPATH cannot select the hardware board.c.
BOARD_C = $(BOARD_DIR)/m74_9_board.c
# board.h from this directory
BOARDINC = $(BOARD_DIR)

#This board has no USB wired out
DDEFS += -DSTM32_USB_USE_OTG1=FALSE
DDEFS += -DSTM32_USB_USE_OTG2=FALSE

DDEFS += -DBOARD_L9779_COUNT=1
DDEFS += -DEFI_UDS=TRUE

# This board has no SD card storage
DDEFS += -DEFI_FILE_LOGGING=FALSE
DDEFS += -DEFI_STORAGE_SD=FALSE
USE_FATFS = no

# Configuration directorys
CONFDIR = $(PROJECT_DIR)/hw_layer/ports/at32/at32f4/cfg

# M74.9 MFS banks live at 0x08300000-0x0837FFFF, above OEM loader/NVM.
# Never use the generic AT32 bank-2 sector 0/32 storage configuration.
DDEFS += -DHAL_USE_EFL=TRUE
DDEFS += -DEFI_STORAGE_INT_FLASH=FALSE
include $(PROJECT_DIR)/hw_layer/ports/stm32/use_higher_level_flash_api.mk

DDEFS += -DFIRMWARE_ID=\"m74_9\"
DDEFS += -DDEFAULT_ENGINE_TYPE=engine_type_e::MINIMAL_PINS
DDEFS += -DSTATIC_BOARD_ID=STATIC_BOARD_ID_M74_9

DDEFS += -DEFI_BACKUP_SRAM=FALSE

# Custom firmware metadata and generated configuration headers.
include $(BOARD_DIR)/meta-info.env
BOARDINC += $(BOARD_DIR)/generated/controllers/generated

DDEFS += -DEFI_WIDEBAND_FIRMWARE_UPDATE=FALSE
