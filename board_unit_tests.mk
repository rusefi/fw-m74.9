BOARDINC += $(BOARD_DIR)/firmware $(BOARD_DIR)/generated/controllers/generated
TESTS_SRC_CPP += $(BOARD_DIR)/tests/test_example.cpp \
  $(BOARD_DIR)/tests/test_board_analog.cpp \
  $(BOARD_DIR)/tests/test_vehicle_can.cpp \
  $(BOARD_DIR)/tests/test_vehicle_can_tx.cpp
BOARDCPPSRC += $(BOARD_DIR)/firmware/vehicle_can.cpp \
  $(BOARD_DIR)/firmware/board_analog.cpp \
  $(BOARD_DIR)/firmware/vehicle_can_tx.cpp
