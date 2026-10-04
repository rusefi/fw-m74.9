ifeq ($(PROJECT_CPU),simulator)
# Simulator rules sort INCDIR, which can put the generic board_types.h first.
# Keep the board's configuration types ahead of those sorted include paths.
RUSEFI_CPPOPT += -I$(BOARD_DIR)/firmware
else
BOARDCPPSRC += \
    $(BOARD_DIR)/firmware/hardware/board_hw_test.cpp \
    $(BOARD_DIR)/board_configuration.cpp \

endif
